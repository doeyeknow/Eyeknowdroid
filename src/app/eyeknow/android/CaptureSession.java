package app.eyeknow.android;

/**
 * The one in-flight native capture. The web side enforces "one at
 * a time" itself (captureInProgress in nativeCapture.ts); this is
 * the native mirror so a second request, a timeout-cancel, or a
 * stray reply can never corrupt the flow.
 *
 * Cancel path: the web side sends cancelEyeCapture on its 180s
 * timeout. MainActivity marks the session cancelled and finishes
 * the camera activity; the camera activity also checks the flag
 * before delivering a result.
 */
public final class CaptureSession {

    public interface CancelListener {
        void onCancelRequested(String requestId);
    }

    private static String activeRequestId;
    private static String activeEye = "";
    private static boolean cancelled;
    private static CancelListener cancelListener;

    private CaptureSession() {}

    public static synchronized boolean begin(String requestId, String eye) {
        if (activeRequestId != null) return false;
        activeRequestId = requestId;
        activeEye = eye;
        cancelled = false;
        return true;
    }

    public static synchronized void end() {
        activeRequestId = null;
        activeEye = "";
        cancelled = false;
        cancelListener = null;
    }

    public static synchronized String activeRequestId() {
        return activeRequestId;
    }

    public static synchronized String activeEye() {
        return activeEye;
    }

    public static synchronized boolean isActive(String requestId) {
        return requestId != null && requestId.equals(activeRequestId);
    }

    public static synchronized boolean isCancelled() {
        return cancelled;
    }

    /** Returns true when this cancel matched the active request. */
    public static synchronized boolean cancel(String requestId) {
        if (!isActive(requestId)) return false;
        cancelled = true;
        CancelListener l = cancelListener;
        if (l != null) l.onCancelRequested(requestId);
        return true;
    }

    public static synchronized void setCancelListener(CancelListener l) {
        cancelListener = l;
    }
}
