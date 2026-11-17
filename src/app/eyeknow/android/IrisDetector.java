package app.eyeknow.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Iris presence + framing + sharpness on a downscaled preview
 * frame. Pure Java — the harness proves the exact shipping logic
 * on synthetic frames.
 *
 * This is a deliberate port of the web reader's decision logic
 * (lib/autoread.ts), not a new detection standard:
 *
 * - Pupil: the darkest compact round blob on a 180-wide grid,
 *   searched from the strictest threshold up ([18, 26, 34, 44,
 *   56]), with the web's blob acceptance rules (area fraction,
 *   center window, aspect, fill, radius bounds), the grow step
 *   (core mean + 34, capped at 88, growth capped at 2x radius),
 *   and the 36-ray radial-gradient radius refinement (jump > 26).
 * - Iris edge: 72 rays, score = sat*150 + (255-lum)*0.45, edge
 *   after 3 consecutive samples below baseline*ratio; strict
 *   ratio 0.45, lenient 0.62 when validFrac < 0.45, gradient
 *   fallback (best drop > 12) when validFrac < 0.3.
 * - Hard gates (Adam, 2026-11-08): pupil found, validFrac >=
 *   0.30, iris radius >= 0.07 * minDim — the same floor that
 *   makes the reader refuse a photo.
 * - Well-framed: validFrac >= 0.55, the web's "fitted by
 *   estimate" line — below it the reader warns the edge was
 *   hard to trace, so auto-capture waits for a better frame.
 * - Sharpness: the web reader's photo-quality gradient (autoread
 *   section 7) — mean |luminance step| between samples 1.17% of
 *   the width apart inside the annulus (0.3 < r/R <= 1.0), with
 *   the web's own threshold: below 6 the photo is "a little soft
 *   or blurry".
 *
 * Documented adaptations (the frame here is ~320px, not the
 * reader's up-to-2000px analysis image):
 * - Ray step is 1 working pixel (the web steps 3px on a much
 *   larger image; 1px here is the same order of fraction of the
 *   frame). Edge back-off is 2 steps, matching the web's 6px.
 * - The gradient fallback compares samples 2px apart (the web
 *   compares 3px apart) for the same reason.
 * - No denoise pass: preview frames at this size carry little
 *   JPEG speckle, and the pupil grid subsampling already
 *   averages. The reader's denoise is analysis-only and the
 *   stored photo is never denoised; nothing here alters a photo.
 * - No pupil-shape classification: the gate needs position and
 *   radius only.
 */
public final class IrisDetector {

    /** Hard floor on traced rays, from the reader (Adam 2026-11-08). */
    public static final float VALID_FRAC_HARD_FLOOR = 0.30f;
    /** Well-framed line: the reader's "fitted by estimate" note. */
    public static final float VALID_FRAC_FRAMED = 0.55f;
    /** Minimum iris radius as a fraction of the frame's short side. */
    public static final float MIN_IRIS_RADIUS_FRAC = 0.07f;
    /** Annulus-gradient sharpness floor, from the reader's quality
     *  check: below 6 the photo is soft or blurry. */
    public static final float SHARPNESS_FLOOR = 6.0f;

    private static final int PUPIL_GRID_W = 180;
    private static final int[] PUPIL_THRESHOLDS = {18, 26, 34, 44, 56};
    private static final int RAYS = 72;

    private IrisDetector() {}

    public static final class Result {
        /** A pupil blob passed the reader's acceptance rules. */
        public final boolean pupilFound;
        /** Pupil center, fractions of the frame. */
        public final float cx;
        public final float cy;
        /** Pupil radius in frame pixels. */
        public final float pupilR;
        /** Iris radius in frame pixels (median traced edge). */
        public final float irisR;
        /** Fraction of rays that truly found the iris edge. */
        public final float validFrac;
        /** Annulus-gradient sharpness (mean |luminance step|). */
        public final float sharpness;
        /** Mean annulus luminance, for "too dark" reporting. */
        public final float meanLum;
        /** The reader's hard gates all pass: this frame is
         *  readable as an iris photo. */
        public final boolean irisPresent;
        /** Well-framed: the edge trace is solid (validFrac >=
         *  0.55) on top of the hard gates. */
        public final boolean wellFramed;

        /** Public so the harness can feed the gate crafted
         *  results; production code only gets Results from
         *  {@link IrisDetector#detect}. */
        public Result(boolean pupilFound, float cx, float cy, float pupilR,
               float irisR, float validFrac, float sharpness,
               float meanLum, boolean irisPresent, boolean wellFramed) {
            this.pupilFound = pupilFound;
            this.cx = cx;
            this.cy = cy;
            this.pupilR = pupilR;
            this.irisR = irisR;
            this.validFrac = validFrac;
            this.sharpness = sharpness;
            this.meanLum = meanLum;
            this.irisPresent = irisPresent;
            this.wellFramed = wellFramed;
        }
    }

    public static Result detect(IrisFrame frame) {
        int w = frame.width;
        int h = frame.height;
        int minDim = frame.minDim();
        Pupil pupil = findPupil(frame);
        boolean pupilFound = pupil != null;
        float cx;
        float cy;
        float rp;
        if (pupilFound) {
            cx = pupil.cx;
            cy = pupil.cy;
            rp = Math.max(2f, pupil.r);
        } else {
            // No pupil: the reader centers by estimate for a
            // reading, but the gate must NOT treat that as an
            // iris — report the estimate center and fail the
            // hard gate below.
            cx = w / 2f;
            cy = h / 2f;
            rp = minDim * 0.085f;
        }
        Trace traced = traceEdges(frame, cx, cy, rp, 0.45f);
        if (traced.validFrac < 0.45f) {
            Trace lenient = traceEdges(frame, cx, cy, rp, 0.62f);
            if (lenient.validFrac > traced.validFrac) traced = lenient;
        }
        if (traced.validFrac < 0.3f) {
            Trace gradient = traceEdgesGradient(frame, cx, cy, rp);
            if (gradient.validFrac > traced.validFrac) traced = gradient;
        }
        float irisR = traced.r;
        boolean irisPresent = pupilFound
                && traced.validFrac >= VALID_FRAC_HARD_FLOOR
                && irisR >= minDim * MIN_IRIS_RADIUS_FRAC;
        boolean wellFramed = irisPresent && traced.validFrac >= VALID_FRAC_FRAMED;
        float[] quality = annulusQuality(frame, cx, cy, rp, irisR);
        return new Result(pupilFound, cx / w, cy / h, rp, irisR,
                traced.validFrac, quality[0], quality[1], irisPresent, wellFramed);
    }

    // ------------------------------------------------------------ pupil

    private static final class Pupil {
        final float cx;
        final float cy;
        final float r;
        Pupil(float cx, float cy, float r) { this.cx = cx; this.cy = cy; this.r = r; }
    }

    private static Pupil findPupil(IrisFrame frame) {
        int w = frame.width;
        int h = frame.height;
        int gw = PUPIL_GRID_W;
        int gh = Math.max(60, Math.round((float) h / w * gw));
        float[] gray = new float[gw * gh];
        for (int y = 0; y < gh; y++) {
            for (int x = 0; x < gw; x++) {
                gray[y * gw + x] = frame.lumAt(((x + 0.5f) / gw) * w, ((y + 0.5f) / gh) * h);
            }
        }
        int minDimG = Math.min(gw, gh);
        for (int t : PUPIL_THRESHOLDS) {
            byte[] mask = new byte[gw * gh];
            for (int i = 0; i < gray.length; i++) mask[i] = gray[i] < t ? (byte) 1 : 0;
            List<int[]> best = bestBlob(mask, gray, gw, gh, minDimG);
            if (best == null) continue;
            int[] cells = best.get(0);
            // Grow: fill reflection holes at a looser threshold,
            // capped at 2x the core radius (a dark-brown iris must
            // not swallow the pupil whole).
            float coreSum = 0;
            for (int c : cells) coreSum += gray[c];
            float coreMean = coreSum / cells.length;
            float tGrow = Math.min(88f, coreMean + 34f);
            byte[] gmask = new byte[gw * gh];
            for (int i = 0; i < gray.length; i++) gmask[i] = gray[i] < tGrow ? (byte) 1 : 0;
            for (int[] g : components(gmask, gw, gh)) {
                if (g.length > cells.length && contains(g, cells[0])) {
                    if (Math.sqrt(g.length / Math.PI)
                            <= Math.sqrt(cells.length / Math.PI) * 2) {
                        cells = g;
                    }
                    break;
                }
            }
            float sx = 0;
            float sy = 0;
            for (int c : cells) {
                sx += c % gw;
                sy += c / gw;
            }
            float gx = sx / cells.length;
            float gy = sy / cells.length;
            float maxExt = 0;
            for (int c : cells) {
                float d = (float) Math.hypot((c % gw) - gx, (c / gw) - gy);
                if (d > maxExt) maxExt = d;
            }
            float cx = (gx / gw) * w;
            float cy = (gy / gh) * h;
            float rArea = (float) Math.sqrt(cells.length / Math.PI) * (w / (float) gw);
            float rCap = minDimG * 0.17f * (w / (float) gw);
            float r = Math.min(Math.max(rArea, 0.9f * maxExt * (w / (float) gw)), rCap);
            r = refineRadius(frame, cx, cy, r, rCap);
            return new Pupil(cx, cy, r);
        }
        return null;
    }

    /** Best round compact blob in a mask, or null. Returns a
     *  one-element list holding the winning cell array. */
    private static List<int[]> bestBlob(byte[] mask, float[] gray, int gw, int gh, int minDimG) {
        int[] bestCells = null;
        float bestScore = 0;
        for (int[] cells : components(mask, gw, gh)) {
            float[] b = evalBlob(cells, gw, gh, minDimG);
            if (b == null) continue;
            float area = b[0];
            float roundness = b[1];
            float fill = b[2];
            float score = area * (0.4f + 0.6f * roundness) * fill;
            if (bestCells == null || score > bestScore) {
                bestCells = cells;
                bestScore = score;
            }
        }
        if (bestCells == null) return null;
        List<int[]> out = new ArrayList<>(1);
        out.add(bestCells);
        return out;
    }

    /** Blob acceptance, the reader's rules. Returns {area,
     *  roundness, fill} or null. */
    private static float[] evalBlob(int[] cells, int gw, int gh, int minDimG) {
        int area = cells.length;
        float frac = (float) area / (gw * gh);
        if (frac < 0.0008f || frac > 0.06f) return null;
        float sx = 0;
        float sy = 0;
        int minx = gw;
        int maxx = 0;
        int miny = gh;
        int maxy = 0;
        for (int c : cells) {
            int x = c % gw;
            int y = c / gw;
            sx += x;
            sy += y;
            if (x < minx) minx = x;
            if (x > maxx) maxx = x;
            if (y < miny) miny = y;
            if (y > maxy) maxy = y;
        }
        float mx = sx / area / gw;
        float my = sy / area / gh;
        if (mx < 0.15f || mx > 0.85f || my < 0.15f || my > 0.85f) return null;
        int bw = maxx - minx + 1;
        int bh = maxy - miny + 1;
        if ((float) Math.max(bw, bh) / Math.min(bw, bh) > 1.7f) return null;
        float fill = (float) area / (bw * bh);
        if (fill < 0.42f) return null;
        float rCells = (float) Math.sqrt(area / Math.PI);
        if (rCells < minDimG * 0.018f || rCells > minDimG * 0.16f) return null;
        float roundness = (float) Math.min(bw, bh) / Math.max(bw, bh);
        return new float[] { area, roundness, fill };
    }

    /** 4-connected components of a mask. */
    private static List<int[]> components(byte[] mask, int gw, int gh) {
        List<int[]> out = new ArrayList<>();
        boolean[] seen = new boolean[gw * gh];
        int[] stack = new int[gw * gh];
        for (int i = 0; i < mask.length; i++) {
            if (mask[i] == 0 || seen[i]) continue;
            int sp = 0;
            stack[sp++] = i;
            seen[i] = true;
            int count = 0;
            int[] buf = new int[gw * gh];
            while (sp > 0) {
                int cur = stack[--sp];
                buf[count++] = cur;
                int cx = cur % gw;
                int[] nb = {cur - 1, cur + 1, cur - gw, cur + gw};
                for (int n : nb) {
                    if (n < 0 || n >= mask.length || seen[n] || mask[n] == 0) continue;
                    if (Math.abs((n % gw) - cx) > 1) continue;
                    seen[n] = true;
                    stack[sp++] = n;
                }
            }
            out.add(Arrays.copyOf(buf, count));
        }
        return out;
    }

    private static boolean contains(int[] cells, int v) {
        for (int c : cells) if (c == v) return true;
        return false;
    }

    /** Radial-gradient radius refinement (36 rays, jump > 26),
     *  the reader's confirmation of the blob radius. */
    private static float refineRadius(IrisFrame frame, float cx, float cy, float r, float rCap) {
        int nb = 36;
        List<Float> refined = new ArrayList<>();
        for (int k = 0; k < nb; k++) {
            double th = (k / (double) nb) * Math.PI * 2;
            float dx = (float) Math.sin(th);
            float dy = (float) -Math.cos(th);
            int samples = 24;
            float prev = 0;
            for (int s = 1; s <= samples; s++) {
                float rr = (s / (float) samples) * (rCap / 1.15f);
                float g = frame.lumAt(cx + dx * rr, cy + dy * rr);
                if (s > 2 && prev > 0 && g - prev > 26) {
                    refined.add(rr - (rCap / 1.15f) / samples);
                    break;
                }
                prev = g;
            }
        }
        if (refined.size() >= nb * 0.3f) {
            float med = median(refined);
            if (med > 0 && Math.abs(med - r) < r * 0.4f) {
                r = (r + med) / 2;
            }
        }
        return r;
    }

    // ------------------------------------------------------------ iris edge

    private static final class Trace {
        final float validFrac;
        final float r;
        Trace(float validFrac, float r) { this.validFrac = validFrac; this.r = r; }
    }

    private static float edgeScore(IrisFrame frame, float x, float y) {
        return frame.satAt(x, y) * 150f + (255 - frame.lumAt(x, y)) * 0.45f;
    }

    private static Trace traceEdges(IrisFrame frame, float cx, float cy, float rp, float ratio) {
        int minDim = frame.minDim();
        float maxR = minDim * 0.52f;
        float[] edgeR = new float[RAYS];
        boolean[] valid = new boolean[RAYS];
        for (int k = 0; k < RAYS; k++) {
            double th = (k / (double) RAYS) * Math.PI * 2;
            float dx = (float) Math.sin(th);
            float dy = (float) -Math.cos(th);
            float base = 0;
            int bn = 0;
            for (float r = rp * 1.15f; r < rp * 1.65f && r < maxR; r += 1f) {
                base += edgeScore(frame, cx + dx * r, cy + dy * r);
                bn++;
            }
            base = bn > 0 ? base / bn : 0;
            int below = 0;
            for (float r = rp * 1.4f; r < maxR; r += 1f) {
                if (edgeScore(frame, cx + dx * r, cy + dy * r) < base * ratio) {
                    below++;
                    if (below >= 3) {
                        edgeR[k] = r - 2;
                        valid[k] = true;
                        break;
                    }
                } else {
                    below = 0;
                }
            }
        }
        return summarizeTrace(edgeR, valid, rp, maxR, minDim);
    }

    private static Trace traceEdgesGradient(IrisFrame frame, float cx, float cy, float rp) {
        int minDim = frame.minDim();
        float maxR = minDim * 0.52f;
        float[] edgeR = new float[RAYS];
        boolean[] valid = new boolean[RAYS];
        for (int k = 0; k < RAYS; k++) {
            double th = (k / (double) RAYS) * Math.PI * 2;
            float dx = (float) Math.sin(th);
            float dy = (float) -Math.cos(th);
            float bestGrad = Float.NEGATIVE_INFINITY;
            float bestR = 0;
            for (float r = rp * 1.4f; r < maxR; r += 1f) {
                float g0 = edgeScore(frame, cx + dx * (r - 2), cy + dy * (r - 2));
                float g1 = edgeScore(frame, cx + dx * r, cy + dy * r);
                float grad = g0 - g1;
                if (grad > bestGrad) {
                    bestGrad = grad;
                    bestR = r - 2;
                }
            }
            if (bestGrad > 12) {
                edgeR[k] = bestR;
                valid[k] = true;
            }
        }
        return summarizeTrace(edgeR, valid, rp, maxR, minDim);
    }

    private static Trace summarizeTrace(float[] edgeR, boolean[] valid, float rp,
                                        float maxR, int minDim) {
        List<Float> radii = new ArrayList<>();
        for (int k = 0; k < RAYS; k++) if (valid[k]) radii.add(edgeR[k]);
        float vf = (float) radii.size() / RAYS;
        float r = radii.size() >= 12 ? median(radii) : minDim * 0.3f;
        r = Math.min(Math.max(r, rp * 1.9f), maxR);
        return new Trace(vf, r);
    }

    // ------------------------------------------------------------ quality

    /** Annulus sharpness + mean luminance, the reader's photo
     *  quality check (section 7): samples 1.17% of the width
     *  apart, annulus 0.3 < r/R <= 1.0. Returns {sharpness,
     *  meanLum}. */
    private static float[] annulusQuality(IrisFrame frame, float cx, float cy,
                                          float rp, float irisR) {
        int w = frame.width;
        int h = frame.height;
        int qs = Math.max(1, Math.round(w * 0.0117f));
        int qm = Math.round(w * 0.02f);
        float gradSum = 0;
        int gradN = 0;
        float lumSum = 0;
        int lumN = 0;
        for (int y = qm; y < h - qm; y += qs) {
            for (int x = qm; x < w - qm; x += qs) {
                float rr = (float) Math.hypot(x - cx, y - cy) / Math.max(1f, irisR);
                if (rr > 1f || rr < 0.3f) continue;
                float g = frame.lumAt(x, y);
                lumSum += g;
                lumN++;
                float g2 = frame.lumAt(x + qs, y);
                gradSum += Math.abs(g2 - g);
                gradN++;
            }
        }
        float sharpness = gradN > 0 ? gradSum / gradN : 0f;
        float meanLum = lumN > 0 ? lumSum / lumN : 0f;
        return new float[] { sharpness, meanLum };
    }

    private static float median(List<Float> vals) {
        if (vals.isEmpty()) return 0f;
        List<Float> s = new ArrayList<>(vals);
        s.sort(Float::compareTo);
        return s.get(s.size() / 2);
    }
}
