package app.eyeknow.android;

/**
 * RGB pixels -> {@link IrisFrame}. Byte/int math only (no Android
 * types), so the harness proves the exact shipping conversion
 * with real JPEG pixels via javax.imageio.
 *
 * This is the verification path's frame builder (v1.4): the cut
 * auto-crop is decoded, its pixels land here, and the SAME
 * {@link IrisDetector} that gated the capture re-runs on them —
 * no second detection standard. The camera's live path keeps
 * using {@link YuvFrame}; the two builders share the working
 * width and the saturation definition, and differ only in their
 * input format:
 *
 * - The frame is downsampled to TARGET_WIDTH (aspect preserved)
 *   by nearest-neighbor sampling, the same rule YuvFrame uses.
 * - Luminance is Rec.601 luma (0.299R + 0.587G + 0.114B) — the
 *   standard RGB equivalent of the Y plane YuvFrame reads.
 * - Saturation is (max-min)/max over RGB — the web reader's
 *   definition, and the same value YuvFrame computes from its
 *   BT.601 conversion.
 */
public final class RgbFrame {

    /** Working width for detection, shared with YuvFrame. */
    public static final int TARGET_WIDTH = 320;

    private RgbFrame() {}

    /**
     * Convert packed ARGB pixels (as returned by
     * Bitmap.getPixels / BufferedImage.getRGB: 0xAARRGGBB,
     * row-major, no stride) to a downscaled IrisFrame. Alpha is
     * ignored. Throws on bad sizes, like IrisFrame itself.
     */
    public static IrisFrame fromArgb(int[] argb, int srcW, int srcH) {
        if (argb == null || srcW <= 0 || srcH <= 0
                || argb.length != srcW * srcH) {
            throw new IllegalArgumentException("bad RGB input");
        }
        int outW = Math.min(TARGET_WIDTH, srcW);
        int outH = Math.max(1, Math.round((float) srcH * outW / srcW));
        int[] gray = new int[outW * outH];
        float[] sat = new float[outW * outH];
        for (int oy = 0; oy < outH; oy++) {
            int sy = (int) ((oy + 0.5f) * srcH / outH);
            if (sy >= srcH) sy = srcH - 1;
            for (int ox = 0; ox < outW; ox++) {
                int sx = (int) ((ox + 0.5f) * srcW / outW);
                if (sx >= srcW) sx = srcW - 1;
                int px = argb[sy * srcW + sx];
                int r = (px >> 16) & 0xFF;
                int g = (px >> 8) & 0xFF;
                int b = px & 0xFF;
                int o = oy * outW + ox;
                gray[o] = lumaOf(r, g, b);
                sat[o] = saturationOf(r, g, b);
            }
        }
        return new IrisFrame(outW, outH, gray, sat);
    }

    /** Rec.601 luma, integer math, 0..255. */
    static int lumaOf(int r, int g, int b) {
        return (r * 299 + g * 587 + b * 114 + 500) / 1000;
    }

    /** Saturation (max-min)/max over RGB, 0..1. */
    static float saturationOf(int r, int g, int b) {
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        if (mx == 0) return 0f;
        return (float) (mx - mn) / mx;
    }
}
