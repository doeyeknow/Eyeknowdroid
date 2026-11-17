package app.eyeknow.android;

/**
 * Diagnostics for one auto-crop attempt (v1.4): the mapping
 * inputs, the cut rect, and the verification outcome — numbers
 * only, never photo bytes, never transmitted anywhere. Only the
 * LAST attempt is kept (the activity overwrites the stored
 * record on every auto attempt).
 *
 * Pure Java: the record, its storage encoding, and the
 * plain-words summaries the fallback screen and the camera
 * screen show. The activity owns persistence (one
 * SharedPreferences string) and the UI surfaces; this class
 * owns the shape and the wording so the harness proves them.
 *
 * The record exists to make the next failure visible: if the
 * rotation mapping is inverted on real hardware, the pattern
 * (firing fractions + JPEG orientation vs. verification
 * outcome, across attempts) shows up here instead of in a
 * silently bad photo.
 */
public final class CropDiagnostics {

    /** How an auto-crop attempt ended. */
    public enum Outcome {
        /** Verified and submitted. */
        SUBMITTED,
        /** The mapping could not be computed safely. */
        MAPPING_FAILED,
        /** The crop encode failed. */
        ENCODE_FAILED,
        /** Verification rejected the cut crop. */
        VERIFY_FAILED,
        /** Verification could not run (no frame / error). */
        VERIFY_ERROR
    }

    /** One attempt. All fields are plain numbers/strings; no
     *  photo bytes, no paths. -1 / NaN marks "unknown". */
    public static final class Record {
        public final String cameraId;
        public final String cameraLabel;
        public final int analysisW;
        public final int analysisH;
        public final int stillW;
        public final int stillH;
        public final int jpegOrientation;
        public final float fireCx;
        public final float fireCy;
        public final float fireIrisR;
        public final int fireFrameW;
        public final int fireFrameH;
        public final int rectLeft;
        public final int rectTop;
        public final int rectSize;
        public final int jpegW;
        public final int jpegH;
        public final Outcome outcome;
        public final CropVerify.Reason verifyReason;
        /** Center-trace measurements on the cut crop (NaN when
         *  the trace did not run). The spread is recorded for
         *  pattern analysis; verification never gates on it. */
        public final float measuredValidFrac;
        public final float measuredIrisFrac;
        public final float measuredSpread;
        /** Verification wall time in milliseconds, -1 when it
         *  did not run. */
        public final long verifyMs;

        public Record(String cameraId, String cameraLabel,
                      int analysisW, int analysisH, int stillW, int stillH,
                      int jpegOrientation,
                      float fireCx, float fireCy, float fireIrisR,
                      int fireFrameW, int fireFrameH,
                      int rectLeft, int rectTop, int rectSize,
                      int jpegW, int jpegH,
                      Outcome outcome, CropVerify.Reason verifyReason,
                      float measuredValidFrac, float measuredIrisFrac,
                      float measuredSpread,
                      long verifyMs) {
            this.cameraId = cameraId == null ? "" : cameraId;
            this.cameraLabel = cameraLabel == null ? "" : cameraLabel;
            this.analysisW = analysisW;
            this.analysisH = analysisH;
            this.stillW = stillW;
            this.stillH = stillH;
            this.jpegOrientation = jpegOrientation;
            this.fireCx = fireCx;
            this.fireCy = fireCy;
            this.fireIrisR = fireIrisR;
            this.fireFrameW = fireFrameW;
            this.fireFrameH = fireFrameH;
            this.rectLeft = rectLeft;
            this.rectTop = rectTop;
            this.rectSize = rectSize;
            this.jpegW = jpegW;
            this.jpegH = jpegH;
            this.outcome = outcome;
            this.verifyReason = verifyReason;
            this.measuredValidFrac = measuredValidFrac;
            this.measuredIrisFrac = measuredIrisFrac;
            this.measuredSpread = measuredSpread;
            this.verifyMs = verifyMs;
        }
    }

    private CropDiagnostics() {}

    // ------------------------------------------------------------ encoding

    /** Storage encoding: one line of key=value pairs, ";" -
     *  separated. Numbers only, fixed keys, parseable without
     *  any JSON dependency (the harness runs on plain Java).
     *  Unknown/missing keys decode to the "unknown" sentinels,
     *  never a crash. */
    public static String encode(Record r) {
        StringBuilder b = new StringBuilder();
        kv(b, "cam", r.cameraId);
        kv(b, "label", r.cameraLabel);
        kv(b, "aw", r.analysisW);
        kv(b, "ah", r.analysisH);
        kv(b, "sw", r.stillW);
        kv(b, "sh", r.stillH);
        kv(b, "rot", r.jpegOrientation);
        kv(b, "fx", r.fireCx);
        kv(b, "fy", r.fireCy);
        kv(b, "fr", r.fireIrisR);
        kv(b, "fw", r.fireFrameW);
        kv(b, "fh", r.fireFrameH);
        kv(b, "rl", r.rectLeft);
        kv(b, "rt", r.rectTop);
        kv(b, "rs", r.rectSize);
        kv(b, "jw", r.jpegW);
        kv(b, "jh", r.jpegH);
        kv(b, "out", r.outcome == null ? "" : r.outcome.name());
        kv(b, "vr", r.verifyReason == null ? "" : r.verifyReason.name());
        kv(b, "mv", r.measuredValidFrac);
        kv(b, "mi", r.measuredIrisFrac);
        kv(b, "sp", r.measuredSpread);
        kv(b, "ms", r.verifyMs);
        return b.toString();
    }

