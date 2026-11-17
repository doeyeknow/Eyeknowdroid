package app.eyeknow.android;

/**
 * The native-capture bridge wire format, as defined by Adam's
 * lib/nativeCapture.ts in the Eyeknow repo. Pure Java — no Android
 * types — so the harness proves the exact shipping parse/build code.
 *
 * Web -> native (bridge.postMessage):
 *   {"type":"captureEye","requestId":"<uuid>","eye":"left"|"right"}
 *   {"type":"cancelEyeCapture","requestId":"<uuid>"}
 *
 * Native -> web (bridge.onmessage, event.data is a JSON string):
 *   {"requestId":"<uuid>","status":"captured",
 *    "capture":{"display":"data:image/jpeg;base64,...",
 *               "stackedFrames":0,"source":"still"}}
 *   {"requestId":"<uuid>","status":"cancelled"}
 *   {"requestId":"<uuid>","status":"error","message":"<reason>"}
 *
 * The web side accepts a capture only when display is a string
 * starting with "data:image/jpeg;base64,". A single still reports
 * stackedFrames 0 (the capture.ts convention: 0 = single frame) and
 * omits "analysis" — the reader then analyses the display image
 * itself. Nothing here invents frames that were not captured.
 */
public final class BridgeMessages {

    public static final String TYPE_CAPTURE_EYE = "captureEye";
    public static final String TYPE_CANCEL = "cancelEyeCapture";

    public static final String STATUS_CAPTURED = "captured";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_ERROR = "error";

    public static final String DISPLAY_PREFIX = "data:image/jpeg;base64,";

    private BridgeMessages() {}

    /** Parsed inbound request, or null when the message is not one
     *  of the two known shapes. Unknown types return null so the
     *  caller ignores them instead of crashing. */
    public static final class Request {
        public final String type;
        public final String requestId;
        public final String eye; // "left"|"right" for captureEye, else ""

        Request(String type, String requestId, String eye) {
            this.type = type;
            this.requestId = requestId;
            this.eye = eye;
        }

        public boolean isCapture() { return TYPE_CAPTURE_EYE.equals(type); }
        public boolean isCancel() { return TYPE_CANCEL.equals(type); }
    }

    public static Request parseRequest(String message) {
        if (message == null) return null;
        String type = stringField(message, "type");
        String requestId = stringField(message, "requestId");
        if (type == null || requestId == null || requestId.isEmpty()) return null;
        if (TYPE_CAPTURE_EYE.equals(type)) {
            String eye = stringField(message, "eye");
            if (!"left".equals(eye) && !"right".equals(eye)) return null;
            return new Request(type, requestId, eye);
        }
        if (TYPE_CANCEL.equals(type)) {
            return new Request(type, requestId, "");
        }
        return null;
    }

    /** Minimal string-field extraction for the flat request objects
     *  the web side sends. Handles \" and \\ escapes. Returns null
     *  when the field is absent or not a JSON string. */
    static String stringField(String json, String field) {
        String key = "\"" + field + "\"";
        int ki = json.indexOf(key);
        if (ki < 0) return null;
        int ci = json.indexOf(':', ki + key.length());
        if (ci < 0) return null;
        int i = ci + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;
        StringBuilder out = new StringBuilder();
        i++;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                if (n == '"' || n == '\\' || n == '/') { out.append(n); i += 2; continue; }
                if (n == 'n') { out.append('\n'); i += 2; continue; }
                if (n == 't') { out.append('\t'); i += 2; continue; }
                if (n == 'r') { out.append('\r'); i += 2; continue; }
                if (n == 'u' && i + 5 < json.length()) {
                    try {
                        out.append((char) Integer.parseInt(json.substring(i + 2, i + 6), 16));
                        i += 6; continue;
                    } catch (NumberFormatException ignored) { return null; }
                }
                return null;
            }
            if (c == '"') return out.toString();
            out.append(c);
            i++;
        }
        return null;
    }

    /** JSON string literal (double-quoted, escaped) for embedding a
     *  value in a reply or in the evaluateJavascript call. */
    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 2);
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    public static String capturedReply(String requestId, String displayDataUrl,
                                       int stackedFrames, String source) {
        return "{\"requestId\":" + quote(requestId)
                + ",\"status\":" + quote(STATUS_CAPTURED)
                + ",\"capture\":{\"display\":" + quote(displayDataUrl)
                + ",\"stackedFrames\":" + stackedFrames
                + ",\"source\":" + quote(source) + "}}";
    }

    public static String cancelledReply(String requestId) {
        return "{\"requestId\":" + quote(requestId)
                + ",\"status\":" + quote(STATUS_CANCELLED) + "}";
    }

    public static String errorReply(String requestId, String message) {
        return "{\"requestId\":" + quote(requestId)
                + ",\"status\":" + quote(STATUS_ERROR)
                + ",\"message\":" + quote(message) + "}";
    }

    /** The exact check the web side applies before accepting a
     *  capture. The native side validates before replying so a bad
     *  photo becomes an honest error, never a confusing rejection. */
    public static boolean isValidDisplay(String displayDataUrl) {
        return displayDataUrl != null && displayDataUrl.startsWith(DISPLAY_PREFIX)
                && displayDataUrl.length() > DISPLAY_PREFIX.length();
    }

    /** The JavaScript the native side evaluates to deliver a reply:
     *  calls the shim's onmessage with {data: "<reply JSON string>"}.
     *  The web side does JSON.parse(event.data), so data must be a
     *  string holding JSON, not an object. */
    public static String deliveryScript(String replyJson) {
        return "window.EyeknowCapture && window.EyeknowCapture.onmessage"
                + " && window.EyeknowCapture.onmessage({data:" + quote(replyJson) + "});";
    }

    /** View scale (v1.7, Adam's order 2026-11-17: "Set the view to
     *  0.7"). Applied as a CSS zoom on the ROOT element, so the
     *  initial containing block resizes: content reflows to the
     *  wider effective width and fills the screen at 70%, instead
     *  of shrinking inside a full-size viewport. */
    public static final String VIEW_ZOOM = "0.7";

    /** The script injected on every page finish: installs the
     *  window.EyeknowCapture shim (unchanged contract — the
     *  postMessage forward and the null onmessage the site
     *  owns) and then applies the root-element zoom. One
     *  script, one evaluateJavascript call: the shim install
     *  comes first so the bridge never depends on the zoom,
     *  and re-injection is idempotent (same shim, same zoom
     *  value) on every navigation. Pure Java so the harness
     *  pins the exact shipping string; CaptureBridge is the
     *  only caller and evaluates exactly what this returns.
     *  @param nativeName the @JavascriptInterface object name
     *         the shim forwards postMessage into. */
    public static String injectionScript(String nativeName) {
        return "window.EyeknowCapture = {"
                + "postMessage: function(m) { window." + nativeName + ".postMessage(m); },"
                + "onmessage: null"
                + "};"
                + "document.documentElement.style.zoom = '" + VIEW_ZOOM + "';";
    }
}
