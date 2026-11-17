package app.eyeknow.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * The crop step: the captured photo with a draggable square crop.
 * Confirm writes the cropped JPEG (quality 92, long side capped
 * at 4096) and returns its path. Retake returns to the camera;
 * Cancel abandons the capture.
 *
 * Rotate turns the displayed photo 90 degrees clockwise per
 * tap; four taps return to the original orientation. The crop
 * square is defined in DISPLAYED space, so rotating re-frames
 * it to the default centered square — rotate first, then frame
 * the iris. Confirm maps the displayed square back through the
 * inverse rotation into the JPEG's pixel space, crops there,
 * and rotates the cropped square by the same amount, so the
 * written JPEG matches exactly what the preview showed. The
 * rotation state is per-photo: a retake or a new capture opens
 * a fresh instance at rotation 0, and the auto path never
 * reaches this screen at all.
 */
public class CropActivity extends Activity {

    public static final String EXTRA_IMAGE_PATH = "imagePath";
    public static final String EXTRA_EYE = "eye";
    public static final String EXTRA_CROPPED_PATH = "croppedPath";
    /** True when auto-crop could not frame this photo, so the
     *  status line says so instead of looking like a normal stop. */
    public static final String EXTRA_AUTO_FALLBACK = "autoFallback";
    /** Plain-words "Why" line for an auto-crop fallback (v1.4
     *  diagnostics summary). May be absent. */
    public static final String EXTRA_DIAGNOSTICS = "diagnostics";
    public static final int RESULT_RETAKE = RESULT_FIRST_USER + 2;

    private ImageView imageView;
    private CropOverlayView overlay;
    private TextView statusLine;
    private Button confirmButton;

    private File imageFile;
    /** The preview decode, never rotated. Owned here. */
    private Bitmap baseBitmap;
    /** The bitmap on screen: baseBitmap at rotation 0, else a
     *  rotated copy (recycled on replace, never the base). */
    private Bitmap displayBitmap;
    /** Clockwise rotation of the displayed photo, 0/90/180/270.
     *  Per-photo: a fresh activity instance starts at 0. */
    private int rotation;
    private int fullW;
    private int fullH;
    private boolean working;

