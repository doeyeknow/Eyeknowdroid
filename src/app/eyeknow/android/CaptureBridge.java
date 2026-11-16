package app.eyeknow.android;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.lang.ref.WeakReference;

/**
 * The native half of Adam's nativeCapture.ts contract.
 *
 * Why a JS shim: the site assigns bridge.onmessage and calls
 * bridge.postMessage on window.EyeknowCapture. A Java object
 * injected with addJavascriptInterface cannot hold a JS function
 * property (onmessage) — the assignment would be lost. So the
 * native side injects a plain JS object as window.EyeknowCapture
 * whose postMessage forwards into this @JavascriptInterface
 * object (exposed under the separate name EyeknowNative), and
 * whose onmessage is a normal JS property the site owns. Replies
 * go back through evaluateJavascript calling that onmessage with
 * {data: "<reply JSON string>"} — the site does JSON.parse on
 * event.data, so data must be a string.
 *
 * The shim is (re)injected on every page finish. It is only ever
 * injected into the trusted site: MainActivity locks navigation
 * to eyeknow.app / www.eyeknow.app and opens everything else in
 * the browser.
 */
public final class CaptureBridge {

    /** Name of the Java object in JS. Deliberately NOT
     *  "EyeknowCapture" — that name belongs to the shim. */
    public static final String NATIVE_NAME = "EyeknowNative";

    /** The shim source. Kept dependency-free and idempotent. */
    public static final String SHIM =
            "window.EyeknowCapture = {"
            + "postMessage: function(m) { window." + NATIVE_NAME + ".postMessage(m); },"
            + "onmessage: null"
            + "};";

    public interface Host {
        /** The site asked for a capture. Runs on a WebView thread —
         *  the host must hop to the main thread itself. */
        void onCaptureRequested(String requestId, String eye);
        /** The site cancelled (its 180s timeout fired). */
        void onCaptureCancelled(String requestId);
    }

    private final WeakReference<WebView> webViewRef;
    private final Host host;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public CaptureBridge(WebView webView, Host host) {
        this.webViewRef = new WeakReference<>(webView);
        this.host = host;
    }

    /** Inject (or re-inject) the shim. Safe to call repeatedly. */
    public void injectShim() {
        WebView w = webViewRef.get();
        if (w == null) return;
        mainHandler.post(() -> {
            WebView v = webViewRef.get();
            if (v != null) v.evaluateJavascript(SHIM, null);
        });
    }

    @JavascriptInterface
    public void postMessage(String message) {
        BridgeMessages.Request req = BridgeMessages.parseRequest(message);
        if (req == null) return; // unknown shape: ignore, never crash
        if (req.isCapture()) {
            mainHandler.post(() -> host.onCaptureRequested(req.requestId, req.eye));
        } else if (req.isCancel()) {
            mainHandler.post(() -> host.onCaptureCancelled(req.requestId));
        }
    }

    /** Deliver a reply JSON string to the site's onmessage. */
    public void deliver(String replyJson) {
        WebView w = webViewRef.get();
        if (w == null) return;
        String script = BridgeMessages.deliveryScript(replyJson);
        mainHandler.post(() -> {
            WebView v = webViewRef.get();
            if (v != null) v.evaluateJavascript(script, null);
        });
    }

    public void deliverCaptured(String requestId, String displayDataUrl) {
        if (!BridgeMessages.isValidDisplay(displayDataUrl)) {
            deliverError(requestId, "The camera returned an invalid photo.");
            return;
        }
        deliver(BridgeMessages.capturedReply(requestId, displayDataUrl, 0, "still"));
    }

    public void deliverCancelled(String requestId) {
        deliver(BridgeMessages.cancelledReply(requestId));
    }

    public void deliverError(String requestId, String message) {
        deliver(BridgeMessages.errorReply(requestId, message));
    }
}
