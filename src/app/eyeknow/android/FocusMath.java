package app.eyeknow.android;

/**
 * Zoom crop-region + tap-to-focus metering math. Pure Java — the
 * harness proves the exact shipping math. Rects are int arrays
 * {left, top, right, bottom} in sensor (active array) pixels.
 */
public final class FocusMath {

    /** Tap-to-focus box side, as a fraction of the shorter side of
     *  the current (zoomed) view. Small enough to aim at an eye. */
    public static final float FOCUS_BOX_FRACTION = 0.14f;

    private FocusMath() {}

    /** Clamp a zoom factor into [1, maxZoom]. */
    public static float clampZoom(float factor, float maxZoom) {
        if (factor < 1f) return 1f;
        if (factor > maxZoom) return maxZoom;
        return factor;
    }

    /**
     * SCALER_CROP_REGION for a zoom factor: the active array shrunk
     * around its center by 1/factor. Factor 1 returns the whole
     * active array. Result is always inside the active array.
     */
    public static int[] cropRegion(int[] active, float factor, float maxZoom) {
        float f = clampZoom(factor, maxZoom);
        int w = active[2] - active[0];
        int h = active[3] - active[1];
        int cw = Math.max(1, Math.round(w / f));
        int ch = Math.max(1, Math.round(h / f));
        int cx = active[0] + w / 2;
        int cy = active[1] + h / 2;
        int left = cx - cw / 2;
        int top = cy - ch / 2;
        left = Math.max(active[0], Math.min(active[2] - cw, left));
        top = Math.max(active[1], Math.min(active[3] - ch, top));
        return new int[] { left, top, left + cw, top + ch };
    }

    /**
     * Metering rect for a tap at fractions (fx, fy) of the current
     * (zoomed) view. The tap maps into the crop region, and the
     * box is clamped inside the ACTIVE array (the HAL requires
     * metering rects within the active array, not just the crop).
     */
    public static int[] focusBox(float fx, float fy, int[] crop, int[] active) {
        float cx = crop[0] + clamp01(fx) * (crop[2] - crop[0]);
        float cy = crop[1] + clamp01(fy) * (crop[3] - crop[1]);
        int side = Math.round(FOCUS_BOX_FRACTION
                * Math.min(crop[2] - crop[0], crop[3] - crop[1]));
        side = Math.max(1, side);
        int left = Math.round(cx - side / 2f);
        int top = Math.round(cy - side / 2f);
        left = Math.max(active[0], Math.min(active[2] - side, left));
        top = Math.max(active[1], Math.min(active[3] - side, top));
        return new int[] { left, top, left + side, top + side };
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
