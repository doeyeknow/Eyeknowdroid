import app.eyeknow.android.AutoCaptureGate;
import app.eyeknow.android.BridgeMessages;
import app.eyeknow.android.CameraChoice;
import app.eyeknow.android.CropMath;
import app.eyeknow.android.FocusMath;
import app.eyeknow.android.IrisDetector;
import app.eyeknow.android.IrisFrame;
import app.eyeknow.android.YuvFrame;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JVM harness for the eyeKnow shell's pure-Java pieces: the bridge
 * wire format, the camera-selection rule, crop math, focus /
 * zoom math, YUV conversion, iris detection, and the
 * auto-capture gate. Runs the exact shipping classes. No Android
 * types.
 *
 * Compile: javac -d /tmp/ekh ../src/app/eyeknow/android/{BridgeMessages,CameraChoice,CropMath,FocusMath,IrisFrame,YuvFrame,IrisDetector,AutoCaptureGate}.java Harness.java
 * Run:     java -cp /tmp/ekh Harness
 */
public class Harness {

    private static int checks;
    private static int failures;

    private static void check(boolean ok, String name) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("FAIL: " + name);
        }
    }

    private static void eq(Object a, Object b, String name) {
        check(a == null ? b == null : a.equals(b), name + " (got " + a + ", want " + b + ")");
    }

    private static void eqArr(int[] a, int[] b, String name) {
        check(Arrays.equals(a, b), name + " (got " + Arrays.toString(a)
                + ", want " + Arrays.toString(b) + ")");
    }

    public static void main(String[] args) {
        bridge();
        cameraChoice();
        cropMath();
        focusMath();
        yuvFrame();
        detector();
        gate();
        System.out.println("Harness: " + (checks - failures) + "/" + checks + " checks passed");
        if (failures > 0) System.exit(1);
    }

    private static void bridge() {
        // Parse: valid capture request.
        BridgeMessages.Request r = BridgeMessages.parseRequest(
                "{\"type\":\"captureEye\",\"requestId\":\"abc-123\",\"eye\":\"left\"}");
        check(r != null && r.isCapture() && "abc-123".equals(r.requestId)
                && "left".equals(r.eye), "bridge: parse captureEye left");
        // Parse: right eye.
        r = BridgeMessages.parseRequest(
                "{\"type\":\"captureEye\",\"requestId\":\"x\",\"eye\":\"right\"}");
        check(r != null && "right".equals(r.eye), "bridge: parse captureEye right");
        // Parse: cancel.
        r = BridgeMessages.parseRequest(
                "{\"type\":\"cancelEyeCapture\",\"requestId\":\"abc-123\"}");
        check(r != null && r.isCancel() && "abc-123".equals(r.requestId),
                "bridge: parse cancelEyeCapture");
        // Parse: rejects unknown type, bad eye, missing id, garbage.
        check(BridgeMessages.parseRequest("{\"type\":\"nope\",\"requestId\":\"x\"}") == null,
                "bridge: reject unknown type");
        check(BridgeMessages.parseRequest(
                "{\"type\":\"captureEye\",\"requestId\":\"x\",\"eye\":\"middle\"}") == null,
                "bridge: reject bad eye");
        check(BridgeMessages.parseRequest("{\"type\":\"captureEye\",\"eye\":\"left\"}") == null,
                "bridge: reject missing requestId");
        check(BridgeMessages.parseRequest("not json") == null, "bridge: reject garbage");
        check(BridgeMessages.parseRequest(null) == null, "bridge: reject null");
        check(BridgeMessages.parseRequest(
                "{\"type\":\"captureEye\",\"requestId\":\"\",\"eye\":\"left\"}") == null,
                "bridge: reject empty requestId");
        // Parse: whitespace + field order + escapes.
        r = BridgeMessages.parseRequest(
                "{ \"eye\" : \"left\", \"requestId\" : \"a\\\"b\", \"type\" : \"captureEye\" }");
        check(r != null && "a\"b".equals(r.requestId), "bridge: parse with escapes/reorder");

        // Replies: exact shapes the web side parses.
        String cap = BridgeMessages.capturedReply("r1", "data:image/jpeg;base64,AAAA", 0, "still");
        eq("{\"requestId\":\"r1\",\"status\":\"captured\",\"capture\":{\"display\":"
                + "\"data:image/jpeg;base64,AAAA\",\"stackedFrames\":0,\"source\":\"still\"}}",
                cap, "bridge: captured reply shape");
        eq("{\"requestId\":\"r1\",\"status\":\"cancelled\"}",
                BridgeMessages.cancelledReply("r1"), "bridge: cancelled reply shape");
        eq("{\"requestId\":\"r1\",\"status\":\"error\",\"message\":\"boom\"}",
                BridgeMessages.errorReply("r1", "boom"), "bridge: error reply shape");
        // Error message quoting: quotes/newlines escaped.
        String err = BridgeMessages.errorReply("r1", "say \"hi\"\nbye");
        check(err.contains("\\\"hi\\\"") && err.contains("\\n"),
                "bridge: error message escaped");
        // Display validation: the web side's exact rule.
        check(BridgeMessages.isValidDisplay("data:image/jpeg;base64,AAAA"),
                "bridge: valid display accepted");
        check(!BridgeMessages.isValidDisplay("data:image/png;base64,AAAA"),
                "bridge: png display rejected");
        check(!BridgeMessages.isValidDisplay("data:image/jpeg;base64,"),
                "bridge: empty display rejected");
        check(!BridgeMessages.isValidDisplay(null), "bridge: null display rejected");
        // Delivery script: data is a quoted JSON string.
        String script = BridgeMessages.deliveryScript("{\"a\":1}");
        check(script.contains("window.EyeknowCapture.onmessage({data:\"{\\\"a\\\":1}\"})"),
                "bridge: delivery script quotes reply as string: " + script);
        // Quote: control chars + unicode-safe.
        eq("\"a\\u0001b\"", BridgeMessages.quote("a\u0001b"), "bridge: quote control char");
    }

    private static CameraChoice.Lens lens(String id, boolean back, float diopters) {
        return new CameraChoice.Lens(id, back, diopters, false, null);
    }

    private static void cameraChoice() {
        // Empty list -> null.
        check(CameraChoice.choose(new ArrayList<>()) == null, "choice: empty -> null");
        check(CameraChoice.choose(null) == null, "choice: null -> null");
        // Single back camera -> Main.
        CameraChoice.Choice c = CameraChoice.choose(
                Arrays.asList(lens("0", true, 1.5f)));
        check(c != null && "0".equals(c.cameraId) && "Main".equals(c.label),
                "choice: single back is Main");
        // Closest-focus back camera wins.
        c = CameraChoice.choose(Arrays.asList(
                lens("0", true, 1.0f), lens("2", true, 8.0f), lens("1", false, 0f)));
        check(c != null && "2".equals(c.cameraId), "choice: closest-focus back wins");
        // Distinct macro: 2+ diopters closer than the main lens.
        check("Macro".equals(c.label), "choice: distinct close lens labelled Macro");
        // Small edge (< 2 diopters) is NOT macro — honesty rule.
        c = CameraChoice.choose(Arrays.asList(lens("0", true, 1.0f), lens("2", true, 2.5f)));
        check(c != null && "2".equals(c.cameraId) && "Main".equals(c.label),
                "choice: small edge stays Main, never claims macro");
        // Front only -> Front, labelled honestly.
        c = CameraChoice.choose(Arrays.asList(lens("1", false, 0f)));
        check(c != null && "Front".equals(c.label), "choice: front-only labelled Front");
        // Fixed-focus back camera still chosen over front.
        c = CameraChoice.choose(Arrays.asList(lens("0", true, 0f), lens("1", false, 5f)));
        check(c != null && "0".equals(c.cameraId) && "Main".equals(c.label),
                "choice: fixed-focus back beats front");
        // Near-plane display math.
        eq(10, CameraChoice.nearPlaneCm(10f), "choice: 10 diopters = 10cm");
        eq(50, CameraChoice.nearPlaneCm(2f), "choice: 2 diopters = 50cm");
        eq(-1, CameraChoice.nearPlaneCm(0f), "choice: 0 diopters = unknown");
    }

    private static void cropMath() {
        // Default crop: 72% of short side, centered.
        eq(new CropMath.Rect(212, 112, 576),
                CropMath.defaultCrop(1000, 800), "crop: default 1000x800");
        eq(new CropMath.Rect(112, 212, 576),
                CropMath.defaultCrop(800, 1000), "crop: default portrait");
        // Clamp: size to short side, position inside.
        eq(new CropMath.Rect(0, 0, 800),
                CropMath.clamp(new CropMath.Rect(-50, -50, 900), 1000, 800),
                "crop: clamp oversize+negative");
        eq(new CropMath.Rect(200, 0, 800),
                CropMath.clamp(new CropMath.Rect(900, 700, 800), 1000, 800),
                "crop: clamp overflow position");
        // Minimum size enforced.
        CropMath.Rect small = CropMath.clamp(new CropMath.Rect(0, 0, 10), 1000, 800);
        check(small.size == Math.round(800 * CropMath.MIN_FRACTION),
                "crop: minimum size enforced, got " + small.size);
        // Move + scale.
        eq(new CropMath.Rect(150, 150, 120),
                CropMath.move(new CropMath.Rect(100, 100, 100), 50, 50, 1000, 800),
                "crop: move (clamp grows 100px crop to the 120px minimum)");
        eq(new CropMath.Rect(800, 600, 200),
                CropMath.move(new CropMath.Rect(900, 700, 200), 500, 500, 1000, 800),
                "crop: move clamps at edge");
        eq(new CropMath.Rect(50, 50, 200),
                CropMath.scale(new CropMath.Rect(100, 100, 100), 2f, 1000, 800),
                "crop: scale 2x around center");
        // View -> image mapping: letterboxed fit-center.
        // View 500x500, image 1000x500: scale 0.5, offset y=125.
        // View rect (100,175,200) -> image (200,100,400).
        eq(new CropMath.Rect(200, 100, 400),
                CropMath.viewToImage(new CropMath.Rect(100, 175, 200), 500, 500, 1000, 500),
                "crop: viewToImage letterbox");
        // Round-trip image -> view -> image.
        CropMath.Rect img = new CropMath.Rect(200, 100, 400);
        CropMath.Rect view = CropMath.imageToView(img, 500, 500, 1000, 500);
        eq(img, CropMath.viewToImage(view, 500, 500, 1000, 500),
                "crop: image->view->image round trip");
        // Sample size: powers of two to the 2560 cap.
        eq(1, CropMath.sampleSize(2560, 1920), "crop: sample 2560 fits");
        eq(2, CropMath.sampleSize(4000, 3000), "crop: sample 4000 -> 2");
        eq(4, CropMath.sampleSize(8160, 6120), "crop: sample 50MP -> 4");
        eq(1, CropMath.sampleSize(800, 600), "crop: sample small -> 1");
        // Capped size.
        eqArr(new int[]{2560, 1920}, CropMath.cappedSize(4000, 3000),
                "crop: capped 4000x3000");
        eqArr(new int[]{800, 600}, CropMath.cappedSize(800, 600), "crop: capped no-op");
        eqArr(new int[]{1920, 2560}, CropMath.cappedSize(3000, 4000),
                "crop: capped portrait");
    }

    private static void focusMath() {
        int[] active = {0, 0, 4000, 3000};
        // Zoom 1 = whole active array.
        eqArr(active, FocusMath.cropRegion(active, 1f, 4f), "focus: zoom 1 = full array");
        // Zoom 2 = centered half.
        eqArr(new int[]{1000, 750, 3000, 2250},
                FocusMath.cropRegion(active, 2f, 4f), "focus: zoom 2 centered");
        // Zoom clamps to max.
        eqArr(new int[]{1500, 1125, 2500, 1875},
                FocusMath.cropRegion(active, 9f, 4f), "focus: zoom clamps to max");
        eqArr(active, FocusMath.cropRegion(active, 0.5f, 4f), "focus: zoom clamps to 1");
        // Non-zero-origin active array stays inside.
        int[] off = {100, 100, 4100, 3100};
        int[] cr = FocusMath.cropRegion(off, 2f, 4f);
        check(cr[0] >= 100 && cr[1] >= 100 && cr[2] <= 4100 && cr[3] <= 3100,
                "focus: offset array stays inside");
        // Focus box: center tap at zoom 1.
        int[] box = FocusMath.focusBox(0.5f, 0.5f, active, active);
        int side = Math.round(0.14f * 3000);
        eqArr(new int[]{2000 - side / 2, 1500 - side / 2, 2000 - side / 2 + side,
                1500 - side / 2 + side}, box, "focus: center box");
        // Focus box: corner tap clamps inside the active array.
        box = FocusMath.focusBox(0f, 0f, active, active);
        check(box[0] == 0 && box[1] == 0, "focus: corner tap clamps to origin");
        box = FocusMath.focusBox(1f, 1f, active, active);
        check(box[2] <= 4000 && box[3] <= 3000, "focus: far corner clamps inside");
        // Focus box with a zoomed crop: tap maps into the crop region.
        int[] crop = {1000, 750, 3000, 2250};
        box = FocusMath.focusBox(0.5f, 0.5f, crop, active);
        int cSide = Math.round(0.14f * 1500);
        eqArr(new int[]{2000 - cSide / 2, 1500 - cSide / 2, 2000 - cSide / 2 + cSide,
                1500 - cSide / 2 + cSide}, box, "focus: zoomed center box");
        // Out-of-range fractions clamp.
        box = FocusMath.focusBox(-1f, 2f, active, active);
        check(box[0] == 0 && box[3] <= 3000, "focus: fractions clamp");
    }

    // ------------------------------------------------------------ YUV

    private static void yuvFrame() {
        // Gray 4x4: Y=100, U=V=128 -> gray 100, sat ~0.
        ByteBuffer y = ByteBuffer.allocate(16);
        ByteBuffer u = ByteBuffer.allocate(4);
        ByteBuffer v = ByteBuffer.allocate(4);
        for (int i = 0; i < 16; i++) y.put((byte) 100);
        for (int i = 0; i < 4; i++) { u.put((byte) 128); v.put((byte) 128); }
        IrisFrame f = YuvFrame.convert(4, 4, y, 4, u, 2, 1, v, 2, 1);
        check(f.width == 4 && f.height == 4, "yuv: tiny frame keeps size");
        check(f.gray[5] == 100, "yuv: gray from Y plane");
        check(f.sat[5] < 0.05f, "yuv: gray has ~zero saturation, got " + f.sat[5]);
        // Saturated red: Y=76, U=85, V=255 -> sat near 1.
        y.clear(); u.clear(); v.clear();
        for (int i = 0; i < 16; i++) y.put((byte) 76);
        for (int i = 0; i < 4; i++) { u.put((byte) 85); v.put((byte) 255); }
        f = YuvFrame.convert(4, 4, y, 4, u, 2, 1, v, 2, 1);
        check(f.sat[5] > 0.9f, "yuv: saturated red reads saturated, got " + f.sat[5]);
        // Strides honored: Y row stride 8 on a 4-wide image, chroma
        // pixel stride 2 (interleaved-style layout).
        ByteBuffer y2 = ByteBuffer.allocate(32);
        ByteBuffer u2 = ByteBuffer.allocate(8);
        ByteBuffer v2 = ByteBuffer.allocate(8);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) y2.put(row * 8 + col, (byte) (50 + row * 10));
        }
        for (int i = 0; i < 4; i++) { u2.put(i * 2, (byte) 128); v2.put(i * 2, (byte) 128); }
        f = YuvFrame.convert(4, 4, y2, 8, u2, 4, 2, v2, 4, 2);
        check(f.gray[0] == 50 && f.gray[4] == 60 && f.gray[12] == 80,
                "yuv: row stride honored, got " + f.gray[0] + "/" + f.gray[4] + "/" + f.gray[12]);
        // Downsampling: 640x480 -> 320 wide, aspect preserved.
        int w = 640, h = 480;
        ByteBuffer y3 = ByteBuffer.allocate(w * h);
        ByteBuffer u3 = ByteBuffer.allocate((w / 2) * (h / 2));
        ByteBuffer v3 = ByteBuffer.allocate((w / 2) * (h / 2));
        for (int i = 0; i < w * h; i++) y3.put((byte) 140);
        for (int i = 0; i < (w / 2) * (h / 2); i++) { u3.put((byte) 128); v3.put((byte) 128); }
        f = YuvFrame.convert(w, h, y3, w, u3, w / 2, 1, v3, w / 2, 1);
        check(f.width == 320 && f.height == 240,
                "yuv: 640x480 downsamples to 320x240, got " + f.width + "x" + f.height);
        check(f.gray[100] == 140, "yuv: downsampled gray intact");
    }

    // ------------------------------------------------------------ detector

    /** Deterministic pseudo-random noise in [-amp, +amp]. */
    private static float noise(int x, int y, int amp) {
        int s = (x * 374761393 + y * 668265263) ^ 0x5bf03635;
        s = (s ^ (s >> 13)) * 1274126177;
        s = s ^ (s >> 16);
        return ((s & 0xFFFF) / 32768f - 1f) * amp;
    }

    /**
     * Synthetic eye: bright low-saturation sclera, a saturated iris
     * annulus with radial fiber striping (a smooth component plus
     * fine near-pixel-scale striping, the part real defocus kills),
     * a dark pupil disc. blurPasses applies a 7x7 box blur to
     * gray+sat — a realistic defocus kernel at this size, unlike a
     * 3x3 blur which barely softens anything.
     */
    private static IrisFrame syntheticEye(int w, int h, float cx, float cy,
            float pupilR, float irisR, float irisLum, float irisSat,
            float fiberAmp, int blurPasses) {
        return syntheticEye(w, h, cx, cy, pupilR, irisR, irisLum, irisSat,
                fiberAmp, blurPasses, false);
    }

    private static IrisFrame syntheticEye(int w, int h, float cx, float cy,
            float pupilR, float irisR, float irisLum, float irisSat,
            float fiberAmp, int blurPasses, boolean eyelid) {
        int[] gray = new int[w * h];
        float[] sat = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float dx = x - cx;
                float dy = y - cy;
                float d = (float) Math.hypot(dx, dy);
                int o = y * w + x;
                if (eyelid && y < cy - irisR * 0.45f) {
                    // Skin/eyelid over the top of the iris.
                    gray[o] = 150;
                    sat[o] = 0.25f;
                    continue;
                }
                if (d < pupilR) {
                    gray[o] = 12;
                    sat[o] = 0.05f;
                } else if (d < irisR) {
                    double th = Math.atan2(dx, -dy);
                    float fiber = (float) Math.sin(th * 28 + d * 0.35) * fiberAmp
                            + (float) Math.sin(th * 85 + d * 1.1) * (fiberAmp * 0.45f);
                    gray[o] = Math.max(0, Math.min(255,
                            Math.round(irisLum + fiber + noise(x, y, 5))));
                    sat[o] = irisSat;
                } else {
                    gray[o] = 215;
                    sat[o] = 0.04f;
                }
            }
        }
        for (int p = 0; p < blurPasses; p++) {
            gray = boxBlur(gray, w, h);
            sat = boxBlurF(sat, w, h);
        }
        return new IrisFrame(w, h, gray, sat);
    }

    private static int[] boxBlur(int[] src, int w, int h) {
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sum = 0, n = 0;
                for (int dy = -3; dy <= 3; dy++) {
                    int yy = Math.max(0, Math.min(h - 1, y + dy));
                    for (int dx = -3; dx <= 3; dx++) {
                        int xx = Math.max(0, Math.min(w - 1, x + dx));
                        sum += src[yy * w + xx];
                        n++;
                    }
                }
                out[y * w + x] = sum / n;
            }
        }
        return out;
    }

    private static float[] boxBlurF(float[] src, int w, int h) {
        float[] out = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float sum = 0;
                int n = 0;
                for (int dy = -3; dy <= 3; dy++) {
                    int yy = Math.max(0, Math.min(h - 1, y + dy));
                    for (int dx = -3; dx <= 3; dx++) {
                        int xx = Math.max(0, Math.min(w - 1, x + dx));
                        sum += src[yy * w + xx];
                        n++;
                    }
                }
                out[y * w + x] = sum / n;
            }
        }
        return out;
    }

    private static void detector() {
        // Sharp centered eye: pupil found, edge traced, gates pass.
        IrisFrame sharp = syntheticEye(320, 240, 160, 120, 22, 78, 110, 0.45f, 18, 0);
        IrisDetector.Result r = IrisDetector.detect(sharp);
        System.out.println("  [cal] sharp eye: pupil=" + r.pupilFound
                + " cx=" + r.cx + " cy=" + r.cy + " pupilR=" + r.pupilR
                + " irisR=" + r.irisR + " validFrac=" + r.validFrac
                + " sharp=" + r.sharpness + " meanLum=" + r.meanLum);
        check(r.pupilFound, "detect: pupil found on sharp eye");
        check(Math.abs(r.cx - 0.5f) < 0.03f && Math.abs(r.cy - 0.5f) < 0.03f,
                "detect: pupil center within 3%");
        check(Math.abs(r.pupilR - 22) < 6, "detect: pupil radius ~22, got " + r.pupilR);
        check(Math.abs(r.irisR - 78) < 10, "detect: iris radius ~78, got " + r.irisR);
        check(r.validFrac > 0.8f, "detect: validFrac high on clean eye, got " + r.validFrac);
        check(r.irisPresent && r.wellFramed, "detect: gates pass on clean eye");
        check(r.sharpness >= IrisDetector.SHARPNESS_FLOOR,
                "detect: sharp eye clears the sharpness floor, got " + r.sharpness);

        // Same eye, defocused: geometry still found, sharpness fails.
        IrisFrame soft = syntheticEye(320, 240, 160, 120, 22, 78, 110, 0.45f, 18, 4);
        IrisDetector.Result rs = IrisDetector.detect(soft);
        System.out.println("  [cal] soft eye: pupil=" + rs.pupilFound
                + " validFrac=" + rs.validFrac + " sharp=" + rs.sharpness);
        check(rs.pupilFound, "detect: pupil still found when soft");
        check(rs.sharpness < IrisDetector.SHARPNESS_FLOOR,
                "detect: soft eye fails the sharpness floor, got " + rs.sharpness);
        check(rs.sharpness < r.sharpness, "detect: blur lowers sharpness");

        // Dark iris: still detected (the grow cap must not swallow it).
        IrisFrame dark = syntheticEye(320, 240, 160, 120, 22, 78, 55, 0.30f, 14, 0);
        IrisDetector.Result rd = IrisDetector.detect(dark);
        System.out.println("  [cal] dark eye: pupil=" + rd.pupilFound
                + " pupilR=" + rd.pupilR + " validFrac=" + rd.validFrac
                + " sharp=" + rd.sharpness);
        check(rd.pupilFound && rd.irisPresent, "detect: dark iris passes gates");
        check(rd.pupilR < 44, "detect: dark-iris pupil not swollen past 2x, got " + rd.pupilR);

        // Eyelid over the top: partial trace, still present.
        IrisFrame lid = syntheticEye(320, 240, 160, 120, 22, 78, 110, 0.45f, 18, 0, true);
        IrisDetector.Result rl = IrisDetector.detect(lid);
        System.out.println("  [cal] eyelid eye: validFrac=" + rl.validFrac
                + " present=" + rl.irisPresent);
        check(rl.pupilFound, "detect: pupil found under eyelid");
        check(rl.validFrac < 0.95f, "detect: eyelid lowers validFrac, got " + rl.validFrac);
        check(rl.irisPresent, "detect: eyelid eye still passes hard gates");

        // No eye: uniform frame.
        int[] uni = new int[320 * 240];
        Arrays.fill(uni, 150);
        float[] usat = new float[320 * 240];
        Arrays.fill(usat, 0.1f);
        IrisDetector.Result ru = IrisDetector.detect(new IrisFrame(320, 240, uni, usat));
        check(!ru.pupilFound && !ru.irisPresent, "detect: uniform frame has no iris");

        // Noise only: no compact round blob.
        int[] nz = new int[320 * 240];
        float[] nzs = new float[320 * 240];
        for (int i = 0; i < nz.length; i++) {
            nz[i] = 100 + (int) noise(i % 320, i / 320, 60);
            nzs[i] = 0.1f;
        }
        IrisDetector.Result rn = IrisDetector.detect(new IrisFrame(320, 240, nz, nzs));
        check(!rn.irisPresent, "detect: noise frame has no iris");

        // Off-center pupil: outside the 0.15-0.85 center window.
        IrisFrame off = syntheticEye(320, 240, 25, 120, 22, 78, 110, 0.45f, 18, 0);
        IrisDetector.Result ro = IrisDetector.detect(off);
        check(!ro.irisPresent, "detect: off-center eye rejected");

        // Tiny iris: below the 0.07 minDim radius floor.
        IrisFrame tiny = syntheticEye(320, 240, 160, 120, 6, 14, 110, 0.45f, 18, 0);
        IrisDetector.Result rt = IrisDetector.detect(tiny);
        check(!rt.irisPresent, "detect: tiny iris rejected, irisR=" + rt.irisR);
    }

    // ------------------------------------------------------------ gate

    private static IrisDetector.Result gateResult(boolean present, boolean framed,
            float sharpness, float cx, float cy, float irisR) {
        return new IrisDetector.Result(true, cx, cy, 22f, irisR, framed ? 0.8f : 0.4f,
                sharpness, 120f, present, framed);
    }

    private static void gate() {
        AutoCaptureGate g = new AutoCaptureGate();
        IrisDetector.Result good = gateResult(true, true, 9f, 0.5f, 0.5f, 78f);
        // Four consecutive passing feeds fire exactly once.
        check(g.feed(good, true, true) == AutoCaptureGate.State.STEADY, "gate: 1st steady");
        check(g.feed(good, true, true) == AutoCaptureGate.State.STEADY, "gate: 2nd steady");
        check(g.feed(good, true, true) == AutoCaptureGate.State.STEADY, "gate: 3rd steady");
        check(g.feed(good, true, true) == AutoCaptureGate.State.FIRED, "gate: 4th fires");
        check(g.feed(good, true, true) == AutoCaptureGate.State.FIRED, "gate: latched fired");
        check(g.hasFired(), "gate: hasFired");
        g.reset();
        check(!g.hasFired() && g.steadyCount() == 0, "gate: reset clears");

        // A failing feed resets the count.
        g.feed(good, true, true);
        g.feed(good, true, true);
        IrisDetector.Result soft = gateResult(true, true, 3f, 0.5f, 0.5f, 78f);
        check(g.feed(soft, true, true) == AutoCaptureGate.State.FOCUSING,
                "gate: soft feed -> FOCUSING");
        check(g.steadyCount() == 0, "gate: soft feed resets count");

        // No iris -> SEARCHING; present but unframed -> FOUND.
        IrisDetector.Result none = new IrisDetector.Result(
                false, 0.5f, 0.5f, 0f, 0f, 0f, 0f, 0f, false, false);
        check(g.feed(none, false, true) == AutoCaptureGate.State.SEARCHING,
                "gate: no iris -> SEARCHING");
        IrisDetector.Result unframed = gateResult(true, false, 9f, 0.5f, 0.5f, 78f);
        check(g.feed(unframed, true, true) == AutoCaptureGate.State.FOUND,
                "gate: unframed -> FOUND");

        // AF unfocused blocks even when sharp; unknown AF does not.
        check(g.feed(good, false, true) == AutoCaptureGate.State.FOCUSING,
                "gate: AF unfocused blocks");
        AutoCaptureGate g2 = new AutoCaptureGate();
        for (int i = 0; i < 3; i++) g2.feed(good, false, false);
        check(g2.feed(good, false, false) == AutoCaptureGate.State.FIRED,
                "gate: unknown AF fires on sharpness alone");

        // Drift beyond 3% restarts the window (counts the new frame as 1).
        AutoCaptureGate g3 = new AutoCaptureGate();
        g3.feed(good, true, true);
        g3.feed(good, true, true);
        IrisDetector.Result moved = gateResult(true, true, 9f, 0.55f, 0.5f, 78f);
        check(g3.feed(moved, true, true) == AutoCaptureGate.State.STEADY,
                "gate: drift restarts window");
        check(g3.steadyCount() == 1, "gate: drift restarts count at 1");

        // Small drift does not reset.
        AutoCaptureGate g4 = new AutoCaptureGate();
        g4.feed(good, true, true);
        IrisDetector.Result jitter = gateResult(true, true, 9f, 0.51f, 0.505f, 79f);
        g4.feed(jitter, true, true);
        check(g4.steadyCount() == 2, "gate: small drift keeps count");

        // Radius jump restarts the window.
        AutoCaptureGate g5 = new AutoCaptureGate();
        g5.feed(good, true, true);
        g5.feed(good, true, true);
        IrisDetector.Result zoomed = gateResult(true, true, 9f, 0.5f, 0.5f, 95f);
        g5.feed(zoomed, true, true);
        check(g5.steadyCount() == 1, "gate: radius jump restarts count");
    }
}
