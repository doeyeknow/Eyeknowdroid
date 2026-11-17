package app.eyeknow.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * eyeKnow native shell. Loads https://www.eyeknow.app (the apex
 * 308-redirects there — verified live) in a WebView and hosts the
 * native eye-capture bridge. Site updates need no reinstall: the
 * shell is a window, and the camera is the only native surface.
 *
 * Navigation is locked to the trusted site; every other link
 * opens in the browser. The bridge shim is injected on every
 * page finish, so it is present whenever the site checks for it.
 *
 * Deep links (v1.4): an eyeknow.app link tapped anywhere opens
 * in the app (manifest intent-filter, autoVerify), and the
 * FULL link URL loads — query string intact — so a referral
 * code (?ref=) is captured in the WebView's own storage instead
 * of the browser's cookie jar, where the app could never see
 * it. The launch intent and links arriving while the app runs
 * both go through the same trust rule; anything untrusted
 * loads the home page instead. singleTask launch mode keeps
 * exactly one MainActivity: a link tap while the app runs
 * delivers onNewIntent to the existing instance.
 *
 * Stamped download (v1.5): an APK downloaded through a
 * referral link carries the code stamped into its signing
 * block (see ApkStamp). On the first PLAIN launch — no
 * trusted deep link — the shell reads its own APK file once
 * and, when a valid stamp is present, loads the home page
 * with ?ref= added, so the page captures the referral exactly
 * as if the link had opened in the app. The check runs at
 * most once ever (a persisted flag, set even when no stamp
 * is found): reinstalls start fresh, updates never re-stamp,
 * and a deep link always wins over the stamp. Any failure
 * reading the stamp falls back to the plain home page.
 *
 * The upload fallback and the web camera keep working inside the
 * shell: file inputs get a chooser, and getUserMedia permission
 * requests are granted once the runtime camera permission holds.
 * Against the CURRENT live site (which predates the bridge code)
 * those two paths are how capture works; the native camera
 * activates the moment the site's bridge code deploys.
 */
public class MainActivity extends Activity implements CaptureBridge.Host {

    public static final String HOME_URL = "https://www.eyeknow.app";

    private static final int REQ_CAPTURE = 4101;
    private static final int REQ_FILE_CHOOSER = 4102;
    private static final int REQ_CAMERA_PERMISSION = 4103;

    /** Shell prefs: the once-ever referral-stamp check (v1.5). */
    private static final String SHELL_PREFS = "eyeknow_shell";
    private static final String KEY_STAMP_CHECKED = "ref_stamp_checked";

