package app.eyeknow.android;

/**
 * One downscaled preview frame for iris detection: luminance plus
 * saturation, row-major. Pure Java — the harness builds synthetic
 * frames directly; the camera builds them from YUV planes via
 * {@link YuvFrame}.
 *
 * Luminance is 0..255 (the Y plane, which is what the web reader's
 * luminance weighting approximates). Saturation is 0..1, the web
 * reader's (max-min)/max over RGB.
 */
public final class IrisFrame {

    public final int width;
    public final int height;
    /** Luminance 0..255, row-major. */
    public final int[] gray;
    /** Saturation 0..1, row-major. Never null. */
    public final float[] sat;

    public IrisFrame(int width, int height, int[] gray, float[] sat) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad frame size");
        }
        if (gray == null || gray.length != width * height) {
            throw new IllegalArgumentException("gray array size mismatch");
        }
        if (sat == null || sat.length != width * height) {
            throw new IllegalArgumentException("sat array size mismatch");
        }
        this.width = width;
        this.height = height;
        this.gray = gray;
        this.sat = sat;
    }

    /** Nearest-neighbor luminance sample, clamped to the frame
     *  (the web reader's sampler rounds and clamps the same way). */
    public int lumAt(float x, float y) {
        int xi = Math.max(0, Math.min(width - 1, Math.round(x)));
        int yi = Math.max(0, Math.min(height - 1, Math.round(y)));
        return gray[yi * width + xi];
    }

    /** Nearest-neighbor saturation sample, clamped to the frame. */
    public float satAt(float x, float y) {
        int xi = Math.max(0, Math.min(width - 1, Math.round(x)));
        int yi = Math.max(0, Math.min(height - 1, Math.round(y)));
        return sat[yi * width + xi];
    }

    public int minDim() {
        return Math.min(width, height);
    }
}
