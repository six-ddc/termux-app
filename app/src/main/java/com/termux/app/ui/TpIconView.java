package com.termux.app.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * A single HUD control glyph, stroke-drawn with a {@link Paint} rather than
 * typeset — so it stays crisp at any size and never depends on a font having
 * the glyph. Borderless rounded press state, optional green "active" tint.
 *
 * <p>Generalises the floating terminal's original ControlIconView so the whole
 * app can share one icon language.
 */
public class TpIconView extends View {

    public static final int CLOSE = 0;
    public static final int ADD = 1;
    public static final int MINIMIZE = 2;
    public static final int MAXIMIZE = 3;
    public static final int MENU = 4;
    public static final int BRACES = 5;
    public static final int RUN = 6;
    public static final int INSERT = 7;
    public static final int EDIT = 8;
    public static final int TRASH = 9;
    public static final int SEARCH = 10;
    public static final int BACK = 11;
    public static final int CHEVRON_DOWN = 12;

    private final int mIcon;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private int mColor = TpChrome.TEXT;
    private int mActiveColor = TpChrome.ACCENT;
    private boolean mActive;

    public TpIconView(Context context, int icon) {
        super(context);
        mIcon = icon;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeWidth(Math.max(2f, TpChrome.strokePx(context)));
        setBackground(TpChrome.pressBg(context, icon == CLOSE || icon == TRASH));
    }

    /** Resting glyph colour. */
    public TpIconView setColor(int color) {
        mColor = color;
        invalidate();
        return this;
    }

    /** Colour used while {@link #setActive(boolean)} is true. */
    public TpIconView setActiveColor(int color) {
        mActiveColor = color;
        invalidate();
        return this;
    }

    public void setActive(boolean active) {
        if (mActive == active) return;
        mActive = active;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mPaint.setColor(mActive ? mActiveColor : mColor);
        mPaint.setStyle(Paint.Style.STROKE);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) * 0.2f;

