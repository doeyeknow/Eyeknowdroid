import app.eyeknow.android.ApkStamp;
import app.eyeknow.android.AutoCaptureGate;
import app.eyeknow.android.AutoCrop;
import app.eyeknow.android.BridgeMessages;
import app.eyeknow.android.CameraChoice;
import app.eyeknow.android.CaptureStatus;
import app.eyeknow.android.CropDiagnostics;
import app.eyeknow.android.CropMath;
import app.eyeknow.android.CropVerify;
import app.eyeknow.android.FocusMath;
import app.eyeknow.android.IrisDetector;
import app.eyeknow.android.IrisFrame;
import app.eyeknow.android.RgbFrame;
import app.eyeknow.android.TrustedSite;
import app.eyeknow.android.YuvFrame;

import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * JVM harness for the eyeKnow shell's pure-Java pieces: the bridge
 * wire format, the camera-selection rule, crop math, focus /
 * zoom math, YUV conversion, iris detection, and the
 * auto-capture gate. Runs the exact shipping classes. No Android
 * types.
 *
 * Compile: javac -d /tmp/ekh ../src/app/eyeknow/android/{BridgeMessages,CameraChoice,CropMath,FocusMath,IrisFrame,YuvFrame,IrisDetector,AutoCaptureGate,AutoCrop,RgbFrame,CropVerify,CaptureStatus,CropDiagnostics,TrustedSite,ApkStamp}.java Harness.java
 * Run:     java -cp /tmp/ekh Harness
 *
 * The v1.4 verification leg loads the fixture
 * fixtures/adam-autocrop-20261116.jpg (Adam's real mis-framed
 * scan photo) — run from this directory. The v1.5 stamp leg
 * reads fixtures/stamped-real-v14-7f365261.apk (the real v1.4
 * APK stamped by the reference stamper) and writes the
 * synthetic stamped fixtures the web stamper's tests read
 * back (the Java-written half of the shared vectors).
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
        viewZoom();
        cameraChoice();
        cropMath();
        focusMath();
        yuvFrame();
        detector();
        gate();
        autoCrop();
        rotation();
        rgbFrame();
        captureStatus();
        cropVerify();
        cropDiagnostics();
        deepLink();
        stamp();
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

    private static void viewZoom() {
        // v1.7: the injected script is the shim install + the
        // root-element zoom in ONE string, built by
        // BridgeMessages and evaluated verbatim by CaptureBridge.
        String script = BridgeMessages.injectionScript("EyeknowNative");
        eq("0.7", BridgeMessages.VIEW_ZOOM, "zoom: VIEW_ZOOM is 0.7");
        check(script.contains("document.documentElement.style.zoom = '0.7'"),
                "zoom: injected script sets documentElement zoom 0.7: " + script);
        check(script.contains("window.EyeknowCapture = {"),
                "zoom: shim install present in the same injection");
        check(script.contains("postMessage: function(m) { window.EyeknowNative.postMessage(m); }"),
                "zoom: shim postMessage forward unchanged");
        check(script.contains("onmessage: null"),
                "zoom: shim onmessage null (site-owned) unchanged");
        check(script.indexOf("window.EyeknowCapture = {")
                        < script.indexOf("document.documentElement.style.zoom"),
                "zoom: shim installs before the zoom statement");
        eq("window.EyeknowCapture = {"
                        + "postMessage: function(m) { window.EyeknowNative.postMessage(m); },"
                        + "onmessage: null"
                        + "};"
                        + "document.documentElement.style.zoom = '0.7';",
                script, "zoom: exact injected script");
        check(script.indexOf("style.zoom") == script.lastIndexOf("style.zoom"),
                "zoom: exactly one zoom assignment");
        check(!script.contains("document.body"),
                "zoom: no body zoom (root element only)");
        eq(BridgeMessages.injectionScript("EyeknowNative"), script,
                "zoom: re-injection is byte-identical (idempotent)");
        check(BridgeMessages.deliveryScript("{\"a\":1}").contains("window.EyeknowCapture.onmessage")
                        && script.contains("window.EyeknowCapture"),
                "zoom: delivery target is the object the injection installs");
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
        // Sample size: powers of two to the 2560 PREVIEW cap.
        // The preview decode is unchanged by the 4096 output cap.
        eq(1, CropMath.sampleSize(2560, 1920), "crop: sample 2560 fits");
        eq(2, CropMath.sampleSize(4000, 3000), "crop: sample 4000 -> 2");
        eq(4, CropMath.sampleSize(8160, 6120), "crop: sample 50MP -> 4");
        eq(1, CropMath.sampleSize(800, 600), "crop: sample small -> 1");
        eq(2, CropMath.sampleSize(4096, 3000),
                "crop: sample 4096 still subsamples (preview cap is 2560)");
        eq(2, CropMath.sampleSize(2561, 1920), "crop: sample just over preview cap -> 2");
        // Capped size: the 4096 WRITTEN-output cap.
        eqArr(new int[]{4000, 3000}, CropMath.cappedSize(4000, 3000),
                "crop: capped 4000x3000 fits under 4096");
        eqArr(new int[]{800, 600}, CropMath.cappedSize(800, 600), "crop: capped no-op");
        eqArr(new int[]{3000, 4000}, CropMath.cappedSize(3000, 4000),
                "crop: capped portrait fits under 4096");
        eqArr(new int[]{4096, 3277}, CropMath.cappedSize(5000, 4000),
                "crop: capped 5000x4000 downscales to exactly 4096");
        eqArr(new int[]{4096, 3000}, CropMath.cappedSize(4096, 3000),
                "crop: capped exactly-4096 untouched");
        eqArr(new int[]{4096, 2999}, CropMath.cappedSize(4097, 3000),
                "crop: capped just over 4096 scales");
        eqArr(new int[]{4096, 3072}, CropMath.cappedSize(8192, 6144),
                "crop: capped 50MP-class downscales to 4096");
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

    // ------------------------------------------------------------ auto crop

    private static void autoCrop() {
        // Centered detection, all four rotations: the crop is the
        // same square, rotated with the frame. Frame 320x240,
        // still 4000x3000, irisR 40 -> half-side 650, side 1300.
        eq(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        4000, 3000, 0, 4000, 3000),
                new CropMath.Rect(1350, 850, 1300), "autocrop: centered R0");
        eq(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        3000, 4000, 90, 4000, 3000),
                new CropMath.Rect(850, 1350, 1300), "autocrop: centered R90");
        eq(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        4000, 3000, 180, 4000, 3000),
                new CropMath.Rect(1350, 850, 1300), "autocrop: centered R180");
        eq(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        3000, 4000, 270, 4000, 3000),
                new CropMath.Rect(850, 1350, 1300), "autocrop: centered R270");

        // Off-center detection pins the rotation DIRECTION: sensor
        // point (0.3, 0.4) must land at (0.3, 0.4) for R0,
        // (0.6, 0.3) for R90, (0.7, 0.6) for R180, (0.4, 0.7)
        // for R270. irisR 20 -> side 650.
        eq(AutoCrop.mapToJpeg(0.3f, 0.4f, 20f, 320, 240,
                        4000, 3000, 0, 4000, 3000),
                new CropMath.Rect(875, 875, 650), "autocrop: rotation R0");
        eq(AutoCrop.mapToJpeg(0.3f, 0.4f, 20f, 320, 240,
                        3000, 4000, 90, 4000, 3000),
                new CropMath.Rect(1475, 875, 650), "autocrop: rotation R90");
        eq(AutoCrop.mapToJpeg(0.3f, 0.4f, 20f, 320, 240,
                        4000, 3000, 180, 4000, 3000),
                new CropMath.Rect(2475, 1475, 650), "autocrop: rotation R180");
        eq(AutoCrop.mapToJpeg(0.3f, 0.4f, 20f, 320, 240,
                        3000, 4000, 270, 4000, 3000),
                new CropMath.Rect(875, 2475, 650), "autocrop: rotation R270");

        // Rotation normalization: 360 -> 0, -90 -> 270.
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        4000, 3000, 360, 4000, 3000) != null,
                "autocrop: rotation 360 normalizes");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        3000, 4000, -90, 4000, 3000) != null,
                "autocrop: rotation -90 normalizes");

        // Size pairs: 16:9 analysis frame vs 4:3 still — the
        // radius scale takes the LARGER axis so the iris is
        // covered on both. Frame 320x180, still 4032x3024,
        // irisR 30 -> side 1310 (from the height axis).
        eq(AutoCrop.mapToJpeg(0.5f, 0.5f, 30f, 320, 180,
                        4032, 3024, 0, 4032, 3024),
                new CropMath.Rect(1361, 857, 1310), "autocrop: aspect mismatch uses max scale");
        // Small frame, rotated: 160x120 -> 1500x2000 at R270.
        eq(AutoCrop.mapToJpeg(0.5f, 0.5f, 25f, 160, 120,
                        1500, 2000, 270, 2000, 1500),
                new CropMath.Rect(344, 594, 813), "autocrop: small frame R270");

        // Clamping: iris near each edge and corner. The square
        // stays inside AND still contains the pupil center.
        // irisR 60 -> side 1950 on a 4000x3000 JPEG.
        CropMath.Rect r;
        r = AutoCrop.mapToJpeg(0.16f, 0.5f, 60f, 320, 240, 4000, 3000, 0, 4000, 3000);
        eq(r, new CropMath.Rect(0, 525, 1950), "autocrop: clamp left edge");
        check(contains(r, 640, 1500), "autocrop: left edge keeps center");
        r = AutoCrop.mapToJpeg(0.84f, 0.5f, 60f, 320, 240, 4000, 3000, 0, 4000, 3000);
        eq(r, new CropMath.Rect(2050, 525, 1950), "autocrop: clamp right edge");
        check(contains(r, 3360, 1500), "autocrop: right edge keeps center");
        r = AutoCrop.mapToJpeg(0.5f, 0.16f, 60f, 320, 240, 4000, 3000, 0, 4000, 3000);
        eq(r, new CropMath.Rect(1025, 0, 1950), "autocrop: clamp top edge");
        check(contains(r, 2000, 480), "autocrop: top edge keeps center");
        r = AutoCrop.mapToJpeg(0.5f, 0.84f, 60f, 320, 240, 4000, 3000, 0, 4000, 3000);
        eq(r, new CropMath.Rect(1025, 1050, 1950), "autocrop: clamp bottom edge");
        check(contains(r, 2000, 2520), "autocrop: bottom edge keeps center");
        r = AutoCrop.mapToJpeg(0.16f, 0.16f, 60f, 320, 240, 4000, 3000, 0, 4000, 3000);
        eq(r, new CropMath.Rect(0, 0, 1950), "autocrop: clamp corner TL");
        check(contains(r, 640, 480), "autocrop: corner TL keeps center");
        r = AutoCrop.mapToJpeg(0.84f, 0.84f, 60f, 320, 240, 4000, 3000, 0, 4000, 3000);
        eq(r, new CropMath.Rect(2050, 1050, 1950), "autocrop: clamp corner BR");
        check(contains(r, 3360, 2520), "autocrop: corner BR keeps center");
        // Rotated corner: (0.16, 0.16) at R90 lands at (0.84, 0.16).
        r = AutoCrop.mapToJpeg(0.16f, 0.16f, 60f, 320, 240, 3000, 4000, 90, 4000, 3000);
        eq(r, new CropMath.Rect(1050, 0, 1950), "autocrop: clamp rotated corner");
        check(contains(r, 2520, 640), "autocrop: rotated corner keeps center");

        // Huge iris: the square shrinks to the short side and
        // still contains the center.
        r = AutoCrop.mapToJpeg(0.5f, 0.5f, 120f, 320, 240, 4000, 3000, 0, 4000, 3000);
        eq(r, new CropMath.Rect(50, 0, 3000), "autocrop: huge iris shrinks to frame");
        check(contains(r, 2000, 1500), "autocrop: huge iris keeps center");

        // Fallbacks: every insane input refuses (null), so the
        // caller falls back to the manual crop screen.
        check(AutoCrop.mapToJpeg(Float.NaN, 0.5f, 40f, 320, 240,
                        4000, 3000, 0, 4000, 3000) == null, "autocrop: NaN cx refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, Float.NaN, 320, 240,
                        4000, 3000, 0, 4000, 3000) == null, "autocrop: NaN radius refuses");
        check(AutoCrop.mapToJpeg(-0.1f, 0.5f, 40f, 320, 240,
                        4000, 3000, 0, 4000, 3000) == null, "autocrop: cx < 0 refuses");
        check(AutoCrop.mapToJpeg(1.1f, 0.5f, 40f, 320, 240,
                        4000, 3000, 0, 4000, 3000) == null, "autocrop: cx > 1 refuses");
        check(AutoCrop.mapToJpeg(0.5f, 2f, 40f, 320, 240,
                        4000, 3000, 0, 4000, 3000) == null, "autocrop: cy > 1 refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 0f, 320, 240,
                        4000, 3000, 0, 4000, 3000) == null, "autocrop: zero radius refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, -5f, 320, 240,
                        4000, 3000, 0, 4000, 3000) == null, "autocrop: negative radius refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 0, 240,
                        4000, 3000, 0, 4000, 3000) == null, "autocrop: zero frame refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        4000, 0, 0, 4000, 3000) == null, "autocrop: zero JPEG refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        4000, 3000, 0, 0, 3000) == null, "autocrop: zero still refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        4000, 3000, 45, 4000, 3000) == null,
                "autocrop: non-90 rotation refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        4000, 3000, 30, 4000, 3000) == null,
                "autocrop: rotation 30 refuses");

        // Dimension disagreement refuses: the HAL stored
        // sensor-oriented pixels (EXIF-only rotation), so the
        // rotation assumption is false and no mapping is trusted.
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        4000, 3000, 90, 4000, 3000) == null,
                "autocrop: unrotated JPEG at R90 refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        3000, 4000, 0, 4000, 3000) == null,
                "autocrop: swapped JPEG at R0 refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        3000, 4000, 180, 4000, 3000) == null,
                "autocrop: swapped JPEG at R180 refuses");
        // Square stills cannot disambiguate and always pass.
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 40f, 320, 240,
                        3000, 3000, 90, 3000, 3000) != null,
                "autocrop: square still passes any rotation");

        // Minimum size: a 2px iris would crop below the floor.
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 2f, 320, 240,
                        4000, 3000, 0, 4000, 3000) == null, "autocrop: tiny iris refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 13f, 320, 240,
                        4000, 3000, 0, 4000, 3000) == null,
                "autocrop: just under minimum refuses");
        check(AutoCrop.mapToJpeg(0.5f, 0.5f, 14f, 320, 240,
                        4000, 3000, 0, 4000, 3000) != null,
                "autocrop: just over minimum passes");

        // End to end (logic level): the gate fires on the 4th
        // steady feed; the firing detection maps to a crop, so
        // the auto path submits with no crop screen.
        AutoCaptureGate g = new AutoCaptureGate();
        IrisDetector.Result fire = gateResult(true, true, 9f, 0.5f, 0.5f, 78f);
        AutoCaptureGate.State s = null;
        for (int i = 0; i < 4; i++) s = g.feed(fire, true, true);
        check(s == AutoCaptureGate.State.FIRED, "autocrop e2e: gate fires");
        eq(AutoCrop.mapToJpeg(fire.cx, fire.cy, fire.irisR, 320, 240,
                        3000, 4000, 90, 4000, 3000),
                new CropMath.Rect(233, 733, 2535), "autocrop e2e: firing detection crops");
        // Same fire, unrotated JPEG: no mapping, manual crop screen.
        check(AutoCrop.mapToJpeg(fire.cx, fire.cy, fire.irisR, 320, 240,
                        4000, 3000, 90, 4000, 3000) == null,
                "autocrop e2e: bad dims fall back to manual crop");
        // The gate latches: no second fire, no re-fire loop.
        check(g.feed(fire, true, true) == AutoCaptureGate.State.FIRED,
                "autocrop e2e: gate stays latched after fire");
    }

    private static boolean contains(CropMath.Rect r, int x, int y) {
        return r != null && x >= r.left && x < r.left + r.size
                && y >= r.top && y < r.top + r.size;
    }

    // ---------------------------------------------------------- rotation (v1.3)

    /** Reference clockwise rotations over a plain int[] image
     *  (pixel value = x*100 + y, unique per pixel). These are
     *  independent of CropMath: they rotate PIXELS, and the
     *  checks below prove CropMath's rect mapping selects the
     *  same pixels the rotation moves — pinning the direction
     *  (clockwise), not assuming it. */
    private static int[] rot90cw(int[] src, int w, int h) {
        int[] dst = new int[src.length]; // dims h x w
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = h - 1 - y;
                int dy = x;
                dst[dy * h + dx] = src[y * w + x];
            }
        }
        return dst;
    }

    private static int[] rot180(int[] src, int w, int h) {
        int[] dst = new int[src.length];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                dst[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x];
            }
        }
        return dst;
    }

    private static int[] rot270cw(int[] src, int w, int h) {
        int[] dst = new int[src.length]; // dims h x w
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int dx = y;
                int dy = w - 1 - x;
                dst[dy * h + dx] = src[y * w + x];
            }
        }
        return dst;
    }

    private static int[] rectPixels(int[] img, int w, CropMath.Rect r) {
        int[] px = new int[r.size * r.size];
        int i = 0;
        for (int y = r.top; y < r.top + r.size; y++) {
            for (int x = r.left; x < r.left + r.size; x++) {
                px[i++] = img[y * w + x];
            }
        }
        Arrays.sort(px);
        return px;
    }

    private static void rotation() {
        // Normalization: only multiples of 90 rotate.
        eq(0, CropMath.normalizeRotation(0), "rot: norm 0");
        eq(90, CropMath.normalizeRotation(90), "rot: norm 90");
        eq(180, CropMath.normalizeRotation(180), "rot: norm 180");
        eq(270, CropMath.normalizeRotation(270), "rot: norm 270");
        eq(0, CropMath.normalizeRotation(360), "rot: norm 360 -> 0");
        eq(90, CropMath.normalizeRotation(450), "rot: norm 450 -> 90");
        eq(270, CropMath.normalizeRotation(-90), "rot: norm -90 -> 270");
        eq(0, CropMath.normalizeRotation(45), "rot: norm 45 -> 0");
        eq(0, CropMath.normalizeRotation(30), "rot: norm 30 -> 0");
        // Rotated dimensions: 90/270 swap the axes.
        eqArr(new int[]{1000, 800}, CropMath.rotatedDims(1000, 800, 0), "rot: dims 0");
        eqArr(new int[]{800, 1000}, CropMath.rotatedDims(1000, 800, 90), "rot: dims 90");
        eqArr(new int[]{1000, 800}, CropMath.rotatedDims(1000, 800, 180), "rot: dims 180");
        eqArr(new int[]{800, 1000}, CropMath.rotatedDims(1000, 800, 270), "rot: dims 270");
        eqArr(new int[]{500, 500}, CropMath.rotatedDims(500, 500, 90), "rot: dims square 90");

        // Direction-pinned rect values, hand-computed from the
        // pixel mapping (source 1000x800, rect (100,50,200)):
        // 90 CW: left = H-top-size = 550, top = left = 100.
        // 180: left = W-left-size = 700, top = H-top-size = 550.
        // 270 CW: left = top = 50, top = W-left-size = 700.
        CropMath.Rect src = new CropMath.Rect(100, 50, 200);
        eq(new CropMath.Rect(100, 50, 200),
                CropMath.rotateRect(src, 1000, 800, 0), "rot: rotateRect 0");
        eq(new CropMath.Rect(550, 100, 200),
                CropMath.rotateRect(src, 1000, 800, 90), "rot: rotateRect 90");
        eq(new CropMath.Rect(700, 550, 200),
                CropMath.rotateRect(src, 1000, 800, 180), "rot: rotateRect 180");
        eq(new CropMath.Rect(50, 700, 200),
                CropMath.rotateRect(src, 1000, 800, 270), "rot: rotateRect 270");
        // Inverse direction pins: the displayed rects above map
        // back to the source rect.
        eq(src, CropMath.unrotateRect(new CropMath.Rect(100, 50, 200), 1000, 800, 0),
                "rot: unrotateRect 0");
        eq(src, CropMath.unrotateRect(new CropMath.Rect(550, 100, 200), 1000, 800, 90),
                "rot: unrotateRect 90");
        eq(src, CropMath.unrotateRect(new CropMath.Rect(700, 550, 200), 1000, 800, 180),
                "rot: unrotateRect 180");
        eq(src, CropMath.unrotateRect(new CropMath.Rect(50, 700, 200), 1000, 800, 270),
                "rot: unrotateRect 270");
        // Rotation 0 is the identity in both directions (the
        // auto path's rotation: mapping must change nothing).
        eq(src, CropMath.rotateRect(src, 1000, 800, 0), "rot: rotate 0 identity");
        eq(src, CropMath.unrotateRect(src, 1000, 800, 0), "rot: unrotate 0 identity");

        // Round trips + containment for off-center, corner,
        // edge, and centered rects in landscape, portrait, and
        // square sources, at all four rotations.
        int[][] cases = {
                // {srcW, srcH, left, top, size}
                {1000, 800, 100, 50, 200},
                {1000, 800, 0, 0, 120},
                {1000, 800, 880, 680, 120},
                {1000, 800, 0, 600, 200},
                {1000, 800, 300, 300, 200},
                {800, 1000, 50, 100, 200},
                {800, 1000, 600, 880, 120},
                {600, 600, 100, 100, 300},
        };
        int[] rots = {0, 90, 180, 270};
        for (int[] c : cases) {
            int w = c[0];
            int h = c[1];
            CropMath.Rect r = new CropMath.Rect(c[2], c[3], c[4]);
            for (int rot : rots) {
                String tag = "rot: rt " + w + "x" + h + " " + r + " @" + rot;
                CropMath.Rect fwd = CropMath.rotateRect(r, w, h, rot);
                int[] dims = CropMath.rotatedDims(w, h, rot);
                check(fwd.left >= 0 && fwd.top >= 0
                        && fwd.left + fwd.size <= dims[0]
                        && fwd.top + fwd.size <= dims[1],
                        tag + " contained in displayed dims, got " + fwd);
                check(fwd.size == r.size, tag + " size preserved, got " + fwd);
                CropMath.Rect back = CropMath.unrotateRect(fwd, w, h, rot);
                eq(r, back, tag + " round trip");
                eq(fwd, CropMath.rotateRect(back, w, h, rot), tag + " reverse round trip");
            }
        }

        // Pixel-level direction proof: rotate a marked 10x6
        // image with the reference rotators, and prove the
        // mapped rect in the rotated image holds exactly the
        // source rect's pixels — corners pinned individually,
        // so a wrong direction cannot pass.
        int pw = 10;
        int ph = 6;
        int[] img = new int[pw * ph];
        for (int y = 0; y < ph; y++) {
            for (int x = 0; x < pw; x++) img[y * pw + x] = x * 100 + y;
        }
        CropMath.Rect pr = new CropMath.Rect(2, 1, 3);
        // 90 CW: displayed 6x10, rect (2,2,3). Corners: TL <- src
        // (2,3), TR <- src (2,1), BL <- src (4,3), BR <- src (4,1).
        int[] r90 = rot90cw(img, pw, ph);
        CropMath.Rect d90 = CropMath.rotateRect(pr, pw, ph, 90);
        eq(new CropMath.Rect(2, 2, 3), d90, "rot: pixel 90 mapped rect");
        check(r90[2 * 6 + 2] == 203, "rot: pixel 90 TL, got " + r90[2 * 6 + 2]);
        check(r90[2 * 6 + 4] == 201, "rot: pixel 90 TR, got " + r90[2 * 6 + 4]);
        check(r90[4 * 6 + 2] == 403, "rot: pixel 90 BL, got " + r90[4 * 6 + 2]);
        check(r90[4 * 6 + 4] == 401, "rot: pixel 90 BR, got " + r90[4 * 6 + 4]);
        check(Arrays.equals(rectPixels(img, pw, pr), rectPixels(r90, 6, d90)),
                "rot: pixel 90 rect holds the source pixels");
        // 180: displayed 10x6, rect (5,2,3). Corners: TL <- (4,3),
        // TR <- (2,3), BL <- (4,1), BR <- (2,1).
        int[] r180 = rot180(img, pw, ph);
        CropMath.Rect d180 = CropMath.rotateRect(pr, pw, ph, 180);
        eq(new CropMath.Rect(5, 2, 3), d180, "rot: pixel 180 mapped rect");
        check(r180[2 * 10 + 5] == 403, "rot: pixel 180 TL, got " + r180[2 * 10 + 5]);
        check(r180[2 * 10 + 7] == 203, "rot: pixel 180 TR, got " + r180[2 * 10 + 7]);
        check(r180[4 * 10 + 5] == 401, "rot: pixel 180 BL, got " + r180[4 * 10 + 5]);
        check(r180[4 * 10 + 7] == 201, "rot: pixel 180 BR, got " + r180[4 * 10 + 7]);
        check(Arrays.equals(rectPixels(img, pw, pr), rectPixels(r180, 10, d180)),
                "rot: pixel 180 rect holds the source pixels");
        // 270 CW: displayed 6x10, rect (1,5,3). Corners: TL <-
        // (4,1), TR <- (4,3), BL <- (2,1), BR <- (2,3).
        int[] r270 = rot270cw(img, pw, ph);
        CropMath.Rect d270 = CropMath.rotateRect(pr, pw, ph, 270);
        eq(new CropMath.Rect(1, 5, 3), d270, "rot: pixel 270 mapped rect");
        check(r270[5 * 6 + 1] == 401, "rot: pixel 270 TL, got " + r270[5 * 6 + 1]);
        check(r270[5 * 6 + 3] == 403, "rot: pixel 270 TR, got " + r270[5 * 6 + 3]);
        check(r270[7 * 6 + 1] == 201, "rot: pixel 270 BL, got " + r270[7 * 6 + 1]);
        check(r270[7 * 6 + 3] == 203, "rot: pixel 270 BR, got " + r270[7 * 6 + 3]);
        check(Arrays.equals(rectPixels(img, pw, pr), rectPixels(r270, 6, d270)),
                "rot: pixel 270 rect holds the source pixels");

        // Confirm-path end to end (math level): a 4000x3000
        // JPEG, rotated 90, on a 1080x1920 view. Preview decode
        // subsamples by 2 (2000x1500), rotated to 1500x2000;
        // fit-center scale 0.72, letterbox offset y=240. A
        // centered 720px view square (180,600,720) maps to
        // displayed-bitmap (250,500,1000), scales x2 to full
        // displayed (500,1000,2000), and unrotates to source
        // (1000,500,2000). Rotating that rect back must
        // reproduce the displayed rect exactly.
        CropMath.Rect viewCrop = new CropMath.Rect(180, 600, 720);
        CropMath.Rect bmpCrop = CropMath.viewToImage(viewCrop, 1080, 1920, 1500, 2000);
        eq(new CropMath.Rect(250, 500, 1000), bmpCrop, "rot: e2e view->displayed bitmap");
        int[] fullDisp = CropMath.rotatedDims(4000, 3000, 90);
        eqArr(new int[]{3000, 4000}, fullDisp, "rot: e2e full displayed dims");
        CropMath.Rect dispCrop = CropMath.clamp(new CropMath.Rect(
                Math.round(bmpCrop.left * 2f), Math.round(bmpCrop.top * 2f),
                Math.round(bmpCrop.size * 2f)), fullDisp[0], fullDisp[1]);
        eq(new CropMath.Rect(500, 1000, 2000), dispCrop, "rot: e2e scaled to full displayed");
        CropMath.Rect srcCrop = CropMath.unrotateRect(dispCrop, 4000, 3000, 90);
        eq(new CropMath.Rect(1000, 500, 2000), srcCrop, "rot: e2e unrotated to source");
        eq(dispCrop, CropMath.rotateRect(srcCrop, 4000, 3000, 90),
                "rot: e2e source rect rotates back to displayed rect");
        // Rotation 0 through the same path is the identity:
        // the auto path's mapping changes nothing.
        eq(dispCrop, CropMath.unrotateRect(dispCrop, 3000, 4000, 0),
                "rot: e2e rotation 0 is identity");
    }

    // ------------------------------------------------------------ rgb frame

    private static void rgbFrame() {
        // Rec.601 luma + (max-min)/max saturation, the same math
        // the detector consumes from YUV.
        IrisFrame red = RgbFrame.fromArgb(new int[]{0xFFFF0000}, 1, 1);
        check(red.gray[0] == 76, "rgb: red luma 76, got " + red.gray[0]);
        check(Math.abs(red.sat[0] - 1f) < 0.001f, "rgb: red sat 1.0");
        IrisFrame green = RgbFrame.fromArgb(new int[]{0xFF00FF00}, 1, 1);
        check(green.gray[0] == 150, "rgb: green luma 150, got " + green.gray[0]);
        check(Math.abs(green.sat[0] - 1f) < 0.001f, "rgb: green sat 1.0");
        IrisFrame blue = RgbFrame.fromArgb(new int[]{0xFF0000FF}, 1, 1);
        check(blue.gray[0] == 29, "rgb: blue luma 29, got " + blue.gray[0]);
        IrisFrame white = RgbFrame.fromArgb(new int[]{0xFFFFFFFF}, 1, 1);
        check(white.gray[0] == 255 && white.sat[0] == 0f, "rgb: white 255/0");
        IrisFrame black = RgbFrame.fromArgb(new int[]{0xFF000000}, 1, 1);
        check(black.gray[0] == 0 && black.sat[0] == 0f, "rgb: black 0/0");
        IrisFrame gray = RgbFrame.fromArgb(new int[]{0xFF808080}, 1, 1);
        check(gray.gray[0] == 128 && gray.sat[0] == 0f, "rgb: gray 128/0");
        // Alpha is ignored: a transparent red is still red.
        IrisFrame alpha = RgbFrame.fromArgb(new int[]{0x00FF0000}, 1, 1);
        check(alpha.gray[0] == 76, "rgb: alpha ignored");
        // No downsample at or below the working width.
        IrisFrame small = RgbFrame.fromArgb(new int[320 * 240], 320, 240);
        check(small.width == 320 && small.height == 240, "rgb: 320 wide kept");
        // Downsample: 640x480 -> 320x240, center-sampled
        // nearest-neighbor — dest (ox, oy) samples src
        // ((ox+0.5)*2, (oy+0.5)*2), so a marker at src
        // (201, 101) lands at dest (100, 50).
        int[] big = new int[640 * 480];
        Arrays.fill(big, 0xFF000000);
        big[101 * 640 + 201] = 0xFFFFFFFF; // src (201, 101)
        IrisFrame down = RgbFrame.fromArgb(big, 640, 480);
        check(down.width == 320 && down.height == 240, "rgb: 640x480 -> 320x240");
        check(down.gray[50 * 320 + 100] == 255, "rgb: marker lands at (100, 50)");
        check(down.gray[0] == 0, "rgb: background stays black");
        // Odd widths round to the working width, aspect kept.
        IrisFrame odd = RgbFrame.fromArgb(new int[321 * 200], 321, 200);
        check(odd.width == 320 && odd.height == 199,
                "rgb: 321x200 -> 320x199, got " + odd.width + "x" + odd.height);
        // Bad inputs fail loudly, never silently wrong.
        boolean threw = false;
        try { RgbFrame.fromArgb(null, 1, 1); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "rgb: null pixels rejected");
        threw = false;
        try { RgbFrame.fromArgb(new int[3], 2, 2); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "rgb: short array rejected");
        threw = false;
        try { RgbFrame.fromArgb(new int[0], 0, 0); } catch (IllegalArgumentException e) { threw = true; }
        check(threw, "rgb: zero dims rejected");
    }

    // -------------------------------------------------------- capture status

    private static void captureStatus() {
        eq("Hold still — capturing…", CaptureStatus.text(CaptureStatus.Phase.FIRING),
                "seq: FIRING text");
        eq("Hold still — capturing…", CaptureStatus.text(CaptureStatus.Phase.CAPTURING),
                "seq: CAPTURING text");
        eq("Photo saved — preparing…", CaptureStatus.text(CaptureStatus.Phase.SAVED),
                "seq: SAVED text");
        eq("Checking the framing…", CaptureStatus.text(CaptureStatus.Phase.VERIFYING),
                "seq: VERIFYING text");
        eq("Submitting…", CaptureStatus.text(CaptureStatus.Phase.SUBMITTING),
                "seq: SUBMITTING text");
        // No phase before SAVED may claim a capture: the v1.4
        // sequencing fix, pinned. "Capturing" is fine; "Captured"
        // before the still bytes exist is the bug.
        for (CaptureStatus.Phase p : CaptureStatus.Phase.values()) {
            String t = CaptureStatus.text(p);
            boolean beforeSave = p == CaptureStatus.Phase.FIRING
                    || p == CaptureStatus.Phase.CAPTURING;
            check(!beforeSave || !t.toLowerCase().contains("captured"),
                    "seq: " + p + " never claims captured");
            check(CaptureStatus.mayClaimCaptured(p) != beforeSave,
                    "seq: mayClaimCaptured(" + p + ")");
        }
        // Null phase fails safe: hold-still text, no capture claim.
        eq("Hold still — capturing…", CaptureStatus.text(null), "seq: null phase text");
        check(!CaptureStatus.mayClaimCaptured(null), "seq: null phase may not claim");
    }

    // ---------------------------------------------------------- crop verify

    /** Load the fixture JPEG as detection-frame pixels. Fails
     *  the harness loudly when the fixture is missing — this
     *  leg must never silently skip Adam's real photo. */
    private static int[] fixturePixels(int[] dims) throws Exception {
        File f = new File("fixtures/adam-autocrop-20261116.jpg");
        if (!f.exists()) f = new File("harness/fixtures/adam-autocrop-20261116.jpg");
        check(f.exists(), "verify: fixture present at fixtures/adam-autocrop-20261116.jpg");
        if (!f.exists()) return null;
        BufferedImage img = ImageIO.read(f);
        dims[0] = img.getWidth();
        dims[1] = img.getHeight();
        int[] argb = new int[dims[0] * dims[1]];
        img.getRGB(0, 0, dims[0], dims[1], argb, 0, dims[0]);
        return argb;
    }

    /** The fixture rescaled by s and pasted so the true pupil
     *  (0.37, 0.23 in the original) lands dead center — a
     *  geometrically-centered version of Adam's real eye. */
    private static int[] recentered(int[] src, int w, int h, float s, int bg) {
        int[] out = new int[w * h];
        Arrays.fill(out, bg);
        int nw = Math.round(w * s);
        int nh = Math.round(h * s);
        int ox = Math.round(w / 2f - 0.37f * w * s);
        int oy = Math.round(h / 2f - 0.23f * h * s);
        for (int y = 0; y < nh; y++) {
            int dy = oy + y;
            if (dy < 0 || dy >= h) continue;
            for (int x = 0; x < nw; x++) {
                int dx = ox + x;
                if (dx < 0 || dx >= w) continue;
                out[dy * w + dx] = src[(int) (y / s) * w + (int) (x / s)];
            }
        }
        return out;
    }

    private static void cropVerify() {
        // Decision boundaries on crafted traces (minDim 320).
        CropVerify.Verdict v = CropVerify.verify(null, 320);
        check(!v.pass && v.reason == CropVerify.Reason.NO_TRACE, "verify: null trace fails");
        v = CropVerify.verify(new IrisDetector.CenterTrace(123f, 1f, 0.01f), 0);
        check(!v.pass && v.reason == CropVerify.Reason.NO_TRACE, "verify: bad minDim fails");
        v = CropVerify.verify(new IrisDetector.CenterTrace(123f, 0.54f, 0.01f), 320);
        check(!v.pass && v.reason == CropVerify.Reason.EDGE_WEAK,
                "verify: validFrac 0.54 fails");
        v = CropVerify.verify(new IrisDetector.CenterTrace(123f, 0.55f, 0.01f), 320);
        check(v.pass && v.reason == CropVerify.Reason.OK, "verify: validFrac 0.55 passes");
        v = CropVerify.verify(new IrisDetector.CenterTrace(57f, 1f, 0.01f), 320);
        check(!v.pass && v.reason == CropVerify.Reason.BAD_RADIUS,
                "verify: irisFrac 0.178 fails");
        v = CropVerify.verify(new IrisDetector.CenterTrace(58f, 1f, 0.01f), 320);
        check(v.pass, "verify: irisFrac 0.181 passes");
        v = CropVerify.verify(new IrisDetector.CenterTrace(160f, 1f, 0.01f), 320);
        check(v.pass, "verify: irisFrac 0.50 passes");
        v = CropVerify.verify(new IrisDetector.CenterTrace(161f, 1f, 0.01f), 320);
        check(!v.pass && v.reason == CropVerify.Reason.BAD_RADIUS,
                "verify: irisFrac 0.503 fails");
        // Weak trace beats a bad radius: the edge check runs first.
        v = CropVerify.verify(new IrisDetector.CenterTrace(30f, 0.2f, 0.5f), 320);
        check(!v.pass && v.reason == CropVerify.Reason.EDGE_WEAK,
                "verify: weak trace reported before bad radius");
        // Measurements ride the verdict.
        v = CropVerify.verify(new IrisDetector.CenterTrace(123f, 0.8f, 0.33f), 320);
        check(v.pass && Math.abs(v.validFrac - 0.8f) < 0.001f
                && Math.abs(v.irisFrac - 123f / 320) < 0.001f
                && Math.abs(v.spread - 0.33f) < 0.001f,
                "verify: verdict carries measurements");
        // The center trace on a centered synthetic: tight, full,
        // and the radius it reports is the iris radius.
        IrisFrame centered = syntheticEye(320, 320, 160, 160, 34, 123, 90, 0.55f, 14, 0);
        IrisDetector.CenterTrace t = IrisDetector.traceFromCenter(centered);
        check(Math.abs(t.irisR - 123f) <= 5f, "verify: trace radius ~123, got " + t.irisR);
        check(t.validFrac >= 0.95f, "verify: trace validFrac >= 0.95, got " + t.validFrac);
        check(t.spread <= 0.05f, "verify: trace spread <= 0.05, got " + t.spread);
        check(CropVerify.verify(t, 320).pass, "verify: centered synthetic passes");
        // Dark iris, centered: passes — the trace does not need
        // a bright pupil blob.
        IrisFrame dark = syntheticEye(320, 320, 160, 160, 34, 123, 55, 0.30f, 14, 0);
        check(CropVerify.verify(IrisDetector.traceFromCenter(dark), 320).pass,
                "verify: dark centered synthetic passes");
        // Eyelid over the top (the harness builder covers 45%
        // above center): passes — lid coverage alone must not
        // reject a centered eye.
        IrisFrame lid = syntheticEye(320, 320, 160, 160, 34, 123, 90, 0.55f, 14, 0, true);
        IrisDetector.CenterTrace lt = IrisDetector.traceFromCenter(lid);
        System.out.println("  [verify] eyelid: validFrac=" + lt.validFrac
                + " irisFrac=" + (lt.irisR / 320));
        check(CropVerify.verify(lt, 320).pass, "verify: eyelid centered synthetic passes");
        // Displaced 0.32 vertically (the fixture's own offset):
        // rejected, edge weak.
        IrisFrame displaced = syntheticEye(320, 320, 160, 58, 34, 123, 90, 0.55f, 14, 0);
        v = CropVerify.verify(IrisDetector.traceFromCenter(displaced), 320);
        check(!v.pass && v.reason == CropVerify.Reason.EDGE_WEAK,
                "verify: 0.32-off-center synthetic rejected");
        // Too tight (iris diameter exceeds the crop): rejected.
        IrisFrame tight = syntheticEye(320, 320, 160, 160, 45, 175, 90, 0.55f, 14, 0);
        v = CropVerify.verify(IrisDetector.traceFromCenter(tight), 320);
        check(!v.pass, "verify: too-tight synthetic rejected");
        // Too loose (iris a postage stamp): rejected by the
        // radius band — the documented rule for too-loose.
        IrisFrame loose = syntheticEye(320, 320, 160, 160, 15, 55, 90, 0.55f, 14, 0);
        v = CropVerify.verify(IrisDetector.traceFromCenter(loose), 320);
        check(!v.pass && v.reason == CropVerify.Reason.BAD_RADIUS,
                "verify: too-loose synthetic rejected by radius band");
        // No eye at all: rejected.
        int[] flat = new int[320 * 320];
        float[] flatSat = new float[320 * 320];
        Arrays.fill(flat, 170);
        Arrays.fill(flatSat, 0.2f);
        v = CropVerify.verify(
                IrisDetector.traceFromCenter(new IrisFrame(320, 320, flat, flatSat)), 320);
        check(!v.pass && v.reason == CropVerify.Reason.EDGE_WEAK,
                "verify: flat frame rejected");

        // --- Adam's real photo: the fixture MUST be rejected,
        // and re-centered versions of it MUST be accepted.
        try {
            int[] dims = new int[2];
            int[] argb = fixturePixels(dims);
            if (argb == null) return;
            check(dims[0] == 1024 && dims[1] == 1024,
                    "verify: fixture is 1024x1024, got " + dims[0] + "x" + dims[1]);
            IrisFrame fixture = RgbFrame.fromArgb(argb, dims[0], dims[1]);
            IrisDetector.CenterTrace ft = IrisDetector.traceFromCenter(fixture);
            System.out.println("  [verify] fixture: validFrac=" + ft.validFrac
                    + " irisFrac=" + (ft.irisR / fixture.minDim())
                    + " spread=" + ft.spread);
            v = CropVerify.verify(ft, fixture.minDim());
            check(!v.pass && v.reason == CropVerify.Reason.EDGE_WEAK,
                    "verify: fixture REJECTED (was submitted by v1.2/v1.3)");
            check(ft.validFrac >= 0.30f && ft.validFrac <= 0.45f,
                    "verify: fixture validFrac in [0.30, 0.45], got " + ft.validFrac);
            check(ft.irisR / fixture.minDim() >= 0.15f
                    && ft.irisR / fixture.minDim() <= 0.30f,
                    "verify: fixture irisFrac in [0.15, 0.30]");
            // The same eye, geometrically centered, at three
            // scales: all accepted. (The pupil-blob rule failed
            // this leg — no-pupil, correct, wrong-blob across
            // the three scales — which is why verification uses
            // the center trace.)
            for (float s : new float[]{0.55f, 0.65f, 0.75f}) {
                IrisFrame rc = RgbFrame.fromArgb(
                        recentered(argb, dims[0], dims[1], s, 0xFFD8DEE2),
                        dims[0], dims[1]);
                IrisDetector.CenterTrace rt = IrisDetector.traceFromCenter(rc);
                System.out.println("  [verify] recentered s=" + s
                        + ": validFrac=" + rt.validFrac
                        + " irisFrac=" + (rt.irisR / rc.minDim()));
                check(CropVerify.verify(rt, rc.minDim()).pass,
                        "verify: recentered fixture s=" + s + " ACCEPTED");
            }
        } catch (Exception e) {
            check(false, "verify: fixture leg threw: " + e);
        }
    }

    // ------------------------------------------------------ crop diagnostics

    private static CropDiagnostics.Record sampleRecord() {
        return new CropDiagnostics.Record(
                "2", "Macro", 640, 480, 4000, 3000, 90,
                0.42f, 0.18f, 95f, 320, 240,
                100, 200, 1200, 3000, 4000,
                CropDiagnostics.Outcome.VERIFY_FAILED,
                CropVerify.Reason.EDGE_WEAK,
                0.36f, 0.21f, 0.60f, 118L);
    }

    private static void cropDiagnostics() {
        CropDiagnostics.Record r = sampleRecord();
        String enc = CropDiagnostics.encode(r);
        check(enc != null && !enc.isEmpty(), "diag: encodes non-empty");
        check(!enc.contains("data:") && !enc.contains("base64")
                && enc.length() < 400,
                "diag: encoded record is numbers + labels only (" + enc.length() + " chars)");
        CropDiagnostics.Record back = CropDiagnostics.decode(enc);
        check(back != null, "diag: round-trips");
        if (back != null) {
            check("2".equals(back.cameraId) && "Macro".equals(back.cameraLabel),
                    "diag: camera fields round-trip");
            check(back.analysisW == 640 && back.analysisH == 480
                    && back.stillW == 4000 && back.stillH == 3000
                    && back.jpegOrientation == 90, "diag: size fields round-trip");
            check(back.fireCx == 0.42f && back.fireCy == 0.18f && back.fireIrisR == 95f
                    && back.fireFrameW == 320 && back.fireFrameH == 240,
                    "diag: firing fields round-trip");
            check(back.rectLeft == 100 && back.rectTop == 200 && back.rectSize == 1200
                    && back.jpegW == 3000 && back.jpegH == 4000,
                    "diag: rect/jpeg fields round-trip");
            check(back.outcome == CropDiagnostics.Outcome.VERIFY_FAILED
                    && back.verifyReason == CropVerify.Reason.EDGE_WEAK,
                    "diag: outcome/reason round-trip");
            check(back.measuredValidFrac == 0.36f && back.measuredIrisFrac == 0.21f
                    && back.measuredSpread == 0.60f && back.verifyMs == 118L,
                    "diag: measurement fields round-trip");
        }
        // Labels containing the separators cannot corrupt the
        // record: ";" becomes "," on encode, and decode splits
        // each pair on the FIRST "=", so "=" inside a value is
        // preserved.
        CropDiagnostics.Record dirty = new CropDiagnostics.Record(
                "0", "We;ird=Label", 1, 1, 1, 1, 0,
                0f, 0f, 0f, 1, 1, 0, 0, 0, 1, 1,
                CropDiagnostics.Outcome.SUBMITTED, CropVerify.Reason.OK,
                Float.NaN, Float.NaN, Float.NaN, -1);
        CropDiagnostics.Record clean = CropDiagnostics.decode(CropDiagnostics.encode(dirty));
        check(clean != null && "We,ird=Label".equals(clean.cameraLabel),
                "diag: separators safe in labels, got "
                        + (clean == null ? "null" : clean.cameraLabel));
        // Garbage decodes to null, never a half-record; one bad
        // VALUE degrades to its sentinel instead of sinking the
        // whole record (the documented partial-record rule).
        check(CropDiagnostics.decode(null) == null, "diag: null -> null");
        check(CropDiagnostics.decode("") == null, "diag: empty -> null");
        check(CropDiagnostics.decode("not a record") == null, "diag: garbage -> null");
        check(CropDiagnostics.decode("cam=0;") == null, "diag: no outcome -> null");
        check(CropDiagnostics.decode("out=BOGUS;") == null, "diag: bad outcome -> null");
        CropDiagnostics.Record partial =
                CropDiagnostics.decode("out=SUBMITTED;mv=abc;");
        check(partial != null && partial.outcome == CropDiagnostics.Outcome.SUBMITTED
                && Float.isNaN(partial.measuredValidFrac),
                "diag: bad number degrades to sentinel, record survives");
        // Unknown keys are ignored: forward-compatible.
        check(CropDiagnostics.decode(enc + ";zz=1;") != null,
                "diag: unknown keys ignored");
        // Wording: plain words, measured facts, no jargon.
        String summary = CropDiagnostics.fallbackSummary(r);
        check(summary.contains("cut off or off-center") && summary.contains("36%"),
                "diag: EDGE_WEAK summary names the measured trace: " + summary);
        check(summary.contains("frame the iris yourself"),
                "diag: summary tells the user what to do");
        check(CropDiagnostics.fallbackSummary(null).contains("frame it yourself"),
                "diag: null record summary stays honest");
        CropDiagnostics.Record big = new CropDiagnostics.Record(
                "0", "Main", 1, 1, 1, 1, 0, 0f, 0f, 0f, 1, 1, 0, 0, 0, 1, 1,
                CropDiagnostics.Outcome.VERIFY_FAILED, CropVerify.Reason.BAD_RADIUS,
                0.9f, 0.62f, 0.1f, 50L);
        check(CropDiagnostics.fallbackSummary(big).contains("cut off at the edges"),
                "diag: too-big radius summary names the cut");
        CropDiagnostics.Record small = new CropDiagnostics.Record(
                "0", "Main", 1, 1, 1, 1, 0, 0f, 0f, 0f, 1, 1, 0, 0, 0, 1, 1,
                CropDiagnostics.Outcome.VERIFY_FAILED, CropVerify.Reason.BAD_RADIUS,
                0.9f, 0.10f, 0.1f, 50L);
        check(CropDiagnostics.fallbackSummary(small).contains("only 10%"),
                "diag: too-small radius summary names the size");
        CropDiagnostics.Record mapFail = new CropDiagnostics.Record(
                "0", "Main", 1, 1, 1, 1, 0, 0f, 0f, 0f, 1, 1, -1, -1, -1, 1, 1,
                CropDiagnostics.Outcome.MAPPING_FAILED, null,
                Float.NaN, Float.NaN, Float.NaN, -1);
        check(CropDiagnostics.fallbackSummary(mapFail).contains("couldn't map the detection"),
                "diag: mapping summary names the mapping");
        CropDiagnostics.Record encFail = new CropDiagnostics.Record(
                "0", "Main", 1, 1, 1, 1, 0, 0f, 0f, 0f, 1, 1, 0, 0, 0, 1, 1,
                CropDiagnostics.Outcome.ENCODE_FAILED, null,
                Float.NaN, Float.NaN, Float.NaN, -1);
        check(CropDiagnostics.fallbackSummary(encFail).contains("couldn't cut"),
                "diag: encode summary names the cut");
        // Camera-screen line: null record -> empty string (the
        // activity hides the line until the first attempt).
        check("".equals(CropDiagnostics.lastCheckLine(null)),
                "diag: null last-check line is empty");
        check(CropDiagnostics.lastCheckLine(r).startsWith("Last auto-crop check: failed"),
                "diag: failed last-check line");
        check(CropDiagnostics.lastCheckLine(mapFail).contains("couldn't map"),
                "diag: mapping last-check line");
        CropDiagnostics.Record ok = new CropDiagnostics.Record(
                "0", "Main", 1, 1, 1, 1, 0, 0f, 0f, 0f, 1, 1, 0, 0, 0, 1, 1,
                CropDiagnostics.Outcome.SUBMITTED, CropVerify.Reason.OK,
                0.92f, 0.38f, 0.05f, 95L);
        String okLine = CropDiagnostics.lastCheckLine(ok);
        check(okLine.contains("passed") && okLine.contains("92%") && okLine.contains("95ms"),
                "diag: passed last-check line: " + okLine);
        // Detail text carries the full record, still numbers only.
        String detail = CropDiagnostics.detailText(r);
        check(detail.contains("Macro") && detail.contains("VERIFY_FAILED")
                && detail.contains("EDGE_WEAK") && detail.contains("118ms")
                && detail.contains("640×480") && detail.contains("4000×3000"),
                "diag: detail text carries the record");
        check(CropDiagnostics.detailText(null).contains("No auto-crop"),
                "diag: null detail text");
    }

    // ------------------------------------------------------------ deep link

    private static final String HOME = "https://www.eyeknow.app";

    private static void deepLink() {
        // The trust rule on components.
        check(TrustedSite.isTrusted("https", "eyeknow.app", -1), "link: apex trusted");
        check(TrustedSite.isTrusted("https", "www.eyeknow.app", -1), "link: www trusted");
        check(TrustedSite.isTrusted("https", "eyeknow.app", 443), "link: explicit 443 trusted");
        check(!TrustedSite.isTrusted("http", "www.eyeknow.app", -1), "link: http rejected");
        check(!TrustedSite.isTrusted("https", "evil.com", -1), "link: wrong host rejected");
        check(!TrustedSite.isTrusted("https", "eyeknow.app.evil.com", -1),
                "link: suffix host rejected");
        check(!TrustedSite.isTrusted("https", "www.eyeknow.app", 8443),
                "link: wrong port rejected");
        check(!TrustedSite.isTrusted(null, "eyeknow.app", -1), "link: null scheme rejected");
        check(!TrustedSite.isTrusted("https", null, -1), "link: null host rejected");
        check(!TrustedSite.isTrusted("HTTPS", "www.eyeknow.app", -1),
                "link: scheme match is case-sensitive (parity with the old rule)");
        check(!TrustedSite.isTrustedUri(null), "link: null URI rejected");
        try {
            check(TrustedSite.isTrustedUri(new URI("https://www.eyeknow.app/?ref=x")),
                    "link: trusted URI parses trusted");
            check(!TrustedSite.isTrustedUri(new URI("https://eyeknow.app@evil.com/")),
                    "link: userinfo host confusion rejected");
        } catch (Exception e) {
            check(false, "link: URI leg threw: " + e);
        }
        // URL selection: a trusted link loads VERBATIM — the
        // ?ref= code survives byte-for-byte.
        String ref = "https://www.eyeknow.app/?ref=7f365261";
        eq(ref, TrustedSite.startUrl(ref, HOME), "link: referral URL passes through untouched");
        String refParams = "https://www.eyeknow.app/?ref=7f365261&scan=abc&utm_source=x";
        eq(refParams, TrustedSite.startUrl(refParams, HOME),
                "link: referral + extra params untouched");
        String apex = "https://eyeknow.app/affiliates?ref=abc123";
        eq(apex, TrustedSite.startUrl(apex, HOME), "link: apex path URL untouched");
        String port443 = "https://www.eyeknow.app:443/?ref=x";
        eq(port443, TrustedSite.startUrl(port443, HOME), "link: explicit 443 untouched");
        // Absent, malformed, or untrusted -> HOME_URL, never the
        // link, never passed to the WebView.
        eq(HOME, TrustedSite.startUrl(null, HOME), "link: no data -> home");
        eq(HOME, TrustedSite.startUrl("", HOME), "link: empty -> home");
        eq(HOME, TrustedSite.startUrl("http://www.eyeknow.app/?ref=x", HOME),
                "link: http -> home");
        eq(HOME, TrustedSite.startUrl("https://evil.com/?ref=x", HOME),
                "link: wrong host -> home");
        eq(HOME, TrustedSite.startUrl("https://www.eyeknow.app:8443/", HOME),
                "link: wrong port -> home");
        eq(HOME, TrustedSite.startUrl("::::", HOME), "link: malformed -> home");
        eq(HOME, TrustedSite.startUrl("https://eyeknow.app@evil.com/", HOME),
                "link: host confusion -> home");
        eq(HOME, TrustedSite.startUrl("/relative/path?ref=x", HOME),
                "link: relative -> home");
        eq(HOME, TrustedSite.startUrl("javascript:alert(1)", HOME),
                "link: javascript scheme -> home");
    }

    // ---- v1.5: referral stamp in the APK Signing Block ----

    private static void stamp() {
        // The validation rule, mirroring lib/affiliates-client.ts
        // isValidRefValue exactly (same vectors run through the
        // TypeScript rule in the web tests).
        check(ApkStamp.isValidRefValue("abc"), "stamp: 3-char code valid");
        check(ApkStamp.isValidRefValue("7f365261"), "stamp: auto code valid");
        check(ApkStamp.isValidRefValue("a--"), "stamp: hyphens + alnum valid");
        check(ApkStamp.isValidRefValue("ab-"), "stamp: trailing hyphen valid");
        check(ApkStamp.isValidRefValue("---a"), "stamp: leading hyphens valid");
        check(ApkStamp.isValidRefValue("kyle-readings-99"), "stamp: slug valid");
        check(ApkStamp.isValidRefValue("abcdefghij-klmnop-123456789012"),
                "stamp: 30-char code valid");
        check(!ApkStamp.isValidRefValue(null), "stamp: null invalid");
        check(!ApkStamp.isValidRefValue(""), "stamp: empty invalid");
        check(!ApkStamp.isValidRefValue("ab"), "stamp: 2 chars invalid");
        check(!ApkStamp.isValidRefValue("abcdefghij-klmnop-1234567890123"),
                "stamp: 31 chars invalid");
        check(!ApkStamp.isValidRefValue("ABC"), "stamp: uppercase invalid");
        check(!ApkStamp.isValidRefValue("a_b"), "stamp: underscore invalid");
        check(!ApkStamp.isValidRefValue("a b"), "stamp: space invalid");
        check(!ApkStamp.isValidRefValue("---"), "stamp: hyphens-only invalid");
        check(!ApkStamp.isValidRefValue("a.b"), "stamp: dot invalid");
        check(!ApkStamp.isValidRefValue("abc\n"), "stamp: newline invalid");

        // Stamped home URL construction.
        eq(HOME + "?ref=7f365261", ApkStamp.stampedHomeUrl(HOME, "7f365261"),
                "stamp: stamped URL attaches ?ref=");
        eq(HOME, ApkStamp.stampedHomeUrl(HOME, null), "stamp: null code -> home unchanged");

        // Synthetic signing blocks, built by the test-only
        // writer below (the Java-written half of the shared
        // vectors — the same bytes are written to fixtures/
        // for the TypeScript reader to read back).
        byte[] v2pair = pair(0x7109871a, new byte[]{1, 2, 3, 4, 5});
        byte[] stampPair = stampPair("7f365261");
        byte[] slugPair = stampPair("kyle-readings-99");
        byte[] unknownPair = pair(0x12345678, new byte[]{9, 9, 9});

        eq("7f365261", ApkStamp.readStamp(synthApk(v2pair, stampPair)),
                "stamp: reads stamp after a v2 pair");
        eq("7f365261", ApkStamp.readStamp(synthApk(stampPair, v2pair)),
                "stamp: reads stamp before a v2 pair");
        eq("kyle-readings-99", ApkStamp.readStamp(synthApk(unknownPair, slugPair)),
                "stamp: unknown IDs skipped, slug read");
        eq(null, ApkStamp.readStamp(synthApk(v2pair, unknownPair)),
                "stamp: no stamp pair -> null");
        eq(null, ApkStamp.readStamp(synthApk()),
                "stamp: empty signing block -> null");
        // First VALID stamp wins: an invalid stamp pair ahead
        // of a valid one does not shadow it.
        eq("7f365261", ApkStamp.readStamp(synthApk(stampPairRaw("BAD CODE!"), stampPair)),
                "stamp: invalid stamp pair skipped, valid one read");
        eq("7f365261", ApkStamp.readStamp(synthApk(stampPair, slugPair)),
                "stamp: first valid stamp wins on a double stamp");
        // Wrong magic under the stamp ID is not a stamp.
        eq(null, ApkStamp.readStamp(synthApk(pair(ApkStamp.STAMP_ID, "NOPE:abc".getBytes()))),
                "stamp: stamp ID without magic -> null");
        // A stamp value that is only the magic, or a code that
        // fails the rule, is not a stamp.
        eq(null, ApkStamp.readStamp(synthApk(stampPairRaw(""))),
                "stamp: magic-only value -> null");
        eq(null, ApkStamp.readStamp(synthApk(stampPairRaw("ab"))),
                "stamp: too-short stamped code -> null");
        eq(null, ApkStamp.readStamp(synthApk(stampPairRaw("ABCDEF"))),
                "stamp: uppercase stamped code -> null");

        // Structural failures all fail safe to null.
        eq(null, ApkStamp.readStamp(null), "stamp: null bytes -> null");
        eq(null, ApkStamp.readStamp(new byte[0]), "stamp: empty file -> null");
        eq(null, ApkStamp.readStamp(new byte[]{1, 2, 3}), "stamp: tiny file -> null");
        eq(null, ApkStamp.readStamp(noSigningBlock()),
                "stamp: APK with no signing block -> null");
        eq(null, ApkStamp.readStamp(badMagicApk(v2pair, stampPair)),
                "stamp: bad block magic -> null");
        eq(null, ApkStamp.readStamp(sizeMismatchApk(v2pair, stampPair)),
                "stamp: size fields disagree -> null");
        eq(null, ApkStamp.readStamp(truncatedPairApk(stampPair)),
                "stamp: truncated pair -> null");
        eq(null, ApkStamp.readStamp(zip64Apk(stampPair)),
                "stamp: ZIP64 sentinel -> null");
        // An EOCD comment is legal and must not break the scan:
        // the stamp is still found.
        eq("7f365261", ApkStamp.readStamp(eocdCommentApk(v2pair, stampPair, "hello")),
                "stamp: EOCD comment does not hide the stamp");
        // PK signature bytes inside the entries must not fool
        // the backwards EOCD scan.
        eq("7f365261", ApkStamp.readStamp(fakeSigEntriesApk(v2pair, stampPair)),
                "stamp: fake EOCD sig in entries ignored");

        // Real stamped APK bytes: the v1.4 APK stamped by the
        // reference stamper (proven apksigner-clean), plus the
        // unstamped original must read null.
        try {
            eq("7f365261", ApkStamp.readStampFromFile(
                            "fixtures/stamped-real-v14-7f365261.apk"),
                    "stamp: real stamped APK reads the code");
            // The TypeScript stamper's output over the real v1.5
            // bytes — the shared-vector half in this direction.
            eq("7f365261", ApkStamp.readStampFromFile(
                            "fixtures/stamped-ts-v15-7f365261.apk"),
                    "stamp: TS-stamped v1.5 APK reads the code");
            eq(null, ApkStamp.readStampFromFile("fixtures/unstamped-v14.apk"),
                    "stamp: real unstamped APK reads null");
            eq(null, ApkStamp.readStampFromFile("fixtures/does-not-exist.apk"),
                    "stamp: missing file -> null");
            eq(null, ApkStamp.readStampFromFile(null), "stamp: null path -> null");
        } catch (Exception e) {
            check(false, "stamp: fixture leg threw: " + e);
        }

        // Write the synthetic fixtures the TypeScript reader
        // cross-check reads (deterministic bytes).
        try {
            writeFile("fixtures/synth-stamped-7f365261.apk",
                    synthApk(v2pair, stampPair));
            writeFile("fixtures/synth-stamped-slug.apk",
                    synthApk(unknownPair, slugPair, v2pair));
            writeFile("fixtures/synth-unstamped.apk", synthApk(v2pair, unknownPair));
        } catch (Exception e) {
            check(false, "stamp: fixture write threw: " + e);
        }
    }

    /** One signing-block ID-value pair: uint64 length (ID +
     *  value), uint32 ID, value bytes. All little-endian. */
    private static byte[] pair(int id, byte[] value) {
        byte[] out = new byte[8 + 4 + value.length];
        put64(out, 0, 4L + value.length);
        put32(out, 8, id);
        System.arraycopy(value, 0, out, 12, value.length);
        return out;
    }

    /** A stamp pair carrying a valid code. */
    private static byte[] stampPair(String code) {
        return stampPairRaw(code);
    }

    /** A stamp pair carrying a raw value suffix (valid or
     *  not — the reader decides). */
    private static byte[] stampPairRaw(String suffix) {
        String v = ApkStamp.VALUE_MAGIC + suffix;
        return pair(ApkStamp.STAMP_ID, v.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    /** A minimal APK-shaped byte array: fake ZIP entries, a
     *  signing block holding the given pairs in order, a fake
     *  central directory, and an EOCD pointing at it. */
    private static byte[] synthApk(byte[]... pairs) {
        byte[] entries = "PK\003\004FAKE-ENTRY-PAYLOAD".getBytes();
        byte[] cd = "PK\001\002FAKE-CENTRAL-DIRECTORY".getBytes();
        int pairsLen = 0;
        for (byte[] p : pairs) pairsLen += p.length;
        long blockSize = pairsLen + 24L; // pairs + trailing size + magic
        byte[] out = new byte[entries.length + 8 + pairsLen + 24 + cd.length + 22];
        int o = 0;
        System.arraycopy(entries, 0, out, o, entries.length);
        o += entries.length;
        put64(out, o, blockSize);
        o += 8;
        for (byte[] p : pairs) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        put64(out, o, blockSize);
        o += 8;
        byte[] magic = "APK Sig Block 42".getBytes();
        System.arraycopy(magic, 0, out, o, magic.length);
        o += magic.length;
        int cdOffset = o;
        System.arraycopy(cd, 0, out, o, cd.length);
        o += cd.length;
        put32(out, o, 0x06054b50);
        put32(out, o + 12, cd.length);
        put32(out, o + 16, cdOffset);
        return out;
    }

    /** Entries + CD + EOCD with no signing block at all. */
    private static byte[] noSigningBlock() {
        byte[] entries = "PK\003\004FAKE".getBytes();
        byte[] cd = "PK\001\002FAKE".getBytes();
        byte[] out = new byte[entries.length + cd.length + 22];
        System.arraycopy(entries, 0, out, 0, entries.length);
        System.arraycopy(cd, 0, out, entries.length, cd.length);
        int o = entries.length + cd.length;
        put32(out, o, 0x06054b50);
        put32(out, o + 12, cd.length);
        put32(out, o + 16, entries.length);
        return out;
    }

    private static byte[] badMagicApk(byte[]... pairs) {
        byte[] apk = synthApk(pairs);
        // Corrupt one byte of the block magic (16 bytes ending
        // 24 bytes before the CD; find the CD via the EOCD).
        int eocd = apk.length - 22;
        int cdOffset = (int) u32(apk, eocd + 16);
        apk[cdOffset - 24 + 8] ^= 0xFF;
        return apk;
    }

    private static byte[] sizeMismatchApk(byte[]... pairs) {
        byte[] apk = synthApk(pairs);
        int eocd = apk.length - 22;
        int cdOffset = (int) u32(apk, eocd + 16);
        // Trailing size +1: the two size fields disagree.
        int footer = cdOffset - 24;
        put64(apk, footer, u64(apk, footer) + 1);
        return apk;
    }

    private static byte[] truncatedPairApk(byte[]... pairs) {
        // A pair whose declared length runs past the pairs
        // region: build the block, then inflate the first
        // pair's length field.
        byte[] apk = synthApk(pairs);
        int eocd = apk.length - 22;
        int cdOffset = (int) u32(apk, eocd + 16);
        int footer = cdOffset - 24;
        long size = u64(apk, footer);
        int firstPair = (int) (cdOffset - 8 - size) + 8;
        put64(apk, firstPair, u64(apk, firstPair) + 1000);
        return apk;
    }

    private static byte[] zip64Apk(byte[]... pairs) {
        byte[] apk = synthApk(pairs);
        int eocd = apk.length - 22;
        put32(apk, eocd + 16, 0xFFFFFFFF);
        return apk;
    }

    private static byte[] eocdCommentApk(byte[] pair1, byte[] pair2, String comment) {
        byte[] base = synthApk(pair1, pair2);
        byte[] c = comment.getBytes();
        byte[] out = new byte[base.length + c.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(c, 0, out, base.length, c.length);
        int eocd = base.length - 22;
        out[eocd + 20] = (byte) c.length;
        out[eocd + 21] = 0;
        return out;
    }

    private static byte[] fakeSigEntriesApk(byte[]... pairs) {
        // Entries containing a fake EOCD signature whose
        // comment length does NOT reach the end of file.
        byte[] apk = synthApk(pairs);
        apk[4] = 0x50;
        apk[5] = 0x4b;
        apk[6] = 0x05;
        apk[7] = 0x06;
        return apk;
    }

    private static void writeFile(String path, byte[] bytes) throws Exception {
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(path)) {
            f.write(bytes);
        }
    }

    private static void put32(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >> 8);
        b[off + 2] = (byte) (v >> 16);
        b[off + 3] = (byte) (v >> 24);
    }

    private static void put64(byte[] b, int off, long v) {
        for (int i = 0; i < 8; i++) b[off + i] = (byte) (v >> (8 * i));
    }

    private static long u32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static long u64(byte[] b, int off) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | (b[off + i] & 0xFFL);
        return v;
    }
}
