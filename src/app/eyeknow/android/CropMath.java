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
 * Sizing follows the web capture caps (lib/capture.ts): frames
 * are drawn no bigger than 2560 on the long side, JPEG quality
 * 0.92. The native still is capped the same way so the photo the
 * reader receives is the same class of image the web flow makes.
 */
public final class CropMath {

    public static final int LONG_SIDE_CAP = 2560;
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
     * BitmapFactory inSampleSize to decode a JPEG of w x h so the
     * decoded bitmap's long side is at most LONG_SIDE_CAP. Powers
     * of two only (what the decoder honors). Returns 1 when the
     * image already fits.
     */
    public static int sampleSize(int w, int h) {
        int longSide = Math.max(w, h);
        int sample = 1;
        while (longSide / sample > LONG_SIDE_CAP) sample *= 2;
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
}
