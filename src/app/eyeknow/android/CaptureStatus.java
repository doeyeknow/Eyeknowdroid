package app.eyeknow.android;

/**
 * The capture-phase status sequence (v1.4). Pure Java — the
 * harness pins the exact strings and the rule that matters.
 *
 * The rule: from the moment the gate fires until the still
 * bytes are saved, the status must tell the user to HOLD STILL
 * and must never claim the photo is captured. v1.2 set
 * "Captured." when the GATE fired — before the still was even
 * requested — so the user relaxed and moved while the shutter
 * was still to come; at macro distance a small move shifts the
 * frame a lot, and the auto-crop then framed where the eye HAD
 * been. The sequence is now:
 *
 *   FIRING -> CAPTURING -> SAVED -> VERIFYING -> SUBMITTING
 *   (hold still)  (hold still)  (saved)  (checking)  (sending)
 *
 * The gate's search states (looking / found / focusing /
 * hold-steady count) are NOT part of this sequence and keep
 * their own strings in the activity; this class owns only the
 * statuses from the fire onward. The manual shutter shares the
 * CAPTURING text (it already said "hold still" — the flaw was
 * auto-only) and lands on the crop screen's own status once
 * the photo is saved.
 */
public final class CaptureStatus {

    /** Phases of one capture, from the fire onward. */
    public enum Phase {
        /** The gate fired; the still has not been requested yet. */
        FIRING,
        /** The still was requested; its bytes are not saved yet. */
        CAPTURING,
        /** The still bytes are on disk. */
        SAVED,
        /** The cut auto-crop is being re-checked (auto path). */
        VERIFYING,
        /** The verified crop is being handed to the bridge. */
        SUBMITTING
    }

    private CaptureStatus() {}

    /** The exact status string for a phase. A null phase
     *  fails safe to the hold-still text — the status line
     *  must never crash, and must never claim a capture it
     *  cannot name. */
    public static String text(Phase phase) {
        if (phase == null) return "Hold still — capturing…";
        switch (phase) {
            case FIRING:
            case CAPTURING:
                return "Hold still — capturing…";
            case SAVED:
                return "Photo saved — preparing…";
            case VERIFYING:
                return "Checking the framing…";
            case SUBMITTING:
                return "Submitting…";
            default:
                return "";
        }
    }

    /**
     * Whether a phase may claim the photo is captured (words
     * like "captured", "taken", "saved"). Only SAVED and later
     * may — FIRING and CAPTURING must hold still instead. The
     * harness asserts text() honors this for every phase.
     */
    public static boolean mayClaimCaptured(Phase phase) {
        return phase == Phase.SAVED
                || phase == Phase.VERIFYING
                || phase == Phase.SUBMITTING;
    }
}
