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
 * at 2560 — the web capture's own caps) and returns its path.
 * Retake returns to the camera; Cancel abandons the capture.
 */
public class CropActivity extends Activity {

    public static final String EXTRA_IMAGE_PATH = "imagePath";
    public static final String EXTRA_EYE = "eye";
    public static final String EXTRA_CROPPED_PATH = "croppedPath";
    public static final int RESULT_RETAKE = RESULT_FIRST_USER + 2;

    private ImageView imageView;
    private CropOverlayView overlay;
    private TextView statusLine;
    private Button confirmButton;

    private File imageFile;
    private Bitmap bitmap;
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
        bitmap = JpegUtil.decodeCapped(imageFile);
        if (bitmap == null) {
            Toast.makeText(this, "The captured photo could not be read.",
                    Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        imageView = new ImageView(this);
        imageView.setImageBitmap(bitmap);
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
        statusLine.setText("Frame the " + ("left".equals(eye) ? "left" : "right")
                + " iris in the square — drag to move, drag a corner to resize.");
        statusLine.setTextColor(0xFFFFFFFF);
        statusLine.setTextSize(15f);
        statusLine.setShadowLayer(4f, 0f, 0f, 0xFF000000);
        top.addView(statusLine);
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
        if (vw == 0 || vh == 0 || bitmap == null) return;
        float s = Math.min((float) vw / bitmap.getWidth(), (float) vh / bitmap.getHeight());
        imgW = Math.round(bitmap.getWidth() * s);
        imgH = Math.round(bitmap.getHeight() * s);
        imgLeft = (vw - imgW) / 2;
        imgTop = (vh - imgH) / 2;
        overlay.setImageArea(imgLeft, imgTop, imgW, imgH);
    }

    private void confirm() {
        if (working) return;
        CropMath.Rect viewCrop = overlay.getCrop();
        if (viewCrop == null) return;
        working = true;
        confirmButton.setEnabled(false);
        statusLine.setText("Preparing the photo…");
        new Thread(() -> {
            try {
                // View -> displayed-bitmap coords, then scale to the
                // full JPEG's pixel space using the exact ratios.
                CropMath.Rect bmpCrop = CropMath.viewToImage(viewCrop,
                        imageView.getWidth(), imageView.getHeight(),
                        bitmap.getWidth(), bitmap.getHeight());
                float sx = (float) fullW / bitmap.getWidth();
                float sy = (float) fullH / bitmap.getHeight();
                CropMath.Rect fullCrop = CropMath.clamp(new CropMath.Rect(
                        Math.round(bmpCrop.left * sx), Math.round(bmpCrop.top * sy),
                        Math.round(bmpCrop.size * Math.min(sx, sy))), fullW, fullH);
                File dir = new File(getCacheDir(), "capture");
                dir.mkdirs();
                File out = new File(dir, "cropped-" + System.currentTimeMillis() + ".jpg");
                JpegUtil.cropToFile(imageFile, fullCrop, out);
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
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
            bitmap = null;
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
