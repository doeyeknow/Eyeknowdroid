package app.eyeknow.android;

/**
 * Post-crop verification for the auto-capture path (v1.4): after
 * the auto-crop is cut and BEFORE it is submitted, the iris edge
 * is traced from the crop's EXACT center with the detector's own
 * ray machinery ({@link IrisDetector#traceFromCenter}), and this
 * class decides whether the framing is good enough to submit. A
 * wrong mapping, a stale detection, or movement after the gate
 * fired all used to sail through silently — now they fall back
 * to the manual crop screen with the raw photo intact.
 *
 * Pure Java — the harness proves the exact shipping decision,
 * including against Adam's real mis-framed photo (the fixture),
 * which MUST be rejected, and re-centered versions of it, which
 * MUST be accepted.
 *
 * Why the center trace, not the pupil position: the pupil blob
 * cannot be trusted on real dark eyes. Measured on Adam's photo
 * (harness fixture leg, pinned): the blob lands on a dark iris
 * patch instead of the pupil — the flash reflection plus the
 * dark-brown iris leave no clean luminance blob — and on
 * re-centered versions of the SAME photo it alternates between
 * no-pupil, correct, and wrong-blob across scales, while the
 * center trace stays consistent (validFrac 0.75–0.97 on the
 * re-centered versions vs 0.36 on the mis-framed original).
 * Gating on the pupil position would false-reject good crops
 * of this eye most of the time. The center trace asks the
 * question verification actually needs — "is the iris centered
 * and fully inside THIS crop?" — with no pupil dependency.
 *
 * The pass rule, both required:
 *
 * 1. The center trace is solid: validFrac >= 0.55, the gate's
 *    own well-framed line (VALID_FRAC_FRAMED, reused — one
 *    standard, not two). A cut or displaced iris loses the
 *    rays pointing at the missing edge: the fixture traces
 *    0.36, a 0.32-off-center synthetic traces 0.42, a too-tight
 *    synthetic traces 0.31, while centered crops trace 0.75+.
 * 2. The iris radius is in a sane band: 18%..50% of the crop
 *    side. The crop rule aims at 38.5% (half-side = iris
 *    radius x 1.3); below 18% the firing detection
 *    overestimated the radius by 2x or more (the iris is a
 *    postage stamp in the frame); above 50% the iris diameter
 *    exceeds the crop (the iris is cut, whatever the trace
 *    says). Both mean the firing detection cannot be trusted.
 *
 * Deliberately NOT gated: the radius SPREAD (recorded in
 * diagnostics, not judged). On clean synthetics it separates
 * centered from off-center beautifully, but on real photos
 * eyelid coverage and an iris already cut in the source photo
 * inflate it past the mis-framed photo's own value — gating on
 * it would false-reject good crops. And sharpness: the gate
 * already required it at fire time, and a soft photo shows the
 * same softness on the manual crop screen — verification
 * polices FRAMING only.
 *
 * A null trace (no frame, detector error upstream) is a
 * failure, never a pass: when verification cannot run, the
 * photo falls back to the manual crop screen.
 */
public final class CropVerify {

    /** Minimum center-trace validFrac: the gate's well-framed
     *  line, reused. */
    public static final float MIN_VALID_FRAC = IrisDetector.VALID_FRAC_FRAMED;
    /** Iris radius band, as a fraction of the crop side. */
    public static final float MIN_IRIS_FRAC = 0.18f;
    public static final float MAX_IRIS_FRAC = 0.50f;

    /** Why a crop failed verification. Stable codes — the
     *  diagnostics record stores these, and the fallback screen
     *  turns them into plain words. */
    public enum Reason {
        /** Passed all checks. */
        OK,
        /** No trace result at all (no frame / error). */
        NO_TRACE,
        /** The center trace was too weak: the iris edge could
         *  not be traced from the crop center (cut, displaced,
         *  or no iris in the crop). */
        EDGE_WEAK,
        /** Iris radius outside the sane band. */
        BAD_RADIUS
    }

    /** One verification decision, with the measurements the
     *  diagnostics record and the fallback screen report. */
    public static final class Verdict {
        public final boolean pass;
        public final Reason reason;
        /** Fraction of center-trace rays that found the iris
         *  edge. NaN when no trace ran. */
        public final float validFrac;
        /** Measured iris radius, fraction of the crop's short
         *  side. NaN when no trace ran. */
        public final float irisFrac;
        /** Spread of the valid ray radii ((p90-p10)/median).
         *  Recorded for diagnostics, never gated. NaN when no
         *  trace ran. */
        public final float spread;

        public Verdict(boolean pass, Reason reason,
                       float validFrac, float irisFrac, float spread) {
            this.pass = pass;
            this.reason = reason;
            this.validFrac = validFrac;
            this.irisFrac = irisFrac;
            this.spread = spread;
        }
    }

    private CropVerify() {}

    /** Decide a cut crop from its center trace plus the
     *  verification frame's short side in pixels (for the
     *  iris-radius band). A null trace fails with NO_TRACE. */
    public static Verdict verify(IrisDetector.CenterTrace trace, int minDim) {
        if (trace == null || minDim <= 0) {
            return new Verdict(false, Reason.NO_TRACE,
                    Float.NaN, Float.NaN, Float.NaN);
        }
        float irisFrac = trace.irisR / minDim;
        if (trace.validFrac < MIN_VALID_FRAC) {
            return new Verdict(false, Reason.EDGE_WEAK,
                    trace.validFrac, irisFrac, trace.spread);
        }
        if (irisFrac < MIN_IRIS_FRAC || irisFrac > MAX_IRIS_FRAC) {
            return new Verdict(false, Reason.BAD_RADIUS,
                    trace.validFrac, irisFrac, trace.spread);
        }
        return new Verdict(true, Reason.OK,
                trace.validFrac, irisFrac, trace.spread);
    }
}
