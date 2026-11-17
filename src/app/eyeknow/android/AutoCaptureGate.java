package app.eyeknow.android;

/**
 * Auto-capture decision: an iris must be present, well-framed, in
 * focus, and STEADY for several consecutive analyzed frames
 * before the shutter fires. Pure Java — the harness proves the
 * exact shipping state machine.
 *
 * The per-frame pass rule:
 * - the detector's hard gates pass (pupil found, validFrac >=
 *   0.30, iris big enough) AND the frame is well-framed
 *   (validFrac >= 0.55), AND
 * - annulus sharpness >= 6 (the reader's soft-photo floor), AND
 * - the camera reports focused (PASSIVE_FOCUSED / FOCUSED_LOCKED)
 *   — unless the device reports no AF state at all, in which case
 *   sharpness alone decides (fixed-focus lenses never report).
 *
 * The stability rule (new, documented): N consecutive passing
 * frames, with the pupil center drifting no more than 3% of the
 * frame between feeds — the same 3% movement bound the web
 * capture's frame stacking uses to drop moved frames — and the
 * iris radius steady within 10%. Any failing feed, or a jump
 * beyond those bounds, resets the count: the eye must be held
 * still, not swept past.
 *
 * At the camera's ~200ms analysis cadence, N = 4 means roughly
 * 0.8 seconds of steady, in-focus iris before the shutter fires.
 * The gate fires once and latches until reset() (retake, or the
 * toggle cycled) — it never double-fires.
 */
public final class AutoCaptureGate {

    /** Consecutive passing feeds required before firing. */
    public static final int STEADY_FRAMES = 4;
    /** Max pupil-center drift between feeds, fraction of frame. */
    public static final float MAX_DRIFT_FRAC = 0.03f;
    /** Max iris-radius change between feeds, fraction of radius. */
    public static final float MAX_RADIUS_DRIFT_FRAC = 0.10f;

    public enum State {
        /** No iris in the frame (or the last feed failed the gates). */
        SEARCHING,
        /** Iris present but not yet well-framed. */
        FOUND,
        /** Well-framed but not yet in focus. */
        FOCUSING,
        /** In focus; counting steady frames. */
        STEADY,
        /** Fired — the shutter was triggered. Latched until reset. */
        FIRED
    }

    private int steadyCount;
    private float lastCx = Float.NaN;
    private float lastCy = Float.NaN;
    private float lastIrisR = Float.NaN;
    private boolean fired;

    /** Feed one analyzed frame. Returns the state after the feed;
     *  FIRED is returned exactly once, on the feed that completes
     *  the steady window. */
    public State feed(IrisDetector.Result r, boolean afFocused, boolean afKnown) {
        if (fired) return State.FIRED;
        if (!r.irisPresent) {
            resetCount();
            return State.SEARCHING;
        }
        if (!r.wellFramed) {
            resetCount();
            return State.FOUND;
        }
        boolean sharp = r.sharpness >= IrisDetector.SHARPNESS_FLOOR;
        boolean focusOk = sharp && (!afKnown || afFocused);
        if (!focusOk) {
            resetCount();
            return State.FOCUSING;
        }
        // In focus: check steadiness against the previous feed.
        if (!Float.isNaN(lastCx)) {
            float dx = Math.abs(r.cx - lastCx);
            float dy = Math.abs(r.cy - lastCy);
            float dr = Math.abs(r.irisR - lastIrisR) / Math.max(1f, lastIrisR);
            if (dx > MAX_DRIFT_FRAC || dy > MAX_DRIFT_FRAC || dr > MAX_RADIUS_DRIFT_FRAC) {
                resetCount();
                lastCx = r.cx;
                lastCy = r.cy;
                lastIrisR = r.irisR;
                steadyCount = 1;
                return State.STEADY;
            }
        }
        lastCx = r.cx;
        lastCy = r.cy;
        lastIrisR = r.irisR;
        steadyCount++;
        if (steadyCount >= STEADY_FRAMES) {
            fired = true;
            return State.FIRED;
        }
        return State.STEADY;
    }

    /** Steady feeds banked so far (0..STEADY_FRAMES), for progress UI. */
    public int steadyCount() {
        return steadyCount;
    }

    public boolean hasFired() {
        return fired;
    }

    /** Full reset: retake, toggle cycled, or a new capture session. */
    public void reset() {
        fired = false;
        resetCount();
    }

    private void resetCount() {
        steadyCount = 0;
        lastCx = Float.NaN;
        lastCy = Float.NaN;
        lastIrisR = Float.NaN;
    }
}
