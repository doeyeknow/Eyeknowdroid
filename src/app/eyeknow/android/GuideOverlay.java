package app.eyeknow.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.View;

/**
 * The eye guide: a dimmed surround with a clear circle in the
 * middle, a ring to center the eye in, and a tap-to-focus ring
 * that fades. Drawn over the preview; it never takes touches.
 */
public class GuideOverlay extends View {

    private final Paint dimPaint = new Paint();
    private final Paint clearPaint = new Paint();
    private final Paint ringPaint = new Paint();
    private final Paint focusPaint = new Paint();

    private float focusX = -1;
    private float focusY = -1;
    private long focusShownAt;

    public GuideOverlay(Context context) {
        super(context);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
        setWillNotDraw(false);
        dimPaint.setColor(0x88000000);
        clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        ringPaint.setColor(Color.WHITE);
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(4f);
        ringPaint.setAntiAlias(true);
        focusPaint.setColor(0xFF4ADE80);
        focusPaint.setStyle(Paint.Style.STROKE);
        focusPaint.setStrokeWidth(4f);
        focusPaint.setAntiAlias(true);
    }

    public void showFocus(float x, float y) {
        focusX = x;
        focusY = y;
        focusShownAt = System.currentTimeMillis();
        invalidate();
        postDelayed(this::invalidate, 900);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) return;
        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) * 0.34f;
        int saved = canvas.saveLayer(0, 0, w, h, null);
        canvas.drawRect(0, 0, w, h, dimPaint);
        canvas.drawCircle(cx, cy, radius, clearPaint);
        canvas.restoreToCount(saved);
        canvas.drawCircle(cx, cy, radius, ringPaint);
        // Small center tick so the pupil has a target.
        canvas.drawLine(cx - 18, cy, cx + 18, cy, ringPaint);
        canvas.drawLine(cx, cy - 18, cx, cy + 18, ringPaint);
        if (focusX >= 0 && System.currentTimeMillis() - focusShownAt < 900) {
            float r = 70f;
            canvas.drawRect(focusX - r, focusY - r, focusX + r, focusY + r, focusPaint);
        }
    }
}
