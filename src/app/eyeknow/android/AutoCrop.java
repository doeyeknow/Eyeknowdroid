package app.eyeknow.android;

/**
 * Auto-crop for the auto-capture path (v1.2): turn the detection
 * that fired the gate into a square crop rect in the stored
 * JPEG's pixel space. Pure Java — the harness proves the exact
 * shipping math, including every rotation.
 *
 * Coordinate facts the mapping rests on:
 *
 * - The detector works on the YUV analysis frames, which are in
 *   SENSOR orientation (no rotation is ever applied to them).
 *   Its center is fractions of the analysis frame; its iris
 *   radius is in analysis-frame pixels.
 * - The still capture sets JPEG_ORIENTATION, so the stored JPEG
 *   is the sensor frame rotated CLOCKWISE by that many degrees
 *   (0/90/180/270), with its dimensions swapped for 90/270.
 *   CropMath/JpegUtil already work in the JPEG's own pixel
 *   space on that basis, and the crop screen displays the
 *   decoded JPEG with no extra rotation.
 * - The still and the analysis frames share the SAME
 *   SCALER_CROP_REGION (the zoom factor applies to both
 *   builders), so both cover the same field of view: fractions
 *   of the analysis frame are fractions of the sensor frame
 *   that the JPEG was rotated from. Zoom cancels out.
 *
 * The mapping is therefore: rotate the detected center's
 * fractions clockwise by JPEG_ORIENTATION, scale the radius by
 * the JPEG-to-analysis size ratio along each sensor axis (the
 * larger of the two, so the iris is covered even when the two
 * streams' aspect ratios differ), and frame generously.
 *
 * Crop rule: a SQUARE centered on the detected pupil center,
 * with half-side = iris radius x MARGIN. The margin is generous
 * on purpose: the web reader re-detects the iris itself, so the
 * crop must frame the iris with room around it, never cut it
 * tight. The rect is clamped inside the JPEG with CropMath's
 * rules (minimum 15% of the short side, whole square inside).
 *
 * Safety: mapToJpeg returns null whenever the mapping cannot be
 * computed safely, and the caller falls back to the manual crop
 * screen — never a guessed crop, never a silent failure. Null
 * cases: insane inputs (non-finite or out-of-range fractions,
 * non-positive radius or dimensions, a rotation that is not a
 * multiple of 90), a crop smaller than the minimum size, and a
 * stored JPEG whose dimensions disagree with the rotation
 * (e.g. the HAL stored the sensor-oriented pixels and only set
 * an EXIF tag — then the rotation assumption is false and no
 * mapping can be trusted).
 */
public final class AutoCrop {

    /** Crop half-side = iris radius x MARGIN. 1.3 leaves 30% of
     *  breathing room around the iris on every side. */
    public static final float MARGIN = 1.3f;

    private AutoCrop() {}

