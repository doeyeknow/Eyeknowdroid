package app.eyeknow.android;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Native iris camera. Camera2, back camera preferred, closest-focus
 * lens wins (see CameraChoice — the UI labels it honestly and
 * never claims macro the device did not provide).
 *
 * - Torch on by default for the back camera (even illumination,
 *   matching the web flow's "rear camera: torch on"), toggleable.
 * - Continuous autofocus + tap-to-focus on the eye region.
 * - Pinch + slider zoom into the iris, level shown.
 * - A still capture goes to the crop step; the cropped photo
 *   returns to MainActivity as a file path (never a Binder extra
 *   — a JPEG data URL would blow the transaction limit).
 *
 * States are shown honestly throughout; every failure path ends
 * in RESULT_ERROR with the real reason.
 */
public class CameraActivity extends Activity {

    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_EYE = "eye";
    public static final String EXTRA_PHOTO_PATH = "photoPath";
    public static final String EXTRA_ERROR = "error";
    public static final int RESULT_ERROR = RESULT_FIRST_USER + 1;

    private static final int REQ_CROP = 4201;
    private static final String TAG = "EyeknowCamera";

    private TextureView textureView;
    private GuideOverlay guideOverlay;
    private TextView statusLine;
    private TextView cameraLine;
    private TextView zoomLabel;
    private SeekBar zoomSlider;
    private Button torchButton;
    private Button captureButton;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader stillReader;
    private Size previewSize;
    private Size stillSize;

    private String cameraId;
    private Rect activeArray;
    private float maxZoom = 1f;
    private float zoomFactor = 1f;
    private boolean hasFlash;
    private boolean torchOn = true;
    private boolean backFacing = true;
    private int sensorOrientation;
    private String requestId = "";
    private String eye = "";
    private boolean capturing;
    private boolean closed;
    private int lastAfState = -1;
    private boolean scaling;
    private File pendingRawFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        eye = getIntent().getStringExtra(EXTRA_EYE);
        if (requestId == null) requestId = "";
        if (eye == null) eye = "";

        buildUi();
        CaptureSession.setCancelListener(id -> {
            if (id.equals(requestId)) {
                setResult(RESULT_CANCELED);
                finish();
            }
        });
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            fail("Camera permission was denied.");
            return;
        }
        startBackground();
        openCamera();
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        textureView = new TextureView(this);
        root.addView(textureView, new FrameLayout.LayoutParams(-1, -1));

        guideOverlay = new GuideOverlay(this);
        root.addView(guideOverlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        top.setPadding(pad, pad, pad, pad);
        cameraLine = line(top, "");
        statusLine = line(top, "Opening camera…");
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2);
        topLp.gravity = Gravity.TOP;
        root.addView(top, topLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(pad, pad, pad, pad);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        zoomLabel = line(zoomRow, "1.0×");
        zoomSlider = new SeekBar(this);
        zoomSlider.setMax(100);
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(0, -2, 1f);
        zoomRow.addView(zoomSlider, sliderLp);
        bottom.addView(zoomRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        torchButton = new Button(this);
        torchButton.setText("Flash: on");
        torchButton.setOnClickListener(v -> toggleTorch());
        captureButton = new Button(this);
        captureButton.setText("Capture " + ("left".equals(eye) ? "left" : "right") + " eye");
        captureButton.setOnClickListener(v -> captureStill());
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        buttons.addView(torchButton);
        buttons.addView(captureButton);
        buttons.addView(cancel);
        bottom.addView(buttons);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, -2);
        bottomLp.gravity = Gravity.BOTTOM;
        root.addView(bottom, bottomLp);

        setContentView(root);

        zoomSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) setZoom(1f + (maxZoom - 1f) * p / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        GestureDetector taps = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        if (!scaling) focusAt(e.getX(), e.getY());
                        return true;
                    }
                });
        ScaleGestureDetector pinch = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector d) {
                        scaling = true;
                        return true;
                    }
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        setZoom(zoomFactor * d.getScaleFactor());
                        return true;
                    }
                    @Override
                    public void onScaleEnd(ScaleGestureDetector d) {
                        scaling = false;
                    }
                });
        textureView.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            taps.onTouchEvent(e);
            return true;
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView line(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(15f);
        t.setShadowLayer(4f, 0f, 0f, 0xFF000000);
        parent.addView(t);
        return t;
    }

    // ------------------------------------------------------------ camera setup

    private void startBackground() {
        bgThread = new HandlerThread("eyeknow-camera");
        bgThread.start();
        bgHandler = new Handler(bgThread.getLooper());
    }

    private void stopBackground() {
        if (bgThread != null) {
            bgThread.quitSafely();
            try { bgThread.join(); } catch (InterruptedException ignored) {}
            bgThread = null;
            bgHandler = null;
        }
    }

    private void openCamera() {
        List<CameraChoice.Lens> lenses = new ArrayList<>();
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics c = cameraManager.getCameraCharacteristics(id);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                boolean back = facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK;
                Float minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                boolean logical = false;
                if (caps != null) {
                    for (int cap : caps) {
                        if (cap == CameraCharacteristics
                                .REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                            logical = true;
                        }
                    }
                }
                lenses.add(new CameraChoice.Lens(id, back,
                        minFocus == null ? 0f : minFocus, logical, null));
                if (logical) {
                    // Physical lenses behind a logical camera are
                    // candidates too — a hidden macro lens lives here.
                    try {
                        for (String pid : c.getPhysicalCameraIds()) {
                            CameraCharacteristics pc =
                                    cameraManager.getCameraCharacteristics(pid);
                            Integer pf = pc.get(CameraCharacteristics.LENS_FACING);
                            Float pmf = pc.get(
                                    CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
                            lenses.add(new CameraChoice.Lens(pid,
                                    pf != null && pf == CameraCharacteristics.LENS_FACING_BACK,
                                    pmf == null ? 0f : pmf, false, null));
                        }
                    } catch (Exception ignored) {
                        // Physical ids are best-effort; the logical id stands.
                    }
                }
            }
        } catch (CameraAccessException e) {
            fail("Could not list the cameras: " + e.getMessage());
            return;
        }
        CameraChoice.Choice choice = CameraChoice.choose(lenses);
        if (choice == null) {
            fail("No camera was found on this phone.");
            return;
        }
        cameraId = choice.cameraId;
        try {
            CameraCharacteristics c = cameraManager.getCameraCharacteristics(cameraId);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            backFacing = facing != null && facing == CameraCharacteristics.LENS_FACING_BACK;
            activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            Float maxD = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = maxD == null ? 1f : Math.max(1f, maxD);
            Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = flash != null && flash;
            Integer orient = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orient == null ? 0 : orient;
            StreamConfigurationMap map =
                    c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                fail("This camera reports no usable sizes.");
                return;
            }
            previewSize = pickPreviewSize(map.getOutputSizes(SurfaceTexture.class));
            stillSize = pickStillSize(map.getOutputSizes(ImageFormat.JPEG));
            if (previewSize == null || stillSize == null) {
                fail("This camera reports no usable sizes.");
                return;
            }
            torchOn = backFacing && hasFlash; // torch on by default, rear only
            runOnUiThread(() -> {
                cameraLine.setText(choice.label + " camera — " + choice.reason);
                torchButton.setText("Flash: " + (torchOn ? "on" : "off"));
                torchButton.setEnabled(hasFlash);
                zoomSlider.setEnabled(maxZoom > 1f);
            });
        } catch (CameraAccessException e) {
            fail("Could not read this camera's details: " + e.getMessage());
            return;
        }
        if (textureView.isAvailable()) {
            startSession();
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    startSession();
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                    applyPreviewTransform();
                }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
        }
    }

    private Size pickPreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1920) continue;
            if (best == null || (long) s.getWidth() * s.getHeight()
                    > (long) best.getWidth() * best.getHeight()) best = s;
        }
        if (best == null) best = sizes[0];
        return best;
    }

    private Size pickStillSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight()
                    > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best;
    }

    private void startSession() {
        if (closed) return;
        try {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                fail("Camera permission was denied.");
                return;
            }
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice device) {
                    cameraDevice = device;
                    createSession();
                }
                @Override public void onDisconnected(CameraDevice device) {
                    device.close();
                    cameraDevice = null;
                }
                @Override public void onError(CameraDevice device, int error) {
                    device.close();
                    cameraDevice = null;
                    fail("The camera could not be opened (error " + error + ").");
                }
            }, bgHandler);
        } catch (CameraAccessException e) {
            fail("The camera could not be opened: " + e.getMessage());
        } catch (SecurityException e) {
            fail("Camera permission was denied.");
        }
    }

    private void createSession() {
        if (cameraDevice == null || closed) return;
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            applyPreviewTransform();
            Surface previewSurface = new Surface(st);
            stillReader = ImageReader.newInstance(stillSize.getWidth(), stillSize.getHeight(),
                    ImageFormat.JPEG, 2);
            stillReader.setOnImageAvailableListener(reader -> {
                Image img = null;
                try {
                    img = reader.acquireLatestImage();
                    if (img != null) saveStill(img);
                } catch (Exception e) {
                    onCaptureFailed("Could not read the captured photo: " + e.getMessage());
                } finally {
                    if (img != null) img.close();
                }
            }, bgHandler);
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            applyPreviewSettings(previewBuilder);
            cameraDevice.createCaptureSession(
                    Arrays.asList(previewSurface, stillReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(previewBuilder.build(),
                                        captureCallback, bgHandler);
                                setStatus("Ready — center the eye in the ring, hold steady.");
                            } catch (CameraAccessException e) {
                                fail("The camera preview could not start: " + e.getMessage());
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            fail("The camera preview could not start.");
                        }
                    }, bgHandler);
        } catch (CameraAccessException e) {
            fail("The camera session could not start: " + e.getMessage());
        }
    }

    /** Center-crop the preview into the TextureView without
     *  stretching, so the guide ring and tap-to-focus stay true. */
    private void applyPreviewTransform() {
        if (previewSize == null || textureView == null) return;
        int vw = textureView.getWidth();
        int vh = textureView.getHeight();
        if (vw == 0 || vh == 0) return;
        // Preview buffer is landscape-oriented relative to the
        // portrait display; swap axes for the transform.
        int pw = previewSize.getHeight();
        int ph = previewSize.getWidth();
        float scale = Math.max((float) vw / pw, (float) vh / ph);
        Matrix m = new Matrix();
        m.postScale(scale, scale, vw / 2f, vh / 2f);
        textureView.setTransform(m);
    }

    private void applyPreviewSettings(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        if (hasFlash) {
            b.set(CaptureRequest.FLASH_MODE, torchOn
                    ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        }
        applyZoom(b);
    }

    private void applyZoom(CaptureRequest.Builder b) {
        if (activeArray == null) return;
        int[] active = new int[]{activeArray.left, activeArray.top,
                activeArray.right, activeArray.bottom};
        int[] crop = FocusMath.cropRegion(active, zoomFactor, maxZoom);
        b.set(CaptureRequest.SCALER_CROP_REGION,
                new Rect(crop[0], crop[1], crop[2], crop[3]));
    }

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request, TotalCaptureResult result) {
                    Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
                    if (af != null && af != lastAfState) {
                        lastAfState = af;
                        String s = null;
                        if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED) {
                            s = "Focused — ready.";
                        } else if (af == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
                                || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN) {
                            s = "Focusing…";
                        } else if (af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                                || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED) {
                            s = "Not focused — move closer or tap the eye to focus.";
                        }
                        if (s != null) setStatus(s);
                    }
                }
            };

    // ------------------------------------------------------------ controls

    private void toggleTorch() {
        if (!hasFlash) return;
        torchOn = !torchOn;
        torchButton.setText("Flash: " + (torchOn ? "on" : "off"));
        refreshPreview();
    }

    private void setZoom(float factor) {
        float next = FocusMath.clampZoom(factor, maxZoom);
        if (Math.abs(next - zoomFactor) < 0.01f) return;
        zoomFactor = next;
        zoomLabel.setText(String.format(java.util.Locale.US, "%.1f×", zoomFactor));
        if (maxZoom > 1f) {
            int p = Math.round((zoomFactor - 1f) / (maxZoom - 1f) * 100f);
            if (Math.abs(zoomSlider.getProgress() - p) > 1) zoomSlider.setProgress(p);
        }
        refreshPreview();
    }

    private void refreshPreview() {
        if (captureSession == null || previewBuilder == null) return;
        try {
            applyPreviewSettings(previewBuilder);
            captureSession.setRepeatingRequest(previewBuilder.build(), captureCallback, bgHandler);
        } catch (CameraAccessException ignored) {
            // A failed refresh leaves the last good request running.
        }
    }

    /** Tap-to-focus: aim AF + AE at the tapped point, show the ring,
     *  then return to continuous autofocus. */
    private void focusAt(float vx, float vy) {
        if (captureSession == null || previewBuilder == null || activeArray == null) return;
        float fx = vx / Math.max(1, textureView.getWidth());
        float fy = vy / Math.max(1, textureView.getHeight());
        int[] active = new int[]{activeArray.left, activeArray.top,
                activeArray.right, activeArray.bottom};
        int[] crop = FocusMath.cropRegion(active, zoomFactor, maxZoom);
        int[] box = FocusMath.focusBox(fx, fy, crop, active);
        MeteringRectangle rect = new MeteringRectangle(
                new Rect(box[0], box[1], box[2], box[3]),
                MeteringRectangle.METERING_WEIGHT_MAX);
        guideOverlay.showFocus(vx, vy);
        setStatus("Focusing on that spot…");
        try {
            previewBuilder.set(CaptureRequest.CONTROL_AF_REGIONS,
                    new MeteringRectangle[]{rect});
            previewBuilder.set(CaptureRequest.CONTROL_AE_REGIONS,
                    new MeteringRectangle[]{rect});
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_START);
            captureSession.capture(previewBuilder.build(), captureCallback, bgHandler);
            previewBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            // Back to continuous AF shortly after; the regions stay
            // until the next tap, which is the standard behavior.
            bgHandler.postDelayed(() -> {
                if (captureSession == null || previewBuilder == null) return;
                try {
                    previewBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    captureSession.setRepeatingRequest(previewBuilder.build(),
                            captureCallback, bgHandler);
                } catch (CameraAccessException ignored) {}
            }, 1200);
        } catch (CameraAccessException e) {
            setStatus("Focus tap failed — continuous focus is still on.");
        }
    }

    private void captureStill() {
        if (capturing || cameraDevice == null || captureSession == null) return;
        capturing = true;
        setStatus("Capturing… hold still.");
        try {
            CaptureRequest.Builder b =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(stillReader.getSurface());
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (hasFlash) {
                b.set(CaptureRequest.FLASH_MODE, torchOn
                        ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            }
            applyZoom(b);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            captureSession.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(CameraCaptureSession session,
                                            CaptureRequest request,
                                            android.hardware.camera2.CaptureFailure failure) {
                    CameraActivity.this.onCaptureFailed(
                            "The capture failed (" + failure.getReason() + ").");
                }
            }, bgHandler);
        } catch (CameraAccessException e) {
            onCaptureFailed("The capture failed: " + e.getMessage());
        }
    }

    /** Upright JPEG: sensor orientation + display rotation. */
    private int jpegOrientation() {
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int deviceDeg = 0;
        if (rotation == Surface.ROTATION_90) deviceDeg = 90;
        else if (rotation == Surface.ROTATION_180) deviceDeg = 180;
        else if (rotation == Surface.ROTATION_270) deviceDeg = 270;
        if (backFacing) return (sensorOrientation + deviceDeg) % 360;
        return (sensorOrientation - deviceDeg + 360) % 360;
    }

    private void saveStill(Image img) {
        ByteBuffer buf = img.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        File dir = new File(getCacheDir(), "capture");
        dir.mkdirs();
        File raw = new File(dir, "raw-" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(raw)) {
            fos.write(bytes);
        } catch (Exception e) {
            onCaptureFailed("The photo could not be saved: " + e.getMessage());
            return;
        }
        pendingRawFile = raw;
        capturing = false;
        runOnUiThread(() -> {
            setStatus("Photo taken — frame the iris, then confirm.");
            Intent i = new Intent(this, CropActivity.class);
            i.putExtra(CropActivity.EXTRA_IMAGE_PATH, raw.getAbsolutePath());
            i.putExtra(CropActivity.EXTRA_EYE, eye);
            startActivityForResult(i, REQ_CROP);
        });
    }

    private void onCaptureFailed(String message) {
        capturing = false;
        runOnUiThread(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            setStatus("Capture failed — try again.");
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CROP) return;
        if (resultCode == CropActivity.RESULT_RETAKE) {
            if (pendingRawFile != null) pendingRawFile.delete();
            pendingRawFile = null;
            setStatus("Ready — center the eye in the ring, hold steady.");
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            String path = data.getStringExtra(CropActivity.EXTRA_CROPPED_PATH);
            if (path != null) {
                if (pendingRawFile != null) pendingRawFile.delete();
                Intent out = new Intent();
                out.putExtra(EXTRA_PHOTO_PATH, path);
                setResult(RESULT_OK, out);
                finish();
                return;
            }
        }
        // Crop cancelled: treat as a cancelled capture.
        if (pendingRawFile != null) pendingRawFile.delete();
        setResult(RESULT_CANCELED);
        finish();
    }

    // ------------------------------------------------------------ lifecycle

    private void setStatus(final String s) {
        runOnUiThread(() -> statusLine.setText(s));
    }

    private void fail(String message) {
        Intent out = new Intent();
        out.putExtra(EXTRA_ERROR, message);
        setResult(RESULT_ERROR, out);
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        closed = false;
        if (cameraDevice == null && bgHandler == null) {
            startBackground();
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        closed = true;
        closeCamera();
        stopBackground();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        CaptureSession.setCancelListener(null);
        closeCamera();
        stopBackground();
        super.onDestroy();
    }

    private void closeCamera() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
        } catch (Exception ignored) {}
        try {
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
        } catch (Exception ignored) {}
        try {
            if (stillReader != null) {
                stillReader.close();
                stillReader = null;
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }
}
