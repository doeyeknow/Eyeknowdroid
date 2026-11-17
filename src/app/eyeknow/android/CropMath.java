package app.eyeknow.android;

/**
 * Crop + sizing math for the native crop step. Pure Java — the
 * harness proves the exact shipping math.
 *
 * The site defines NO crop contract (lib/capture.ts hands full
 * frames to autoRead, which finds the iris itself), so the crop
 * step is the native UX Adam ordered: a SQUARE crop around the
 * eye. The cropped image still goes through autoRead's own iris
 * detection, which works on any image. Square is the only shape
 * the crop step offers — it matches "frame the iris" and cannot
 * produce a sliver.
 *
 * Sizing: the capture requests the largest JPEG size the
 * camera reports, and the written crop keeps as much of that
 * detail as the bridge allows. Two caps, kept separate on
 * purpose:
 *
 * - LONG_SIDE_CAP (4096) caps the WRITTEN crop output
 *   (JpegUtil.cropToFile): a crop larger than this is
 *   downscaled to it; anything smaller is written at its
 *   native crop resolution. The web reader caps its ANALYSIS
 *   canvas at 2000px, but the stored/displayed photo is the
 *   submitted image at full size, so a bigger crop sharpens
 *   the photo the user sees and keeps, and slightly improves
 *   the analysis input — without changing the analysis
 *   resolution. The bridge carries the photo as a base64 data
 *   URL, so the output stays in the low single-digit MB range
 *   at quality 92.
 * - PREVIEW_LONG_SIDE_CAP (2560) caps the DISPLAY decode
 *   (JpegUtil.decodeCapped): the crop screen never needs
 *   full resolution, and the preview must stay fast.
 *
 * Rotation: the crop screen lets the user rotate the photo
 * 90 degrees clockwise per tap. The crop square is defined in
 * DISPLAYED space (the rotated view); rotateRect/unrotateRect
 * map square rects between source and displayed pixel space
 * for 0/90/180/270. The auto path never rotates (rotation 0).
 */
public final class CropMath {

    /** Long-side cap for the WRITTEN crop output. */
    public static final int LONG_SIDE_CAP = 4096;

    /** Long-side cap for the DISPLAY decode only (the crop
     *  screen preview). Unchanged since v1.0: the preview
     *  must stay fast and never needs full resolution. */
    public static final int PREVIEW_LONG_SIDE_CAP = 2560;

    public static final int JPEG_QUALITY = 92;

    /** Default crop square: this fraction of the shorter side,
     *  centered. Big enough to hold the whole eye with margin. */
    public static final float DEFAULT_FRACTION = 0.72f;

    /** Smallest allowed crop square, as a fraction of the shorter
     *  side — below this the iris would be a smear. */
    public static final float MIN_FRACTION = 0.15f;

    private CropMath() {}

    /** Integer rect in image pixels. left/top inclusive, size is
     *  the square side. Always inside [0,w]x[0,h]. */
    public static final class Rect {
        public final int left;
        public final int top;
        public final int size;