    private WebView webView;
    private LinearLayout errorPanel;
    private CaptureBridge bridge;
    private ValueCallback<Uri[]> fileChooserCallback;
    private PermissionRequest pendingPermissionRequest;
    private String pendingCaptureRequestId;
    private String pendingCaptureEye;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout container = new FrameLayout(this);
        webView = new WebView(this);
        container.addView(webView, new FrameLayout.LayoutParams(-1, -1));
        errorPanel = buildErrorPanel();
        container.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
        setContentView(container);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setSupportMultipleWindows(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        // v1.6: honor the site's viewport meta (width=device-width,
        // initial-scale=1) and fit the page to the screen width.
        // Without these two, the WebView ignores the viewport tag,
        // lays the page out at the default wide width, and shows it
        // with no shrink-to-fit — the page looks zoomed in. The
        // site sets user-scalable=false itself, so pinch-zoom stays
        // off, matching Chrome and Adam's 2026-11-05 no-zoom call.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        CookieManager.getInstance().setAcceptCookie(true);

        bridge = new CaptureBridge(webView, this);
        webView.addJavascriptInterface(bridge, CaptureBridge.NATIVE_NAME);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (isTrustedSite(request.getUrl())) return false;
                if (request.isForMainFrame()) openExternalLink(request.getUrl());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                errorPanel.setVisibility(View.GONE);
                bridge.injectShim();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) errorPanel.setVisibility(View.VISIBLE);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (request.isForMainFrame() && errorResponse.getStatusCode() >= 400) {
                    errorPanel.setVisibility(View.VISIBLE);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
                fileChooserCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                try {
                    startActivityForResult(
                            Intent.createChooser(i, "Choose an eye photo"), REQ_FILE_CHOOSER);
                } catch (Exception e) {
                    fileChooserCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                String[] wanted = request.getResources();
                boolean needsCamera = false;
                for (String r : wanted) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) needsCamera = true;
                }
                if (!needsCamera) {
                    request.deny();
                    return;
                }
                if (checkSelfPermission(Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                    request.grant(wanted);
                } else {
                    pendingPermissionRequest = request;
                    requestPermissions(new String[]{Manifest.permission.CAMERA},
                            REQ_CAMERA_PERMISSION);
                }
            }
        });

        // A deep link (affiliate ?ref= link, shared reading, ...)
        // loads in full, query intact; a plain launch, or an
        // untrusted intent URL, loads the home page — with the
        // stamped referral attached on the first plain launch
        // when this APK was downloaded through a referral link.
        webView.loadUrl(launchUrlFor(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Only navigate when the intent actually carries a link:
        // a plain launcher re-tap (no data) must leave the
        // current page alone. A link arriving while the app runs
        // loads exactly as it would on a cold start.
        if (intent != null && intent.getDataString() != null && webView != null) {
            // Links arriving mid-session never consult the
            // stamp: the stamp belongs to the first launch.
            webView.loadUrl(TrustedSite.startUrl(intent.getDataString(), HOME_URL));
        }
    }

    /** The URL to load at launch for an incoming intent: the
     *  full link URL verbatim when trusted; HOME_URL
     *  otherwise — with the stamped referral attached on the
     *  first plain launch when this APK carries a valid stamp. */
    private String launchUrlFor(Intent intent) {
        String data = intent == null ? null : intent.getDataString();
        String start = TrustedSite.startUrl(data, HOME_URL);
        if (!HOME_URL.equals(start)) return start;
        return applyStampIfFirstLaunch();
    }

    /**
     * The referral stamp, consulted at most once ever: on the
     * first plain launch, read this APK's signing block and
     * attach a valid stamp as ?ref=. The checked flag is set
     * before reading, so the check never runs twice — even
     * when no stamp is found or the read fails. A deep link
     * never reaches this method, so a tapped link always wins
     * over the stamp, and the flag is left unset for a later
     * plain launch. Never throws: any failure yields HOME_URL.
     */
    private String applyStampIfFirstLaunch() {
        try {
            SharedPreferences prefs = getSharedPreferences(SHELL_PREFS, MODE_PRIVATE);
            if (prefs.getBoolean(KEY_STAMP_CHECKED, false)) return HOME_URL;
            prefs.edit().putBoolean(KEY_STAMP_CHECKED, true).apply();
            String code = ApkStamp.readStampFromFile(getPackageCodePath());
            return ApkStamp.stampedHomeUrl(HOME_URL, code);
        } catch (Exception e) {
            return HOME_URL;
        }
    }

    private LinearLayout buildErrorPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        int pad = (int) (48 * getResources().getDisplayMetrics().density);
        panel.setPadding(pad, pad, pad, pad);
        panel.setBackgroundColor(Color.WHITE);
        panel.setVisibility(View.GONE);
        TextView msg = new TextView(this);
        msg.setText("Could not load eyeknow. Check your connection and retry.");
        msg.setTextSize(18f);
        msg.setGravity(Gravity.CENTER);
        msg.setTextColor(Color.BLACK);
        panel.addView(msg);
        Button retry = new Button(this);
        retry.setText("Retry");
        retry.setOnClickListener(v -> {
            panel.setVisibility(View.GONE);
            webView.loadUrl(HOME_URL);
        });
        panel.addView(retry);
        return panel;
    }

    private boolean isTrustedSite(Uri uri) {
        // Same rule as the deep-link entry point, factored into
        // TrustedSite so the two can never drift apart.
        return TrustedSite.isTrusted(uri.getScheme(), uri.getHost(), uri.getPort());
    }

    private void openExternalLink(Uri uri) {
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)
                && !"mailto".equals(scheme) && !"tel".equals(scheme)) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception e) {
            Toast.makeText(this, "No app is available to open this link.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------- bridge host

    @Override
    public void onCaptureRequested(String requestId, String eye) {
        if (!CaptureSession.begin(requestId, eye)) {
            bridge.deliverError(requestId, "An eye capture is already in progress.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pendingCaptureRequestId = requestId;
            pendingCaptureEye = eye;
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    REQ_CAMERA_PERMISSION);
            return;
        }
        launchCamera(requestId, eye);
    }

    @Override
    public void onCaptureCancelled(String requestId) {
        // The web side timed out. If the camera screen is up for
        // this request, finish it; the session ends when the
        // activity result arrives (or immediately if none is up).
        if (CaptureSession.cancel(requestId)) {
            // CameraActivity listens and finishes itself.
        }
    }

    private void launchCamera(String requestId, String eye) {
        Intent i = new Intent(this, CameraActivity.class);
        i.putExtra(CameraActivity.EXTRA_REQUEST_ID, requestId);
        i.putExtra(CameraActivity.EXTRA_EYE, eye);
        try {
            startActivityForResult(i, REQ_CAPTURE);
        } catch (Exception e) {
            CaptureSession.end();
            bridge.deliverError(requestId, "Could not open the native camera.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CAMERA_PERMISSION) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (pendingPermissionRequest != null) {
            PermissionRequest req = pendingPermissionRequest;
            pendingPermissionRequest = null;
            if (granted) req.grant(req.getResources());
            else req.deny();
        }
        if (pendingCaptureRequestId != null) {
            String requestId = pendingCaptureRequestId;
            String eye = pendingCaptureEye;
            pendingCaptureRequestId = null;
            pendingCaptureEye = null;
            if (granted) {
                launchCamera(requestId, eye);
            } else {
                CaptureSession.end();
                bridge.deliverError(requestId,
                        "Camera permission was denied — allow it to use the native camera.");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            ValueCallback<Uri[]> cb = fileChooserCallback;
            fileChooserCallback = null;
            if (cb == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
            cb.onReceiveValue(result);
            return;
        }
        if (requestCode != REQ_CAPTURE) return;
        String requestId = CaptureSession.activeRequestId();
        CaptureSession.end();
        if (requestId == null) return; // stale result; nothing waits for it
        if (resultCode == RESULT_CANCELED || data == null) {
            bridge.deliverCancelled(requestId);
            return;
        }
        if (resultCode == CameraActivity.RESULT_ERROR) {
            String msg = data.getStringExtra(CameraActivity.EXTRA_ERROR);
            bridge.deliverError(requestId,
                    msg != null ? msg : "Eye capture failed.");
            return;
        }
        String path = data.getStringExtra(CameraActivity.EXTRA_PHOTO_PATH);
        if (path == null) {
            bridge.deliverError(requestId, "The camera returned no photo.");
            return;
        }
        try {
            String display = JpegUtil.toDataUrl(new File(path));
            new File(path).delete();
            bridge.deliverCaptured(requestId, display);
        } catch (Exception e) {
            bridge.deliverError(requestId, "The captured photo could not be read.");
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
