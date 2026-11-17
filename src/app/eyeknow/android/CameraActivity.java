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
import android.os.SystemClock;
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
 * - Auto-capture (v1.1): preview frames are analyzed on a
 *   background thread (throttled, downscaled) with the web
 *   reader's own decision logic — iris present, well-framed, in
 *   focus, and steady — and the still fires itself. The manual
 *   shutter button always stays available, and auto-capture can
 *   be toggled off; it never captures a frame the detector
 *   rejected.
 * - Auto-crop + auto-submit (v1.2): a gate-fired still is
 *   cropped automatically to the detected iris bounds and
 *   submitted through the same bridge path as a manual confirm
 *   — no crop screen on the auto path. When the crop mapping
 *   cannot be computed safely, the photo falls back to the
 *   manual crop screen instead of submitting a bad crop.
 * - A manual still capture goes to the crop step; the cropped
 *   photo returns to MainActivity as a file path (never a
 *   Binder extra — a JPEG data URL would blow the transaction
 *   limit).
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

    /** Last-attempt diagnostics storage (v1.4): one record,
     *  numbers only, app-private, never transmitted. */
    private static final String DIAG_PREFS = "eyeknow_camera_diag";
    private static final String DIAG_KEY = "last_autocrop";
    private static final String TAG = "EyeknowCamera";

    private TextureView textureView;
    private GuideOverlay guideOverlay;
    private TextView statusLine;
    private TextView diagLine;
    private TextView cameraLine;
    private TextView zoomLabel;
    private SeekBar zoomSlider;
    private Button torchButton;
    private Button captureButton;
    private Button autoButton;

    private HandlerThread bgThread;
    private Handler bgHandler;
    private HandlerThread analysisThread;
    private Handler analysisHandler;
    private ImageReader analysisReader;
    private Size analysisSize;
    private final AutoCaptureGate gate = new AutoCaptureGate();
    private boolean autoCapture = true;
    private boolean autoAvailable;
    private long lastAnalysisMs;
    private String lastStatus = "";
    private volatile boolean afFocused;
    private volatile boolean afKnown;
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;
    private ImageReader stillReader;
    private Size previewSize;
    private Size stillSize;

    private String cameraId;
    private String cameraLabel = "";
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
    // Auto-capture fire state (v1.2): whether the pending still
    // was gate-fired, the firing detection (the most recent
    // passing frame — the one that completed the stability
    // window), and the JPEG_ORIENTATION actually used for the
    // still. The auto-crop mapping needs all of it; the manual
    // path never reads these.
    private boolean autoFired;
    private float fireCx;
    private float fireCy;
    private float fireIrisR;
    private int fireFrameW;
    private int fireFrameH;
    private int stillOrientation;

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
        // Last auto-crop check, when one has been recorded (v1.4
        // diagnostics): one small dim line, hidden until the
        // first auto attempt. Numbers only, never a photo.
        diagLine = line(top, "");
        diagLine.setTextSize(12f);
        diagLine.setVisibility(View.GONE);
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
        captureButton.setOnClickListener(v -> captureStill(false));
        autoButton = new Button(this);
        autoButton.setOnClickListener(v -> toggleAutoCapture());
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        buttons.addView(torchButton);
        buttons.addView(autoButton);
        buttons.addView(captureButton);
        buttons.addView(cancel);
        bottom.addView(buttons);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, -2);
        bottomLp.gravity = Gravity.BOTTOM;
        root.addView(bottom, bottomLp);

        // Auto-capture preference, default ON (Adam asked for it).
        autoCapture = getSharedPreferences("eyeknow_camera", MODE_PRIVATE)
                .getBoolean("auto_capture", true);
        updateAutoButton();

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
        analysisThread = new HandlerThread("eyeknow-analysis");
        analysisThread.start();
        analysisHandler = new Handler(analysisThread.getLooper());
    }

    private void stopBackground() {
        if (bgThread != null) {
            bgThread.quitSafely();
            try { bgThread.join(); } catch (InterruptedException ignored) {}
            bgThread = null;
            bgHandler = null;
        }
        if (analysisThread != null) {
            analysisThread.quitSafely();
            try { analysisThread.join(); } catch (InterruptedException ignored) {}
            analysisThread = null;
            analysisHandler = null;
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
            analysisSize = pickAnalysisSize(map.getOutputSizes(ImageFormat.YUV_420_888));
            autoAvailable = analysisSize != null;
            torchOn = backFacing && hasFlash; // torch on by default, rear only
            cameraLabel = choice.label;
            runOnUiThread(() -> {
                cameraLine.setText(choice.label + " camera — " + choice.reason);
                torchButton.setText("Flash: " + (torchOn ? "on" : "off"));
                torchButton.setEnabled(hasFlash);
                zoomSlider.setEnabled(maxZoom > 1f);
                updateAutoButton();
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

    /** Smallest YUV size in the 320-640 wide band the detector
     *  wants; falls back to the smallest size the camera offers.
     *  Analysis downscales to 320 wide anyway, so a bigger buffer
     *  only costs memory and copy time. */
    private Size pickAnalysisSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() < 320 || s.getWidth() > 640) continue;
            if (best == null || (long) s.getWidth() * s.getHeight()
                    < (long) best.getWidth() * best.getHeight()) best = s;
        }
        if (best != null) return best;
        best = sizes[0];
        for (Size s : sizes) {
            if ((long) s.getWidth() * s.getHeight()
                    < (long) best.getWidth() * best.getHeight()) best = s;
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
        createSessionInternal(true);
    }

    private void createSessionInternal(boolean includeAnalysis) {
        if (cameraDevice == null || closed) return;
        try {
            SurfaceTexture st = textureView.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            applyPreviewTransform();
            Surface previewSurface = new Surface(st);
            if (stillReader == null) {
                stillReader = ImageReader.newInstance(stillSize.getWidth(),
                        stillSize.getHeight(), ImageFormat.JPEG, 2);
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
            }
            List<Surface> targets = new ArrayList<>();
            targets.add(previewSurface);
            targets.add(stillReader.getSurface());
            if (includeAnalysis && autoAvailable && analysisReader == null
                    && analysisHandler != null) {
                analysisReader = ImageReader.newInstance(analysisSize.getWidth(),
                        analysisSize.getHeight(), ImageFormat.YUV_420_888, 2);
                analysisReader.setOnImageAvailableListener(
                        this::analyzeFrame, analysisHandler);
                targets.add(analysisReader.getSurface());
            }
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            // v1.2 fix: the analysis surface MUST be a target of
            // the repeating request, or it never receives a
            // frame — a surface in the session outputs alone gets
            // nothing. v1.1 omitted this target, so analyzeFrame
            // never ran and the gate could never fire; every v1.1
            // photo that reached the crop screen came from the
            // manual shutter. The retry path (no analysis) builds
            // a fresh builder with analysisReader null, so no
            // stale target can survive it.
            if (analysisReader != null) {
                previewBuilder.addTarget(analysisReader.getSurface());
            }
            applyPreviewSettings(previewBuilder);
            cameraDevice.createCaptureSession(
                    targets,
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                session.setRepeatingRequest(previewBuilder.build(),
                                        captureCallback, bgHandler);
                                if (autoCapture && autoAvailable) {
                                    setStatus("Looking for the iris — center the eye in the ring.");
                                } else if (!autoAvailable) {
                                    setStatus("Auto-capture isn't available on this camera — "
                                            + "use the Capture button.");
                                } else {
                                    setStatus("Ready — center the eye in the ring, hold steady.");
                                }
                            } catch (CameraAccessException e) {
                                fail("The camera preview could not start: " + e.getMessage());
                            }
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            if (includeAnalysis && !closed) {
                                // Some cameras cannot run preview + YUV +
                                // JPEG together. Retry without the
                                // analysis target: manual capture must
                                // never be bricked by auto-capture.
                                if (analysisReader != null) {
                                    analysisReader.close();
                                    analysisReader = null;
                                }
                                autoAvailable = false;
                                runOnUiThread(() -> updateAutoButton());
                                createSessionInternal(false);
                                return;
                            }
                            fail("The camera preview could not start.");
                        }
                    }, bgHandler);
        } catch (CameraAccessException e) {
            fail("The camera session could not start: " + e.getMessage());
        }
    }

    /** Runs on the analysis thread. Throttled to ~5 fps, latest
     *  frame only; never blocks the preview. Any exception here
     *  must stay local — analysis never breaks the camera. */
    private void analyzeFrame(ImageReader reader) {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;
            if (!autoCapture || !autoAvailable || capturing || gate.hasFired()) return;
            long now = SystemClock.uptimeMillis();
            if (now - lastAnalysisMs < 200) return;
            lastAnalysisMs = now;
            Image.Plane[] planes = img.getPlanes();
            if (planes.length < 3) return;
            IrisFrame frame = YuvFrame.convert(img.getWidth(), img.getHeight(),
                    planes[0].getBuffer(), planes[0].getRowStride(),
                    planes[1].getBuffer(), planes[1].getRowStride(), planes[1].getPixelStride(),
                    planes[2].getBuffer(), planes[2].getRowStride(), planes[2].getPixelStride());
            IrisDetector.Result r = IrisDetector.detect(frame);
            AutoCaptureGate.State state = gate.feed(r, afFocused, afKnown);
            onGateState(state, r, frame.width, frame.height);
        } catch (Exception ignored) {
            // Analysis failures stay silent; the manual shutter remains.
        } finally {
            if (img != null) img.close();
        }
    }

    /** Gate states drive the status line while auto-capture is
     *  active. The FIRED state stashes the firing detection and
     *  takes the still on the AUTO path (v1.2: auto-crop +
     *  auto-submit, no crop screen). */
    private void onGateState(AutoCaptureGate.State state, IrisDetector.Result r,
                             int frameW, int frameH) {
        runOnUiThread(() -> {
            switch (state) {
                case SEARCHING:
                    setStatus("Looking for the iris — center the eye in the ring.");
                    break;
                case FOUND:
                    setStatus("Iris found — move closer so it fills the ring.");
                    break;
                case FOCUSING:
                    if (r.meanLum < 30) {
                        setStatus("Too dark to focus — move closer to the light.");
                    } else {
                        setStatus("Iris found — focusing… hold still.");
                    }
                    break;
                case STEADY:
                    setStatus("Hold steady… " + gate.steadyCount()
                            + "/" + AutoCaptureGate.STEADY_FRAMES);
                    break;
                case FIRED:
                    // The firing frame is the most recent passing
                    // detection — the one that completed the
                    // stability window. Stash it for the crop
                    // mapping before the still fires. captureStill
                    // owns autoFired: if a manual capture is
                    // already in flight, this fire is dropped and
                    // the manual photo keeps the crop step.
                    fireCx = r.cx;
                    fireCy = r.cy;
                    fireIrisR = r.irisR;
                    fireFrameW = frameW;
                    fireFrameH = frameH;
                    // v1.4 sequencing fix: the gate firing is NOT
                    // a capture — the still is taken after this,
                    // and saying "Captured." here made the user
                    // relax and move before the shutter, shifting
                    // the frame at macro distance. "Hold still"
                    // until the still bytes are saved (see
                    // CaptureStatus — the sequence is pinned by
                    // the harness).
                    setStatus(CaptureStatus.text(CaptureStatus.Phase.FIRING));
                    captureStill(true);
                    break;
            }
        });
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
                    if (af != null) {
                        // AF state feeds the auto-capture gate: focused
                        // means FOCUSED_LOCKED or PASSIVE_FOCUSED; a
                        // fixed-focus lens never reports any state but
                        // INACTIVE, and the gate must not wait forever.
                        if (af != CaptureResult.CONTROL_AF_STATE_INACTIVE) afKnown = true;
                        afFocused = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                                || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED;
                    }
                    if (af != null && af != lastAfState) {
                        lastAfState = af;
                        // While auto-capture is active the gate owns
                        // the status line; AF chatter must not stomp it.
                        if (autoCapture && autoAvailable) return;
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

    private void toggleAutoCapture() {
        autoCapture = !autoCapture;
        getSharedPreferences("eyeknow_camera", MODE_PRIVATE)
                .edit().putBoolean("auto_capture", autoCapture).apply();
        gate.reset();
        updateAutoButton();
        if (autoCapture && autoAvailable) {
            setStatus("Looking for the iris — center the eye in the ring.");
        } else if (!autoAvailable) {
            setStatus("Auto-capture isn't available on this camera — use the Capture button.");
        } else {
            setStatus("Auto-capture off — use the Capture button.");
        }
    }

    private void updateAutoButton() {
        if (autoButton == null) return;
        autoButton.setEnabled(autoAvailable);
        autoButton.setText("Auto: " + (autoCapture ? "on" : "off"));
    }

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

    /** Fire the still. {@code auto} marks a gate-fired capture
     *  (auto-crop + auto-submit); the manual shutter passes false
     *  and keeps the crop step. The JPEG_ORIENTATION actually
     *  used is stashed: the auto-crop mapping must use the
     *  rotation the still was taken with, not a recomputation. */
    private void captureStill(boolean auto) {
        if (capturing || cameraDevice == null || captureSession == null) return;
        capturing = true;
        autoFired = auto;
        // Same honest text on both paths: capturing, not
        // captured — the still has not been taken yet.
        setStatus(CaptureStatus.text(CaptureStatus.Phase.CAPTURING));
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
            stillOrientation = jpegOrientation();
            b.set(CaptureRequest.JPEG_ORIENTATION, stillOrientation);
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
        if (autoFired) {
            // Auto path (v1.2): crop to the detected iris and
            // submit — no crop screen. Any failure here falls
            // back to the manual crop screen, never a dead end.
            autoFired = false;
            tryAutoCrop(raw);
            return;
        }
        openCropScreen(raw, false, null);
    }

    /** Auto-crop + verify + auto-submit for a gate-fired still.
     *  Runs on the camera background thread (saveStill's thread):
     *  the crop encode and the verification happen here, and
     *  only the finish crosses to the UI thread. Every failure
     *  — null mapping, encode failure, verification failure,
     *  verification error — falls back to the manual crop
     *  screen with the raw photo intact and a diagnostics
     *  record explaining why. Nothing here can crash the
     *  capture: a rejected crop is deleted, the raw is kept,
     *  and the user still gets to submit, framing it
     *  themselves. */
    private void tryAutoCrop(File raw) {
        // The still bytes are saved: only now may the status
        // say the photo exists (v1.4 sequencing fix).
        setStatus(CaptureStatus.text(CaptureStatus.Phase.SAVED));
        int[] d = JpegUtil.dimensions(raw);
        CropMath.Rect rect = null;
        if (d[0] > 0 && d[1] > 0 && stillSize != null) {
            rect = AutoCrop.mapToJpeg(fireCx, fireCy, fireIrisR,
                    fireFrameW, fireFrameH,
                    d[0], d[1], stillOrientation,
                    stillSize.getWidth(), stillSize.getHeight());
        }
        if (rect == null) {
            CropDiagnostics.Record rec = diagRecord(d, null,
                    CropDiagnostics.Outcome.MAPPING_FAILED, null,
                    Float.NaN, Float.NaN, Float.NaN, -1);
            recordDiag(rec);
            openCropScreen(raw, true, CropDiagnostics.fallbackSummary(rec));
            return;
        }
        File out;
        try {
            File dir = new File(getCacheDir(), "capture");
            dir.mkdirs();
            out = new File(dir, "cropped-" + System.currentTimeMillis() + ".jpg");
            JpegUtil.cropToFile(raw, rect, out);
        } catch (Exception e) {
            // Encode failed: the raw photo is intact, so the
            // manual crop screen can still prepare it.
            CropDiagnostics.Record rec = diagRecord(d, rect,
                    CropDiagnostics.Outcome.ENCODE_FAILED, null,
                    Float.NaN, Float.NaN, Float.NaN, -1);
            recordDiag(rec);
            openCropScreen(raw, true, CropDiagnostics.fallbackSummary(rec));
            return;
        }
        // v1.4 verification: trace the iris edge from the
        // center of the CUT crop before submitting — a wrong
        // mapping, a stale detection, or movement after the
        // gate fired used to sail through silently.
        setStatus(CaptureStatus.text(CaptureStatus.Phase.VERIFYING));
        long t0 = System.currentTimeMillis();
        CropVerify.Verdict verdict = null;
        try {
            IrisFrame frame = JpegUtil.frameForVerify(out);
            if (frame != null) {
                verdict = CropVerify.verify(
                        IrisDetector.traceFromCenter(frame), frame.minDim());
            }
        } catch (Exception ignored) {
            verdict = null; // verification error -> fallback below
        }
        long verifyMs = System.currentTimeMillis() - t0;
        if (verdict == null) {
            CropDiagnostics.Record rec = diagRecord(d, rect,
                    CropDiagnostics.Outcome.VERIFY_ERROR, null,
                    Float.NaN, Float.NaN, Float.NaN, verifyMs);
            recordDiag(rec);
            out.delete();
            openCropScreen(raw, true, CropDiagnostics.fallbackSummary(rec));
            return;
        }
        if (!verdict.pass) {
            CropDiagnostics.Record rec = diagRecord(d, rect,
                    CropDiagnostics.Outcome.VERIFY_FAILED, verdict.reason,
                    verdict.validFrac, verdict.irisFrac, verdict.spread, verifyMs);
            recordDiag(rec);
            out.delete();
            openCropScreen(raw, true, CropDiagnostics.fallbackSummary(rec));
            return;
        }
        CropDiagnostics.Record rec = diagRecord(d, rect,
                CropDiagnostics.Outcome.SUBMITTED, CropVerify.Reason.OK,
                verdict.validFrac, verdict.irisFrac, verdict.spread, verifyMs);
        recordDiag(rec);
        if (pendingRawFile != null) pendingRawFile.delete();
        pendingRawFile = null;
        final File submit = out;
        setStatus(CaptureStatus.text(CaptureStatus.Phase.SUBMITTING));
        runOnUiThread(() -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_PHOTO_PATH, submit.getAbsolutePath());
            setResult(RESULT_OK, result);
            finish();
        });
    }

    /** The diagnostics record for this attempt, filled from the
     *  camera state. Numbers only, no photo bytes. */
    private CropDiagnostics.Record diagRecord(int[] jpegDims, CropMath.Rect rect,
            CropDiagnostics.Outcome outcome, CropVerify.Reason reason,
            float validFrac, float irisFrac, float spread, long verifyMs) {
        return new CropDiagnostics.Record(
                cameraId, cameraLabel,
                analysisSize == null ? -1 : analysisSize.getWidth(),
                analysisSize == null ? -1 : analysisSize.getHeight(),
                stillSize == null ? -1 : stillSize.getWidth(),
                stillSize == null ? -1 : stillSize.getHeight(),
                stillOrientation,
                fireCx, fireCy, fireIrisR, fireFrameW, fireFrameH,
                rect == null ? -1 : rect.left,
                rect == null ? -1 : rect.top,
                rect == null ? -1 : rect.size,
                jpegDims[0], jpegDims[1],
                outcome, reason, validFrac, irisFrac, spread, verifyMs);
    }

    /** Store the last attempt (only) and refresh the camera
     *  screen's diagnostics line. App-private prefs; the record
     *  is never transmitted anywhere. */
    private void recordDiag(CropDiagnostics.Record rec) {
        getSharedPreferences(DIAG_PREFS, MODE_PRIVATE).edit()
                .putString(DIAG_KEY, CropDiagnostics.encode(rec)).apply();
        refreshDiagLine();
    }

    private void refreshDiagLine() {
        String encoded = getSharedPreferences(DIAG_PREFS, MODE_PRIVATE)
                .getString(DIAG_KEY, null);
        final CropDiagnostics.Record rec = CropDiagnostics.decode(encoded);
        runOnUiThread(() -> {
            if (diagLine == null) return;
            if (rec == null) {
                diagLine.setVisibility(View.GONE);
            } else {
                diagLine.setText(CropDiagnostics.lastCheckLine(rec));
                diagLine.setVisibility(View.VISIBLE);
            }
        });
    }

    /** Open the manual crop step. {@code autoFallback} marks a
     *  photo the auto-crop could not frame, so the crop screen
     *  says so honestly instead of looking like a normal stop;
     *  {@code diagSummary} (may be null) is the plain-words
     *  "Why" line shown under the status on that screen. */
    private void openCropScreen(File raw, boolean autoFallback, String diagSummary) {
        runOnUiThread(() -> {
            setStatus(autoFallback
                    ? "Auto-crop could not frame this photo — frame the iris, then confirm."
                    : "Photo taken — frame the iris, then confirm.");
            Intent i = new Intent(this, CropActivity.class);
            i.putExtra(CropActivity.EXTRA_IMAGE_PATH, raw.getAbsolutePath());
            i.putExtra(CropActivity.EXTRA_EYE, eye);
            i.putExtra(CropActivity.EXTRA_AUTO_FALLBACK, autoFallback);
            if (diagSummary != null) {
                i.putExtra(CropActivity.EXTRA_DIAGNOSTICS, diagSummary);
            }
            startActivityForResult(i, REQ_CROP);
        });
    }

    private void onCaptureFailed(String message) {
        capturing = false;
        autoFired = false;
        gate.reset(); // a failed capture must not latch auto-capture off
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
            gate.reset(); // a fresh stability window for the retake
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

    /** Status line with dedup: the gate feeds at ~5 fps, and
     *  re-setting the same text every feed would churn the UI. */
    private void setStatus(final String s) {
        runOnUiThread(() -> {
            if (s.equals(lastStatus)) return;
            lastStatus = s;
            statusLine.setText(s);
        });
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
        gate.reset(); // a fresh stability window each time we return
        afKnown = false;
        afFocused = false;
        refreshDiagLine(); // the last auto-crop check, if any
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
        try {
            if (analysisReader != null) {
                analysisReader.close();
                analysisReader = null;
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }
}