        public Rect(int left, int top, int size) {
            this.left = left;
            this.top = top;
            this.size = size;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Rect)) return false;
            Rect r = (Rect) o;
            return left == r.left && top == r.top && size == r.size;
        }

        @Override public int hashCode() {
            return left * 31 * 31 + top * 31 + size;
        }

        @Override public String toString() {
            return "Rect(" + left + "," + top + "," + size + ")";
        }
    }

    /** Default centered square for an image of w x h. */
    public static Rect defaultCrop(int w, int h) {
        int shortSide = Math.min(w, h);
        int size = Math.max(1, Math.round(shortSide * DEFAULT_FRACTION));
        return new Rect((w - size) / 2, (h - size) / 2, size);
    }

    /** Clamp a square crop inside the image. The size is clamped to
     *  [minSize, shortSide]; the position is clamped so the whole
     *  square stays inside. */
    public static Rect clamp(Rect r, int w, int h) {
        int shortSide = Math.min(w, h);
        int minSize = Math.max(1, Math.round(shortSide * MIN_FRACTION));
        int size = Math.max(minSize, Math.min(shortSide, r.size));
        int left = Math.max(0, Math.min(w - size, r.left));
        int top = Math.max(0, Math.min(h - size, r.top));
        return new Rect(left, top, size);
    }

    /** Move a crop by (dx, dy) image pixels, clamped inside. */
    public static Rect move(Rect r, int dx, int dy, int w, int h) {
        return clamp(new Rect(r.left + dx, r.top + dy, r.size), w, h);
    }

    /** Resize a crop around its center by a scale factor, clamped. */
    public static Rect scale(Rect r, float factor, int w, int h) {
        int cx = r.left + r.size / 2;
        int cy = r.top + r.size / 2;
        int size = Math.max(1, Math.round(r.size * factor));
        return clamp(new Rect(cx - size / 2, cy - size / 2, size), w, h);
    }

    /**
     * Map a crop rect drawn in VIEW coordinates to IMAGE pixels.
     * The view shows the image letterboxed (fit-center): the image
     * is scaled by s = min(vw/iw, vh/ih) and centered, so view
     * point (vx, vy) maps to image ((vx-ox)/s, (vy-oy)/s).
     * The result is clamped inside the image. All inputs in pixels.
     */
    public static Rect viewToImage(Rect viewRect, int vw, int vh, int iw, int ih) {
        float s = Math.min((float) vw / iw, (float) vh / ih);
        float ox = (vw - iw * s) / 2f;
        float oy = (vh - ih * s) / 2f;
        int left = Math.round((viewRect.left - ox) / s);
        int top = Math.round((viewRect.top - oy) / s);
        int size = Math.round(viewRect.size / s);
        return clamp(new Rect(left, top, size), iw, ih);
    }

    /** Map an image crop rect back to view coordinates (for drawing
     *  the overlay after a clamp). Inverse of viewToImage. */
    public static Rect imageToView(Rect imgRect, int vw, int vh, int iw, int ih) {
        float s = Math.min((float) vw / iw, (float) vh / ih);
        float ox = (vw - iw * s) / 2f;
        float oy = (vh - ih * s) / 2f;
        int left = Math.round(ox + imgRect.left * s);
        int top = Math.round(oy + imgRect.top * s);
        int size = Math.round(imgRect.size * s);
        return new Rect(left, top, size);
    }

    /**
     * BitmapFactory inSampleSize to decode a JPEG of w x h for
     * DISPLAY so the decoded bitmap's long side is at most
     * PREVIEW_LONG_SIDE_CAP. Powers of two only (what the
     * decoder honors). Returns 1 when the image already fits.
     * The written crop never uses this path — it region-decodes
     * at full resolution (JpegUtil.cropToFile).
     */
    public static int sampleSize(int w, int h) {
        int longSide = Math.max(w, h);
        int sample = 1;
        while (longSide / sample > PREVIEW_LONG_SIDE_CAP) sample *= 2;
        return sample;
    }

    /** Output size after capping the long side at LONG_SIDE_CAP
     *  (aspect preserved). Returns {w, h}. No-op when it fits. */
    public static int[] cappedSize(int w, int h) {
        int longSide = Math.max(w, h);
        if (longSide <= LONG_SIDE_CAP) return new int[] { w, h };
        float s = (float) LONG_SIDE_CAP / longSide;
        return new int[] { Math.max(1, Math.round(w * s)),
                           Math.max(1, Math.round(h * s)) };
    }

    // ------------------------------------------------------- rotation

    /** Normalize a rotation to 0/90/180/270. Anything else is
     *  treated as 0 — the crop screen only ever produces
     *  multiples of 90, and a bogus value must never rotate. */
    public static int normalizeRotation(int degrees) {
        int d = ((degrees % 360) + 360) % 360;
        return (d == 90 || d == 180 || d == 270) ? d : 0;
    }

    /** Dimensions {w, h} of an image of w x h rotated CLOCKWISE
     *  by degrees (0/90/180/270). 90/270 swap the axes. */
    public static int[] rotatedDims(int w, int h, int degrees) {
        int d = normalizeRotation(degrees);
        return (d == 90 || d == 270) ? new int[] { h, w } : new int[] { w, h };
    }

    /**
     * Map a square rect from SOURCE pixel space to DISPLAYED
     * space, where the displayed image is the source rotated
     * CLOCKWISE by degrees. The mapping is exact integer
     * arithmetic on the rect's edges: a source pixel (x, y) in
     * a W x H image lands at displayed (H-1-y, x) for 90, at
     * (W-1-x, H-1-y) for 180, and at (y, W-1-x) for 270. The
     * result is clamped inside the displayed dimensions (a
     * no-op for an in-bounds source rect, a safety net for a
     * rounded one). The size is preserved exactly — unlike
     * clamp(), no minimum is enforced here, because the rect
     * was already minimum-clamped in its own space and the
     * rotation must not grow it.
     */
    public static Rect rotateRect(Rect src, int srcW, int srcH, int degrees) {
        int d = normalizeRotation(degrees);
        int[] dims = rotatedDims(srcW, srcH, d);
        Rect r;
        switch (d) {
            case 90:
                r = new Rect(srcH - src.top - src.size, src.left, src.size);
                break;
            case 180:
                r = new Rect(srcW - src.left - src.size, srcH - src.top - src.size, src.size);
                break;
            case 270:
                r = new Rect(src.top, srcW - src.left - src.size, src.size);
                break;
            default:
                r = src;
                break;
        }
        return clampPosition(r, dims[0], dims[1]);
    }

    /**
     * Inverse of rotateRect: map a square rect from DISPLAYED
     * space back to SOURCE pixel space. The crop screen draws
     * the square in displayed space; confirm maps it back
     * through here, crops the source, and rotates the cropped
     * square by the same rotation — so the written JPEG is
     * exactly what the preview showed inside the square. The
     * size is preserved exactly (see rotateRect).
     */
    public static Rect unrotateRect(Rect disp, int srcW, int srcH, int degrees) {
        int d = normalizeRotation(degrees);
        Rect r;
        switch (d) {
            case 90:
                r = new Rect(disp.top, srcH - disp.left - disp.size, disp.size);
                break;
            case 180:
                r = new Rect(srcW - disp.left - disp.size, srcH - disp.top - disp.size, disp.size);
                break;
            case 270:
                r = new Rect(srcW - disp.top - disp.size, disp.left, disp.size);
                break;
            default:
                r = disp;
                break;
        }
        return clampPosition(r, srcW, srcH);
    }

    /** Clamp a square rect's POSITION inside the image, keeping
     *  its size (capped at the short side). Unlike clamp(), no
     *  minimum size is enforced: rotation mapping must never
     *  grow a rect. */
    private static Rect clampPosition(Rect r, int w, int h) {
        int size = Math.max(1, Math.min(Math.min(w, h), r.size));
        int left = Math.max(0, Math.min(w - size, r.left));
        int top = Math.max(0, Math.min(h - size, r.top));
        return new Rect(left, top, size);
    }
}