    // Fit-center letterbox of the bitmap inside the image view.
    private int imgLeft;
    private int imgTop;
    private int imgW;
    private int imgH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String path = getIntent().getStringExtra(EXTRA_IMAGE_PATH);
        String eye = getIntent().getStringExtra(EXTRA_EYE);
        if (path == null) {
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        imageFile = new File(path);
        int[] d = JpegUtil.dimensions(imageFile);
        fullW = d[0];
        fullH = d[1];
        if (fullW <= 0 || fullH <= 0) {
            Toast.makeText(this, "The captured photo could not be read.",
                    Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        baseBitmap = JpegUtil.decodeCapped(imageFile);
        if (baseBitmap == null) {
            Toast.makeText(this, "The captured photo could not be read.",
                    Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        displayBitmap = baseBitmap;
        rotation = 0;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        imageView = new ImageView(this);
        imageView.setImageBitmap(displayBitmap);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(false);
        root.addView(imageView, new FrameLayout.LayoutParams(-1, -1));
        overlay = new CropOverlayView(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        top.setPadding(pad, pad, pad, pad);
        statusLine = new TextView(this);
        boolean autoFallback = getIntent().getBooleanExtra(EXTRA_AUTO_FALLBACK, false);
        statusLine.setText(autoFallback
                ? "Auto-crop could not frame this photo — frame the "
                        + ("left".equals(eye) ? "left" : "right")
                        + " iris in the square, then confirm."
                : "Frame the " + ("left".equals(eye) ? "left" : "right")
                        + " iris in the square — drag to move, drag a corner to resize.");
        statusLine.setTextColor(0xFFFFFFFF);
        statusLine.setTextSize(15f);
        statusLine.setShadowLayer(4f, 0f, 0f, 0xFF000000);
        top.addView(statusLine);
        // v1.4 diagnostics: the plain-words reason the auto-crop
        // fell back, when the camera screen supplied one. A
        // second, smaller line under the status — the reason
        // the user is framing it themselves.
        String diagSummary = getIntent().getStringExtra(EXTRA_DIAGNOSTICS);
        if (diagSummary != null && !diagSummary.isEmpty()) {
            TextView diagLine = new TextView(this);
            diagLine.setText(diagSummary);
            diagLine.setTextColor(0xFFE0E0E0);
            diagLine.setTextSize(13f);
            diagLine.setShadowLayer(4f, 0f, 0f, 0xFF000000);
            top.addView(diagLine);
        }
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2);
        topLp.gravity = Gravity.TOP;
        root.addView(top, topLp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(pad, pad, pad, pad);
        SeekBar sizeSlider = new SeekBar(this);
        sizeSlider.setMax(100);
        sizeSlider.setProgress(72);
        sizeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    float f = CropMath.MIN_FRACTION + (1f - CropMath.MIN_FRACTION) * p / 100f;
                    overlay.setCropSizeFraction(f, imgLeft, imgTop, imgW, imgH);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        bottom.addView(sizeSlider, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        confirmButton = new Button(this);
        confirmButton.setText("Use this photo");
        confirmButton.setOnClickListener(v -> confirm());
        Button retake = new Button(this);
        retake.setText("Retake");
        retake.setOnClickListener(v -> {
            setResult(RESULT_RETAKE);
            finish();
        });
        Button cancel = new Button(this);
        cancel.setText("Cancel");
        cancel.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });
        buttons.addView(confirmButton);
        Button rotate = new Button(this);
        rotate.setText("Rotate");
        rotate.setOnClickListener(v -> rotatePhoto());
        buttons.addView(rotate);
        buttons.addView(retake);
        buttons.addView(cancel);
        bottom.addView(buttons);
        FrameLayout.LayoutParams bottomLp = new FrameLayout.LayoutParams(-1, -2);
        bottomLp.gravity = Gravity.BOTTOM;
        root.addView(bottom, bottomLp);
        setContentView(root);

        imageView.post(this::computeImageArea);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Where the bitmap actually lands inside the FIT_CENTER view. */
    private void computeImageArea() {
        int vw = imageView.getWidth();
        int vh = imageView.getHeight();
        if (vw == 0 || vh == 0 || displayBitmap == null) return;
        float s = Math.min((float) vw / displayBitmap.getWidth(),
                (float) vh / displayBitmap.getHeight());
        imgW = Math.round(displayBitmap.getWidth() * s);
        imgH = Math.round(displayBitmap.getHeight() * s);
        imgLeft = (vw - imgW) / 2;
        imgTop = (vh - imgH) / 2;
        overlay.setImageArea(imgLeft, imgTop, imgW, imgH);
    }

    /** Rotate the displayed photo 90 degrees clockwise. The
     *  crop square is defined in displayed space, so the
     *  overlay re-frames to the default centered square in the
     *  new orientation — rotate first, then frame the iris.
     *  Four taps return to the original orientation. */
    private void rotatePhoto() {
        if (working || baseBitmap == null) return;
        rotation = (rotation + 90) % 360;
        Bitmap next = (rotation == 0)
                ? baseBitmap
                : JpegUtil.rotate(baseBitmap, rotation);
        if (displayBitmap != null && displayBitmap != baseBitmap) {
            displayBitmap.recycle();
        }
        displayBitmap = next;
        imageView.setImageBitmap(displayBitmap);
        computeImageArea();
    }

    private void confirm() {
        if (working) return;
        CropMath.Rect viewCrop = overlay.getCrop();
        if (viewCrop == null || displayBitmap == null) return;
        working = true;
        confirmButton.setEnabled(false);
        statusLine.setText("Preparing the photo…");
        // Snapshot the UI-thread state for the worker: the
        // rotation and the displayed bitmap's size cannot
        // change while working (rotate is blocked), but the
        // worker must not read views or bitmaps itself.
        final int confirmRotation = rotation;
        final int viewW = imageView.getWidth();
        final int viewH = imageView.getHeight();
        final int dispBmpW = displayBitmap.getWidth();
        final int dispBmpH = displayBitmap.getHeight();
        final int srcW = fullW;
        final int srcH = fullH;
        new Thread(() -> {
            try {
                // View -> DISPLAYED-bitmap coords, then scale to
                // the full displayed pixel space using the exact
                // ratios (the displayed image is the JPEG
                // rotated by confirmRotation, so its full size
                // is the rotated dimensions).
                CropMath.Rect bmpCrop = CropMath.viewToImage(viewCrop,
                        viewW, viewH, dispBmpW, dispBmpH);
                int[] fullDisp = CropMath.rotatedDims(srcW, srcH, confirmRotation);
                float sx = (float) fullDisp[0] / dispBmpW;
                float sy = (float) fullDisp[1] / dispBmpH;
                CropMath.Rect dispCrop = CropMath.clamp(new CropMath.Rect(
                        Math.round(bmpCrop.left * sx), Math.round(bmpCrop.top * sy),
                        Math.round(bmpCrop.size * Math.min(sx, sy))),
                        fullDisp[0], fullDisp[1]);
                // Displayed space -> source pixel space through
                // the inverse rotation; the crop is cut from
                // the source and rotated by the same amount, so
                // the written JPEG is exactly what the preview
                // showed inside the square.
                CropMath.Rect fullCrop = CropMath.unrotateRect(
                        dispCrop, srcW, srcH, confirmRotation);
                File dir = new File(getCacheDir(), "capture");
                dir.mkdirs();
                File out = new File(dir, "cropped-" + System.currentTimeMillis() + ".jpg");
                JpegUtil.cropToFile(imageFile, fullCrop, out, confirmRotation);
                runOnUiThread(() -> {
                    Intent result = new Intent();
                    result.putExtra(EXTRA_CROPPED_PATH, out.getAbsolutePath());
                    setResult(RESULT_OK, result);
                    finish();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    working = false;
                    confirmButton.setEnabled(true);
                    statusLine.setText("Could not prepare the photo: " + e.getMessage());
                });
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        if (displayBitmap != null && displayBitmap != baseBitmap
                && !displayBitmap.isRecycled()) {
            displayBitmap.recycle();
        }
        displayBitmap = null;
        if (baseBitmap != null && !baseBitmap.isRecycled()) {
            baseBitmap.recycle();
            baseBitmap = null;
        }
        if (imageView != null) {
            imageView.setImageDrawable(null);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_CANCELED);
        super.onBackPressed();
    }
}
