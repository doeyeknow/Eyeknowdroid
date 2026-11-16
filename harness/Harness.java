import app.eyeknow.android.BridgeMessages;
import app.eyeknow.android.CameraChoice;
import app.eyeknow.android.CropMath;
import app.eyeknow.android.FocusMath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * JVM harness for the eyeKnow shell's pure-Java pieces: the bridge
 * wire format, the camera-selection rule, crop math, and focus /
 * zoom math. Runs the exact shipping classes. No Android types.
 *
 * Compile: javac -d /tmp/ekh ../src/app/eyeknow/android/{BridgeMessages,CameraChoice,CropMath,FocusMath}.java Harness.java
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
}