    /**
     * Map a firing detection to a square crop rect in the stored
     * JPEG's full pixel space, or null when the mapping cannot
     * be computed safely (caller falls back to manual crop).
     *
     * @param cxFrac  detected pupil center, fraction of the
     *                analysis frame width (sensor orientation)
     * @param cyFrac  detected pupil center, fraction of the
     *                analysis frame height
     * @param irisRPx detected iris radius, analysis-frame pixels
     * @param frameW  analysis frame width (detection space)
     * @param frameH  analysis frame height
     * @param jpegW   stored JPEG width in pixels
     * @param jpegH   stored JPEG height in pixels
     * @param jpegOrientation the JPEG_ORIENTATION value used for
     *                the still (clockwise rotation, 0/90/180/270)
     * @param stillW  requested still size width (sensor space)
     * @param stillH  requested still size height (sensor space)
     */
    public static CropMath.Rect mapToJpeg(
            float cxFrac, float cyFrac, float irisRPx,
            int frameW, int frameH,
            int jpegW, int jpegH,
            int jpegOrientation,
            int stillW, int stillH) {
        if (!Float.isFinite(cxFrac) || !Float.isFinite(cyFrac)
                || !Float.isFinite(irisRPx)) return null;
        if (cxFrac < 0f || cxFrac > 1f || cyFrac < 0f || cyFrac > 1f) return null;
        if (irisRPx <= 0f) return null;
        if (frameW <= 0 || frameH <= 0 || jpegW <= 0 || jpegH <= 0
                || stillW <= 0 || stillH <= 0) return null;
        int rot = ((jpegOrientation % 360) + 360) % 360;
        if (rot != 0 && rot != 90 && rot != 180 && rot != 270) return null;
        if (!dimsConsistent(stillW, stillH, jpegW, jpegH, rot)) return null;

        // Rotate the center fractions clockwise by the JPEG
        // rotation: the sensor frame's axes become the JPEG's.
        float gx;
        float gy;
        switch (rot) {
            case 0:
                gx = cxFrac;
                gy = cyFrac;
                break;
            case 90:
                gx = 1f - cyFrac;
                gy = cxFrac;
                break;
            case 180:
                gx = 1f - cxFrac;
                gy = 1f - cyFrac;
                break;
            default: // 270
                gx = cyFrac;
                gy = 1f - cxFrac;
                break;
        }
        // Center in JPEG pixels. Clamped inside so a fraction of
        // exactly 1.0 can never put the center on the far edge.
        float cxPx = Math.max(0f, Math.min(jpegW - 1f, gx * jpegW));
        float cyPx = Math.max(0f, Math.min(jpegH - 1f, gy * jpegH));

        // Radius in JPEG pixels along each sensor axis. For
        // 90/270 the sensor's width axis runs along the JPEG's
        // height, and vice versa. The larger scale wins so the
        // iris is covered on both axes when the streams'
        // aspect ratios differ; when they match, both scales
        // are equal and the mapping is exact.
        int jpegAlongSensorW = (rot == 90 || rot == 270) ? jpegH : jpegW;
        int jpegAlongSensorH = (rot == 90 || rot == 270) ? jpegW : jpegH;
        float ax = irisRPx * jpegAlongSensorW / frameW;
        float ay = irisRPx * jpegAlongSensorH / frameH;
        float halfSide = Math.max(ax, ay) * MARGIN;
        if (!Float.isFinite(halfSide) || halfSide <= 0f) return null;
        int side = Math.round(halfSide * 2f);

        // Below the minimum crop size the iris would be a smear
        // (CropMath's floor); refuse rather than submit it. The
        // gate's own iris-size floor makes this unreachable in
        // practice — it is a safety net, not a path.
        int minSize = Math.max(1, Math.round(
                Math.min(jpegW, jpegH) * CropMath.MIN_FRACTION));
        if (side < minSize) return null;

        CropMath.Rect rect = CropMath.clamp(new CropMath.Rect(
                Math.round(cxPx - halfSide), Math.round(cyPx - halfSide), side),
                jpegW, jpegH);
        // The clamp must never push the pupil center out of the
        // square: verify, and refuse if it somehow did.
        if (cxPx < rect.left || cxPx >= rect.left + rect.size
                || cyPx < rect.top || cyPx >= rect.top + rect.size) return null;
        return rect;
    }

    /**
     * The stored JPEG's dimensions must agree with the rotation:
     * for 90/270 the JPEG is the still size with axes swapped,
     * for 0/180 it matches the still size. A mismatch means the
     * HAL did not rotate the pixels the way the mapping assumes
     * (e.g. EXIF-only orientation), so no mapping is trusted.
     * Square stills cannot disambiguate and always pass.
     */
    static boolean dimsConsistent(int stillW, int stillH,
                                  int jpegW, int jpegH, int rotation) {
        int wantW = (rotation == 90 || rotation == 270) ? stillH : stillW;
        int wantH = (rotation == 90 || rotation == 270) ? stillW : stillH;
        return jpegW == wantW && jpegH == wantH;
    }
}
