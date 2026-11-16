package app.eyeknow.android;

import java.util.List;

/**
 * Which camera to open for an iris close-up, and what to honestly
 * call it. Pure Java over small structs the Android layer fills
 * from CameraCharacteristics — so the harness proves the exact
 * shipping selection rule.
 *
 * Android exposes no "macro" flag. The rule below is the honest
 * one: among back-facing cameras, prefer the one that focuses
 * CLOSEST (largest LENS_INFO_MINIMUM_FOCUS_DISTANCE in diopters).
 * A camera is labelled "Macro" only when it is a distinct physical
 * lens that focuses meaningfully closer than the main back camera
 * (at least 2 diopters closer, i.e. its near plane is clearly
 * nearer). Otherwise the label is "Main camera" with close focus,
 * and the UI never claims macro. Front cameras are a last resort
 * (no torch, worse optics for this job) and are labelled as such.
 *
 * minimumFocusDistance: diopters, 0 = fixed focus / unknown.
 * A null focal length list means unknown.
 */
public final class CameraChoice {

    public static final class Lens {
        public final String id;
        public final boolean backFacing;
        public final float minFocusDistance; // diopters, 0 = fixed/unknown
        public final boolean logicalMulti;   // logical multi-camera id
        public final String[] physicalIds;   // physical ids if logical

        public Lens(String id, boolean backFacing, float minFocusDistance,
                    boolean logicalMulti, String[] physicalIds) {
            this.id = id;
            this.backFacing = backFacing;
            this.minFocusDistance = minFocusDistance;
            this.logicalMulti = logicalMulti;
            this.physicalIds = physicalIds;
        }
    }

    public static final class Choice {
        public final String cameraId;
        /** "Macro", "Main", or "Front" — the honest UI label. */
        public final String label;
        /** Human reason, shown in the camera UI's status line. */
        public final String reason;

        Choice(String cameraId, String label, String reason) {
            this.cameraId = cameraId;
            this.label = label;
            this.reason = reason;
        }
    }

    /** Diopters closer than the main lens a distinct lens must focus
     *  to earn the "Macro" label. 2 diopters ≈ the near plane moving
     *  from ~50cm to ~14cm or nearer — a real difference, not noise. */
    static final float MACRO_EDGE_DIOPTERS = 2.0f;

    private CameraChoice() {}

    /**
     * Pick a camera. Returns null when the list is empty (the caller
     * reports "no camera" honestly). Physical ids of a logical
     * camera are candidates too — callers pass them as their own
     * Lens entries with the same facing.
     */
    public static Choice choose(List<Lens> lenses) {
        if (lenses == null || lenses.isEmpty()) return null;
        Lens bestBack = null;
        Lens mainBack = null; // the back lens with the longest reach = main
        for (Lens l : lenses) {
            if (!l.backFacing) continue;
            if (bestBack == null || l.minFocusDistance > bestBack.minFocusDistance) {
                bestBack = l;
            }
            if (mainBack == null || l.minFocusDistance < mainBack.minFocusDistance) {
                mainBack = l;
            }
        }
        if (bestBack != null) {
            boolean distinctMacro = mainBack != null && bestBack != mainBack
                    && (bestBack.minFocusDistance - mainBack.minFocusDistance)
                            >= MACRO_EDGE_DIOPTERS;
            if (distinctMacro) {
                return new Choice(bestBack.id, "Macro",
                        "Macro lens — focuses closer than the main camera.");
            }
            return new Choice(bestBack.id, "Main",
                    bestBack.minFocusDistance > 0
                            ? "Main camera — closest focus available on this phone."
                            : "Main camera — fixed focus; hold steady at close range.");
        }
        // No back camera at all: first front lens, labelled honestly.
        for (Lens l : lenses) {
            if (!l.backFacing) {
                return new Choice(l.id, "Front",
                        "Front camera — no rear camera found; no flash available.");
            }
        }
        return null;
    }

    /** Minimum focus distance as a near-plane distance in cm, for
     *  display. Returns -1 when unknown/fixed. */
    public static int nearPlaneCm(float minFocusDistanceDiopters) {
        if (minFocusDistanceDiopters <= 0) return -1;
        return Math.round(100.0f / minFocusDistanceDiopters);
    }
}