        switch (mIcon) {
            case CLOSE:
                canvas.drawLine(cx - r, cy - r, cx + r, cy + r, mPaint);
                canvas.drawLine(cx - r, cy + r, cx + r, cy - r, mPaint);
                break;
            case ADD:
                canvas.drawLine(cx - r, cy, cx + r, cy, mPaint);
                canvas.drawLine(cx, cy - r, cx, cy + r, mPaint);
                break;
            case MINIMIZE:
                canvas.drawLine(cx - r, cy, cx + r, cy, mPaint);
                break;
            case MAXIMIZE: {
                float rad = r * 0.4f;
                canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, rad, rad, mPaint);
                break;
            }
            case MENU: {
                float gap = r * 0.72f;
                canvas.drawLine(cx - r, cy - gap, cx + r, cy - gap, mPaint);
                canvas.drawLine(cx - r, cy, cx + r, cy, mPaint);
                canvas.drawLine(cx - r, cy + gap, cx + r, cy + gap, mPaint);
                break;
            }
            case BRACES:
                drawBrace(canvas, cx, cy, r, true);
                drawBrace(canvas, cx, cy, r, false);
                break;
            case RUN:
                mPath.reset();
                mPath.moveTo(cx - r * 0.75f, cy - r);
                mPath.lineTo(cx + r, cy);
                mPath.lineTo(cx - r * 0.75f, cy + r);
                mPath.close();
                canvas.drawPath(mPath, mPaint);
                break;
            case INSERT:
                // right-pointing arrow ("send to terminal")
                canvas.drawLine(cx - r, cy, cx + r, cy, mPaint);
                canvas.drawLine(cx + r, cy, cx + r * 0.25f, cy - r * 0.6f, mPaint);
                canvas.drawLine(cx + r, cy, cx + r * 0.25f, cy + r * 0.6f, mPaint);
                break;
            case EDIT:
                drawPencil(canvas, cx, cy, r);
                break;
            case TRASH:
                drawTrash(canvas, cx, cy, r);
                break;
            case SEARCH: {
                float cr = r * 0.62f;
                float ox = cx - r * 0.22f;
                float oy = cy - r * 0.22f;
                canvas.drawCircle(ox, oy, cr, mPaint);
                float k = 0.7071f;
                canvas.drawLine(ox + cr * k, oy + cr * k, cx + r, cy + r, mPaint);
                break;
            }
            case BACK:
                mPath.reset();
                mPath.moveTo(cx + r * 0.45f, cy - r);
                mPath.lineTo(cx - r * 0.45f, cy);
                mPath.lineTo(cx + r * 0.45f, cy + r);
                canvas.drawPath(mPath, mPaint);
                break;
            case CHEVRON_DOWN:
                mPath.reset();
                mPath.moveTo(cx - r, cy - r * 0.45f);
                mPath.lineTo(cx, cy + r * 0.45f);
                mPath.lineTo(cx + r, cy - r * 0.45f);
                canvas.drawPath(mPath, mPaint);
                break;
            default:
                break;
        }
    }

    private void drawBrace(Canvas canvas, float cx, float cy, float r, boolean left) {
        float dir = left ? -1f : 1f;
        float x0 = cx + dir * r * 0.30f; // outer top/bottom tip
        float x1 = cx + dir * r * 0.72f; // vertical stem
        float xMid = cx + dir * r;       // middle pinch
        mPath.reset();
        mPath.moveTo(x0, cy - r);
        mPath.lineTo(x1, cy - r);
        mPath.lineTo(x1, cy - r * 0.18f);
        mPath.lineTo(xMid, cy);
        mPath.lineTo(x1, cy + r * 0.18f);
        mPath.lineTo(x1, cy + r);
        mPath.lineTo(x0, cy + r);
        canvas.drawPath(mPath, mPaint);
    }

    private void drawPencil(Canvas canvas, float cx, float cy, float r) {
        // diagonal shaft from lower-left nib to upper-right cap
        float nibX = cx - r, nibY = cy + r;
        float capX = cx + r * 0.62f, capY = cy - r * 0.62f;
        canvas.drawLine(nibX, nibY, capX, capY, mPaint);
        // eraser cap (short perpendicular stroke)
        float capLen = r * 0.42f;
        canvas.drawLine(capX - capLen * 0.7071f, capY - capLen * 0.7071f,
            capX + capLen * 0.7071f, capY + capLen * 0.7071f, mPaint);
        // nib tip — small notch
        canvas.drawLine(nibX, nibY, nibX + r * 0.5f, nibY - r * 0.18f, mPaint);
        canvas.drawLine(nibX, nibY, nibX + r * 0.18f, nibY - r * 0.5f, mPaint);
    }

    private void drawTrash(Canvas canvas, float cx, float cy, float r) {
        float lidY = cy - r * 0.55f;
        // lid
        canvas.drawLine(cx - r, lidY, cx + r, lidY, mPaint);
        // handle
        canvas.drawLine(cx - r * 0.35f, lidY, cx - r * 0.35f, cy - r, mPaint);
        canvas.drawLine(cx + r * 0.35f, lidY, cx + r * 0.35f, cy - r, mPaint);
        canvas.drawLine(cx - r * 0.35f, cy - r, cx + r * 0.35f, cy - r, mPaint);
        // body (tapered)
        float bodyBottom = cy + r;
        canvas.drawLine(cx - r * 0.72f, lidY, cx - r * 0.56f, bodyBottom, mPaint);
        canvas.drawLine(cx + r * 0.72f, lidY, cx + r * 0.56f, bodyBottom, mPaint);
        canvas.drawLine(cx - r * 0.56f, bodyBottom, cx + r * 0.56f, bodyBottom, mPaint);
        // ribs
        canvas.drawLine(cx, lidY + r * 0.45f, cx, bodyBottom - r * 0.18f, mPaint);
    }
}