    private static void kv(StringBuilder b, String key, String value) {
        if (b.length() > 0) b.append(';');
        b.append(key).append('=').append(value == null ? "" : value.replace(";", ","));
    }

    private static void kv(StringBuilder b, String key, int value) {
        kv(b, key, Integer.toString(value));
    }

    private static void kv(StringBuilder b, String key, long value) {
        kv(b, key, Long.toString(value));
    }

    private static void kv(StringBuilder b, String key, float value) {
        kv(b, key, Float.isNaN(value) ? "" : Float.toString(value));
    }

    /** Decode a stored line, or null when it is not a record at
     *  all (empty, garbage). Partial records decode with
     *  sentinels for the missing fields. */
    public static Record decode(String encoded) {
        if (encoded == null || encoded.trim().isEmpty()) return null;
        String cam = "";
        String label = "";
        int aw = -1, ah = -1, sw = -1, sh = -1, rot = -1;
        float fx = Float.NaN, fy = Float.NaN, fr = Float.NaN;
        int fw = -1, fh = -1;
        int rl = -1, rt = -1, rs = -1, jw = -1, jh = -1;
        Outcome out = null;
        CropVerify.Reason vr = null;
        float mv = Float.NaN, mi = Float.NaN, sp = Float.NaN;
        long ms = -1;
        boolean anyKey = false;
        for (String part : encoded.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            anyKey = true;
            String k = part.substring(0, eq);
            String v = part.substring(eq + 1);
            try {
                switch (k) {
                    case "cam": cam = v; break;
                    case "label": label = v; break;
                    case "aw": aw = Integer.parseInt(v); break;
                    case "ah": ah = Integer.parseInt(v); break;
                    case "sw": sw = Integer.parseInt(v); break;
                    case "sh": sh = Integer.parseInt(v); break;
                    case "rot": rot = Integer.parseInt(v); break;
                    case "fx": fx = v.isEmpty() ? Float.NaN : Float.parseFloat(v); break;
                    case "fy": fy = v.isEmpty() ? Float.NaN : Float.parseFloat(v); break;
                    case "fr": fr = v.isEmpty() ? Float.NaN : Float.parseFloat(v); break;
                    case "fw": fw = Integer.parseInt(v); break;
                    case "fh": fh = Integer.parseInt(v); break;
                    case "rl": rl = Integer.parseInt(v); break;
                    case "rt": rt = Integer.parseInt(v); break;
                    case "rs": rs = Integer.parseInt(v); break;
                    case "jw": jw = Integer.parseInt(v); break;
                    case "jh": jh = Integer.parseInt(v); break;
                    case "out": out = Outcome.valueOf(v); break;
                    case "vr": vr = CropVerify.Reason.valueOf(v); break;
                    case "mv": mv = v.isEmpty() ? Float.NaN : Float.parseFloat(v); break;
                    case "mi": mi = v.isEmpty() ? Float.NaN : Float.parseFloat(v); break;
                    case "sp": sp = v.isEmpty() ? Float.NaN : Float.parseFloat(v); break;
                    case "ms": ms = Long.parseLong(v); break;
                    default: break; // forward-compatible: ignore new keys
                }
            } catch (Exception ignored) {
                // One bad value must not sink the whole record.
            }
        }
        if (!anyKey || out == null) return null;
        return new Record(cam, label, aw, ah, sw, sh, rot,
                fx, fy, fr, fw, fh, rl, rt, rs, jw, jh,
                out, vr, mv, mi, sp, ms);
    }

    // ------------------------------------------------------------ wording

    /** One plain-words line for the manual crop screen when an
     *  auto attempt fell back: what was measured, in words.
     *  Never empty; never blames the user. */
    public static String fallbackSummary(Record r) {
        if (r == null) {
            return "The auto-crop didn't frame the iris well — frame it yourself.";
        }
        switch (r.outcome) {
            case MAPPING_FAILED:
                return "The auto-crop couldn't map the detection to this photo — "
                        + "frame the iris yourself.";
            case ENCODE_FAILED:
                return "The auto-crop couldn't cut the crop — frame the iris yourself.";
            case VERIFY_ERROR:
                return "The auto-crop check couldn't run — frame the iris yourself.";
            case VERIFY_FAILED:
                return verifyFailureWords(r);
            case SUBMITTED:
            default:
                return "The auto-crop didn't frame the iris well — frame it yourself.";
        }
    }

