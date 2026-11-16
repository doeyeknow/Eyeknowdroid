package app.eyeknow.android;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
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

        webView.loadUrl(HOME_URL);
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
        if (!"https".equals(uri.getScheme())) return false;
        String host = uri.getHost();
        if (!"eyeknow.app".equals(host) && !"www.eyeknow.app".equals(host)) return false;
        return uri.getPort() == -1 || uri.getPort() == 443;
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
