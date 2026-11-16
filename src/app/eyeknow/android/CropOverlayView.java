package app.eyeknow.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.MotionEvent;
import android.view.View;

/**
 * The square crop overlay: dimmed surround, clear square, corner
 * handles. Drag inside the square to move it; drag a corner to
 * resize it (stays square); the activity's slider resizes too.
 * All geometry here is VIEW pixels; the activity converts to
 * image pixels with CropMath at confirm time.
 */
public class CropOverlayView extends View {

    public interface Listener {
        void onCropChanged();
    }

    private final Paint dimPaint = new Paint();
    private final Paint clearPaint = new Paint();
    private final Paint edgePaint = new Paint();
    private final Paint handlePaint = new Paint();

    private CropMath.Rect crop; // view coords
    private Listener listener;
    private int dragMode; // 0 none, 1 move, 2 resize
    private float lastX;
    private float lastY;
    private int viewW;
    private int viewH;

    public CropOverlayView(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        dimPaint.setColor(0xAA000000);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        edgePaint.setColor(Color.WHITE);
        edgePaint.setStyle(Paint.Style.STROKE);
        edgePaint.setStrokeWidth(4f);
        edgePaint.setAntiAlias(true);
        handlePaint.setColor(0xFF4ADE80);
        handlePaint.setStyle(Paint.Style.FILL);
        handlePaint.setAntiAlias(true);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Image area in view coords (fit-center letterbox). Set by the
     *  activity once the bitmap is known; the crop defaults to a
     *  centered square inside it. */
    public void setImageArea(int left, int top, int width, int height) {
        int shortSide = Math.min(width, height);
        int size = Math.round(shortSide * CropMath.DEFAULT_FRACTION);
        crop = new CropMath.Rect(left + (width - size) / 2, top + (height - size) / 2, size);
        invalidate();
    }

    public CropMath.Rect getCrop() {
        return crop;
    }

    /** Resize around the center (driven by the slider). */
    public void setCropSizeFraction(float fraction, int imageLeft, int imageTop,
                                    int imageWidth, int imageHeight) {
        if (crop == null) return;
        int shortSide = Math.min(imageWidth, imageHeight);
        int size = Math.round(shortSide * fraction);
        int cx = crop.left + crop.size / 2;
        int cy = crop.top + crop.size / 2;
        CropMath.Rect next = new CropMath.Rect(cx - size / 2, cy - size / 2, size);
        next = clampToImage(next, imageLeft, imageTop, imageWidth, imageHeight);
        crop = next;
        invalidate();
        if (listener != null) listener.onCropChanged();
    }

    private CropMath.Rect clampToImage(CropMath.Rect r, int il, int it, int iw, int ih) {
        int shortSide = Math.min(iw, ih);
        int minSize = Math.max(1, Math.round(shortSide * CropMath.MIN_FRACTION));
        int size = Math.max(minSize, Math.min(shortSide, r.size));
        int left = Math.max(il, Math.min(il + iw - size, r.left));
        int top = Math.max(it, Math.min(it + ih - size, r.top));
        return new CropMath.Rect(left, top, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        viewW = w;
        viewH = h;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (crop == null) return false;
        float x = e.getX();
        float y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float handle = 60f * getResources().getDisplayMetrics().density / 2f;
                boolean nearCorner = (Math.abs(x - crop.left) < handle
                        || Math.abs(x - (crop.left + crop.size)) < handle)
                        && (Math.abs(y - crop.top) < handle
                        || Math.abs(y - (crop.top + crop.size)) < handle);
                if (nearCorner) {
                    dragMode = 2;
                } else if (x >= crop.left && x <= crop.left + crop.size
                        && y >= crop.top && y <= crop.top + crop.size) {
                    dragMode = 1;
                } else {
                    return false;
                }
                lastX = x;
                lastY = y;
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragMode == 1) {
                    int dx = Math.round(x - lastX);
                    int dy = Math.round(y - lastY);
                    crop = new CropMath.Rect(crop.left + dx, crop.top + dy, crop.size);
                    crop = clampToView(crop);
                    lastX = x;
                    lastY = y;
                    invalidate();
                    if (listener != null) listener.onCropChanged();
                } else if (dragMode == 2) {
                    int cx = crop.left + crop.size / 2;
                    int cy = crop.top + crop.size / 2;
                    int half = Math.max(Math.abs(Math.round(x) - cx), Math.abs(Math.round(y) - cy));
                    int size = Math.max(1, half * 2);
                    crop = clampToView(new CropMath.Rect(cx - size / 2, cy - size / 2, size));
                    invalidate();
                    if (listener != null) listener.onCropChanged();
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = 0;
                return true;
            default:
                return false;
        }
    }

    private CropMath.Rect clampToView(CropMath.Rect r) {
        int shortSide = Math.min(viewW, viewH);
        int minSize = Math.max(1, Math.round(shortSide * CropMath.MIN_FRACTION));
        int size = Math.max(minSize, Math.min(shortSide, r.size));
        int left = Math.max(0, Math.min(viewW - size, r.left));
        int top = Math.max(0, Math.min(viewH - size, r.top));
        return new CropMath.Rect(left, top, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (crop == null) return;
        int w = getWidth();
        int h = getHeight();
        int saved = canvas.saveLayer(0, 0, w, h, null);
        canvas.drawRect(0, 0, w, h, dimPaint);
        canvas.drawRect(crop.left, crop.top, crop.left + crop.size, crop.top + crop.size,
                clearPaint);
        canvas.restoreToCount(saved);
        canvas.drawRect(crop.left, crop.top, crop.left + crop.size, crop.top + crop.size,
                edgePaint);
        float hr = 14f;
        float[][] corners = {
                {crop.left, crop.top},
                {crop.left + crop.size, crop.top},
                {crop.left, crop.top + crop.size},
                {crop.left + crop.size, crop.top + crop.size},
        };
        for (float[] c : corners) {
            canvas.drawCircle(c[0], c[1], hr, handlePaint);
        }
    }
}