    private static String verifyFailureWords(Record r) {
        CropVerify.Reason reason = r.verifyReason;
        if (reason == CropVerify.Reason.EDGE_WEAK) {
            String traced = Float.isNaN(r.measuredValidFrac) ? ""
                    : " (only " + Math.round(r.measuredValidFrac * 100) + "% of the edge traced)";
            return "The auto-crop check found the iris cut off or off-center in the crop"
                    + traced + " — frame the iris yourself.";
        }
        if (reason == CropVerify.Reason.BAD_RADIUS && !Float.isNaN(r.measuredIrisFrac)) {
            int pct = Math.round(r.measuredIrisFrac * 100);
            if (r.measuredIrisFrac > CropVerify.MAX_IRIS_FRAC) {
                return "The auto-crop check found the iris filling " + pct
                        + "% of the crop — cut off at the edges, so frame the iris yourself.";
            }
            return "The auto-crop check found the iris filling only " + pct
                    + "% of the crop — frame the iris yourself.";
        }
        return "The auto-crop didn't frame the iris well — frame it yourself.";
    }

    /** One compact line for the camera screen: the last
     *  attempt's outcome + the measurement that decided it. */
    public static String lastCheckLine(Record r) {
        if (r == null) return "";
        StringBuilder b = new StringBuilder("Last auto-crop check: ");
        switch (r.outcome) {
            case SUBMITTED:
                b.append("passed");
                if (!Float.isNaN(r.measuredValidFrac)) {
                    b.append(", edge traced ").append(Math.round(r.measuredValidFrac * 100))
                            .append("%");
                }
                break;
            case MAPPING_FAILED:
                b.append("couldn't map the detection");
                break;
            case ENCODE_FAILED:
                b.append("couldn't cut the crop");
                break;
            case VERIFY_ERROR:
                b.append("check couldn't run");
                break;
            case VERIFY_FAILED:
                b.append("failed — ");
                if (r.verifyReason == CropVerify.Reason.EDGE_WEAK) {
                    b.append("iris edge traced only ")
                            .append(Float.isNaN(r.measuredValidFrac) ? "?"
                                    : Math.round(r.measuredValidFrac * 100) + "%");
                } else if (r.verifyReason == CropVerify.Reason.BAD_RADIUS
                        && !Float.isNaN(r.measuredIrisFrac)) {
                    b.append("iris ").append(Math.round(r.measuredIrisFrac * 100))
                            .append("% of the crop");
                } else {
                    b.append("framing check failed");
                }
                break;
            default:
                b.append("unknown");
                break;
        }
        if (r.verifyMs >= 0) b.append(" · ").append(r.verifyMs).append("ms");
        return b.toString();
    }

    /** Detail lines for a diagnostics view: every recorded
     *  number, labeled. The activity may show these verbatim. */
    public static String detailText(Record r) {
        if (r == null) return "No auto-crop attempt recorded yet.";
        StringBuilder b = new StringBuilder();
        line(b, "Camera", r.cameraLabel.isEmpty() ? r.cameraId
                : r.cameraLabel + " (" + r.cameraId + ")");
        line(b, "Analysis frame", dim(r.analysisW, r.analysisH));
        line(b, "Still size", dim(r.stillW, r.stillH));
        line(b, "JPEG orientation", r.jpegOrientation < 0 ? "?" : r.jpegOrientation + "°");
        line(b, "Firing detection", frac3(r.fireCx) + ", " + frac3(r.fireCy)
                + " r=" + (Float.isNaN(r.fireIrisR) ? "?" : Math.round(r.fireIrisR) + "px")
                + " in " + dim(r.fireFrameW, r.fireFrameH));
        line(b, "Cut rect", r.rectSize < 0 ? "none"
                : r.rectLeft + "," + r.rectTop + " " + r.rectSize + "px in "
                        + dim(r.jpegW, r.jpegH));
        line(b, "Outcome", r.outcome == null ? "?" : r.outcome.name());
        line(b, "Verification", r.verifyReason == null ? "did not run" : r.verifyReason.name()
                + (Float.isNaN(r.measuredValidFrac) ? "" : ", edge trace "
                        + Math.round(r.measuredValidFrac * 100) + "%")
                + (Float.isNaN(r.measuredIrisFrac) ? "" : ", iris "
                        + Math.round(r.measuredIrisFrac * 100) + "% of crop")
                + (Float.isNaN(r.measuredSpread) ? "" : ", radius spread "
                        + String.format(java.util.Locale.US, "%.2f", r.measuredSpread))
                + (r.verifyMs < 0 ? "" : ", " + r.verifyMs + "ms"));
        return b.toString().trim();
    }

    private static void line(StringBuilder b, String label, String value) {
        if (b.length() > 0) b.append('\n');
        b.append(label).append(": ").append(value);
    }

    private static String dim(int w, int h) {
        return (w <= 0 || h <= 0) ? "?" : w + "×" + h;
    }

    private static String frac3(float v) {
        if (Float.isNaN(v)) return "?";
        return String.format(java.util.Locale.US, "%.3f", v);
    }
}
