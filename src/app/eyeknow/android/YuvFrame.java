package app.eyeknow.android;

import java.nio.ByteBuffer;

/**
 * YUV_420_888 preview planes -> {@link IrisFrame}. Byte math only
 * (no Android types), so the harness proves the exact shipping
 * conversion with synthetic plane buffers.
 *
 * The frame is downsampled to TARGET_WIDTH (aspect preserved) by
 * nearest-neighbor sampling: detection needs shape, not pixels,
 * and a small frame keeps analysis off the preview's critical
 * path. Luminance comes straight from the Y plane. Saturation is
 * computed from a BT.601 YUV->RGB conversion at each sampled
 * point, then (max-min)/max — the web reader's saturation
 * definition.
 *
 * Plane layout notes (YUV_420_888): the Y plane is full
 * resolution; U and V are half resolution in each axis, each with
 * its own row stride and pixel stride. All strides are honored —
 * never assume packed planes.
 */
public final class YuvFrame {

    /** Working width for detection. The pupil grid is 180 wide
     *  (the web reader's grid); 320 gives the ray trace room to
     *  breathe without costing real time. */
    public static final int TARGET_WIDTH = 320;

    private YuvFrame() {}

    /**
     * Convert one YUV_420_888 image's planes to a downscaled
     * IrisFrame. Buffers are read, never modified; positions are
     * restored. Strides are in bytes; pixel strides are in bytes
     * between adjacent samples in the chroma planes.
     */
    public static IrisFrame convert(
            int srcWidth, int srcHeight,
            ByteBuffer yBuf, int yRowStride,
            ByteBuffer uBuf, int uRowStride, int uPixelStride,
            ByteBuffer vBuf, int vRowStride, int vPixelStride) {
        int outW = Math.min(TARGET_WIDTH, srcWidth);
        int outH = Math.max(1, Math.round((float) srcHeight * outW / srcWidth));
        int[] gray = new int[outW * outH];
        float[] sat = new float[outW * outH];
        for (int oy = 0; oy < outH; oy++) {
            int sy = (int) ((oy + 0.5f) * srcHeight / outH);
            if (sy >= srcHeight) sy = srcHeight - 1;
            int cy = sy / 2;
            for (int ox = 0; ox < outW; ox++) {
                int sx = (int) ((ox + 0.5f) * srcWidth / outW);
                if (sx >= srcWidth) sx = srcWidth - 1;
                int cx = sx / 2;
                int y = yBuf.get(sy * yRowStride + sx) & 0xFF;
                int u = uBuf.get(cy * uRowStride + cx * uPixelStride) & 0xFF;
                int v = vBuf.get(cy * vRowStride + cx * vPixelStride) & 0xFF;
                int o = oy * outW + ox;
                gray[o] = y;
                sat[o] = saturationOf(y, u, v);
            }
        }
        return new IrisFrame(outW, outH, gray, sat);
    }

    /** Saturation (max-min)/max of the BT.601 RGB conversion of
     *  one YUV sample. Package-private for the harness. */
    static float saturationOf(int y, int u, int v) {
        // BT.601 limited-range conversion, integer math.
        int c = y - 16;
        int d = u - 128;
        int e = v - 128;
        int r = (298 * c + 409 * e + 128) >> 8;
        int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
        int b = (298 * c + 516 * d + 128) >> 8;
        r = clamp255(r);
        g = clamp255(g);
        b = clamp255(b);
        int mx = Math.max(r, Math.max(g, b));
        int mn = Math.min(r, Math.min(g, b));
        if (mx == 0) return 0f;
        return (float) (mx - mn) / mx;
    }

    private static int clamp255(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
