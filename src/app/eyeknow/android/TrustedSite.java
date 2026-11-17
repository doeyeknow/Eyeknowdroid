package app.eyeknow.android;

import java.net.URI;

/**
 * The one trust rule for eyeKnow URLs, shared by the WebView's
 * navigation guard and the deep-link entry point (v1.4). A URL
 * is trusted when it is https, on exactly eyeknow.app or
 * www.eyeknow.app, with no explicit port or port 443. Anything
 * else is untrusted and never loads inside the shell.
 *
 * Pure Java (java.net.URI only) — the harness proves the exact
 * shipping rule, including that a referral link's query string
 * survives byte-for-byte.
 *
 * Why this class exists: an affiliate link tapped anywhere used
 * to open in the browser, where the referral landed in the
 * browser's cookie jar — and the app's WebView, a separate jar,
 * never saw it. Now the link opens in the app (manifest
 * intent-filter), and {@link #startUrl} hands the FULL link
 * URL to the WebView so the page captures the referral in the
 * WebView's own storage. The URL is returned VERBATIM — never
 * stripped, rebuilt, or re-encoded — because the ?ref= code
 * must survive byte-for-byte.
 */
public final class TrustedSite {

    public static final String APEX_HOST = "eyeknow.app";
    public static final String WWW_HOST = "www.eyeknow.app";

    private TrustedSite() {}

    /** The trust rule on parsed components. Scheme and host
     *  match exactly (case-sensitive), port must be absent
     *  (-1) or 443. */
    public static boolean isTrusted(String scheme, String host, int port) {
        if (!"https".equals(scheme)) return false;
        if (!APEX_HOST.equals(host) && !WWW_HOST.equals(host)) return false;
        return port == -1 || port == 443;
    }

    /** The trust rule on a parsed URI. A null URI is untrusted. */
    public static boolean isTrustedUri(URI uri) {
        if (uri == null) return false;
        return isTrusted(uri.getScheme(), uri.getHost(), uri.getPort());
    }

    /**
     * The URL the shell should load for an incoming deep link.
     * Returns the link URL VERBATIM when it parses to a trusted
     * site; returns {@code homeUrl} when the link is absent,
     * malformed, or untrusted. An untrusted URL is never
     * returned, so it can never reach the WebView.
     */
    public static String startUrl(String linkUrl, String homeUrl) {
        if (linkUrl == null || linkUrl.isEmpty()) return homeUrl;
        URI uri;
        try {
            uri = new URI(linkUrl);
        } catch (Exception e) {
            return homeUrl;
        }
        return isTrustedUri(uri) ? linkUrl : homeUrl;
    }
}
