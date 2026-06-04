package com.termux.app.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import com.termux.R;

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
    public static final int GRID = 13;
    public static final int LINK = 14;
    public static final int SHARE = 15;
    public static final int REFRESH = 16;
    public static final int POWER = 17;
    public static final int FULLSCREEN = 18;
    public static final int FULLSCREEN_EXIT = 19;
    public static final int PALETTE = 20;
    public static final int SCREEN_ON = 21;
    public static final int FLOAT = 22;
    public static final int USER = 23;
    public static final int KEY = 24;
    public static final int SETTINGS = 25;
    public static final int HELP = 26;
    public static final int REPORT = 27;

    private final int mIcon;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private int mColor = TpChrome.TEXT;
    private int mActiveColor = TpChrome.ACCENT;
    private boolean mActive;

    public TpIconView(Context context, int icon) {
        super(context);
        mIcon = icon;
        init(context, false);
    }

    public TpIconView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TpIconView);
        mIcon = a.getInt(R.styleable.TpIconView_tpIcon, MENU);
        boolean framed = a.getBoolean(R.styleable.TpIconView_tpFramed, false);
        a.recycle();
        init(context, framed);
    }

    public TpIconView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TpIconView, defStyleAttr, 0);
        mIcon = a.getInt(R.styleable.TpIconView_tpIcon, MENU);
        boolean framed = a.getBoolean(R.styleable.TpIconView_tpFramed, false);
        a.recycle();
        init(context, framed);
    }

    private void init(Context context, boolean framed) {
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        mPaint.setStrokeJoin(Paint.Join.ROUND);
        mPaint.setStrokeWidth(Math.max(2f, TpChrome.strokePx(context)));
        if (framed) {
            setBackground(TpChrome.roundRect(TpChrome.GLASS_BG, TpChrome.dp(context, 8),
                TpChrome.HAIRLINE, Math.max(1, TpChrome.dp(context, 1))));
        } else {
            setBackground(TpChrome.pressBg(context, mIcon == CLOSE || mIcon == TRASH));
        }
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
            case GRID:
                drawGrid(canvas, cx, cy, r);
                break;
            case LINK:
                drawLink(canvas, cx, cy, r);
                break;
            case SHARE:
                drawShare(canvas, cx, cy, r);
                break;
            case REFRESH:
                drawRefresh(canvas, cx, cy, r);
                break;
            case POWER:
                drawPower(canvas, cx, cy, r);
                break;
            case FULLSCREEN:
                drawFullscreen(canvas, cx, cy, r, false);
                break;
            case FULLSCREEN_EXIT:
                drawFullscreen(canvas, cx, cy, r, true);
                break;
            case PALETTE:
                drawPalette(canvas, cx, cy, r);
                break;
            case SCREEN_ON:
                drawScreen(canvas, cx, cy, r);
                break;
            case FLOAT:
                drawFloat(canvas, cx, cy, r);
                break;
            case USER:
                drawUser(canvas, cx, cy, r);
                break;
            case KEY:
                drawKey(canvas, cx, cy, r);
                break;
            case SETTINGS:
                drawSettings(canvas, cx, cy, r);
                break;
            case HELP:
                drawHelp(canvas, cx, cy, r);
                break;
            case REPORT:
                drawReport(canvas, cx, cy, r);
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

    private void drawGrid(Canvas canvas, float cx, float cy, float r) {
        float s = r * 0.58f;
        float gap = r * 0.34f;
        drawTinySquare(canvas, cx - s - gap / 2f, cy - s - gap / 2f, s);
        drawTinySquare(canvas, cx + gap / 2f, cy - s - gap / 2f, s);
        drawTinySquare(canvas, cx - s - gap / 2f, cy + gap / 2f, s);
        drawTinySquare(canvas, cx + gap / 2f, cy + gap / 2f, s);
    }

    private void drawTinySquare(Canvas canvas, float left, float top, float size) {
        float rad = size * 0.22f;
        canvas.drawRoundRect(left, top, left + size, top + size, rad, rad, mPaint);
    }

    private void drawLink(Canvas canvas, float cx, float cy, float r) {
        canvas.save();
        canvas.rotate(-28f, cx, cy);
        float w = r * 1.15f;
        float h = r * 0.68f;
        float rad = h * 0.5f;
        mRect.set(cx - w * 1.05f, cy - h * 0.5f, cx + w * 0.15f, cy + h * 0.5f);
        canvas.drawRoundRect(mRect, rad, rad, mPaint);
        mRect.set(cx - w * 0.15f, cy - h * 0.5f, cx + w * 1.05f, cy + h * 0.5f);
        canvas.drawRoundRect(mRect, rad, rad, mPaint);
        canvas.restore();
    }

    private void drawShare(Canvas canvas, float cx, float cy, float r) {
        float x0 = cx - r * 0.95f, y0 = cy;
        float x1 = cx + r * 0.75f, y1 = cy - r * 0.85f;
        float x2 = cx + r * 0.75f, y2 = cy + r * 0.85f;
        canvas.drawLine(x0, y0, x1, y1, mPaint);
        canvas.drawLine(x0, y0, x2, y2, mPaint);
        canvas.drawCircle(x0, y0, r * 0.22f, mPaint);
        canvas.drawCircle(x1, y1, r * 0.22f, mPaint);
        canvas.drawCircle(x2, y2, r * 0.22f, mPaint);
    }

    private void drawRefresh(Canvas canvas, float cx, float cy, float r) {
        mRect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(mRect, -35f, 285f, false, mPaint);
        canvas.drawLine(cx + r * 0.95f, cy - r * 0.2f, cx + r * 0.95f, cy - r * 0.95f, mPaint);
        canvas.drawLine(cx + r * 0.95f, cy - r * 0.2f, cx + r * 0.28f, cy - r * 0.24f, mPaint);
    }

    private void drawPower(Canvas canvas, float cx, float cy, float r) {
        canvas.drawLine(cx, cy - r, cx, cy - r * 0.08f, mPaint);
        mRect.set(cx - r, cy - r * 0.52f, cx + r, cy + r * 1.18f);
        canvas.drawArc(mRect, 132f, 276f, false, mPaint);
    }

    private void drawFullscreen(Canvas canvas, float cx, float cy, float r, boolean inward) {
        float o = r;
        float i = r * 0.42f;
        if (!inward) {
            drawCorner(canvas, cx - o, cy - o, cx - i, cy - o, cx - o, cy - i);
            drawCorner(canvas, cx + o, cy - o, cx + i, cy - o, cx + o, cy - i);
            drawCorner(canvas, cx - o, cy + o, cx - i, cy + o, cx - o, cy + i);
            drawCorner(canvas, cx + o, cy + o, cx + i, cy + o, cx + o, cy + i);
        } else {
            drawCorner(canvas, cx - i, cy - i, cx - o, cy - i, cx - i, cy - o);
            drawCorner(canvas, cx + i, cy - i, cx + o, cy - i, cx + i, cy - o);
            drawCorner(canvas, cx - i, cy + i, cx - o, cy + i, cx - i, cy + o);
            drawCorner(canvas, cx + i, cy + i, cx + o, cy + i, cx + i, cy + o);
        }
    }

    private void drawCorner(Canvas canvas, float x, float y, float x2, float y2, float x3, float y3) {
        canvas.drawLine(x, y, x2, y2, mPaint);
        canvas.drawLine(x, y, x3, y3, mPaint);
    }

    private void drawPalette(Canvas canvas, float cx, float cy, float r) {
        mRect.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(mRect, 25f, 300f, false, mPaint);
        canvas.drawCircle(cx - r * 0.4f, cy - r * 0.2f, r * 0.12f, mPaint);
        canvas.drawCircle(cx, cy - r * 0.48f, r * 0.12f, mPaint);
        canvas.drawCircle(cx + r * 0.42f, cy - r * 0.12f, r * 0.12f, mPaint);
        canvas.drawLine(cx + r * 0.25f, cy + r * 0.62f, cx + r * 0.72f, cy + r * 0.62f, mPaint);
    }

    private void drawScreen(Canvas canvas, float cx, float cy, float r) {
        float rad = r * 0.22f;
        canvas.drawRoundRect(cx - r, cy - r * 0.68f, cx + r, cy + r * 0.58f, rad, rad, mPaint);
        canvas.drawLine(cx - r * 0.35f, cy + r, cx + r * 0.35f, cy + r, mPaint);
        canvas.drawLine(cx, cy + r * 0.58f, cx, cy + r, mPaint);
    }

    private void drawFloat(Canvas canvas, float cx, float cy, float r) {
        float rad = r * 0.22f;
        canvas.drawRoundRect(cx - r, cy - r * 0.72f, cx + r, cy + r * 0.72f, rad, rad, mPaint);
        canvas.drawRoundRect(cx - r * 0.1f, cy - r * 0.34f, cx + r * 0.82f, cy + r * 0.36f, rad, rad, mPaint);
    }

    private void drawUser(Canvas canvas, float cx, float cy, float r) {
        canvas.drawCircle(cx, cy - r * 0.42f, r * 0.38f, mPaint);
        mRect.set(cx - r, cy - r * 0.03f, cx + r, cy + r * 1.38f);
        canvas.drawArc(mRect, 205f, 130f, false, mPaint);
    }

    private void drawKey(Canvas canvas, float cx, float cy, float r) {
        canvas.drawCircle(cx - r * 0.42f, cy, r * 0.42f, mPaint);
        canvas.drawLine(cx, cy, cx + r, cy, mPaint);
        canvas.drawLine(cx + r * 0.55f, cy, cx + r * 0.55f, cy + r * 0.35f, mPaint);
        canvas.drawLine(cx + r * 0.82f, cy, cx + r * 0.82f, cy + r * 0.25f, mPaint);
    }

    private void drawSettings(Canvas canvas, float cx, float cy, float r) {
        canvas.drawCircle(cx, cy, r * 0.45f, mPaint);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * i / 4.0;
            float x1 = cx + (float) Math.cos(a) * r * 0.68f;
            float y1 = cy + (float) Math.sin(a) * r * 0.68f;
            float x2 = cx + (float) Math.cos(a) * r;
            float y2 = cy + (float) Math.sin(a) * r;
            canvas.drawLine(x1, y1, x2, y2, mPaint);
        }
    }

    private void drawHelp(Canvas canvas, float cx, float cy, float r) {
        mRect.set(cx - r * 0.65f, cy - r, cx + r * 0.65f, cy + r * 0.2f);
        canvas.drawArc(mRect, 205f, 260f, false, mPaint);
        canvas.drawLine(cx + r * 0.18f, cy + r * 0.1f, cx, cy + r * 0.42f, mPaint);
        canvas.drawPoint(cx, cy + r * 0.88f, mPaint);
    }

    private void drawReport(Canvas canvas, float cx, float cy, float r) {
        canvas.drawLine(cx - r * 0.72f, cy - r, cx - r * 0.72f, cy + r, mPaint);
        mPath.reset();
        mPath.moveTo(cx - r * 0.72f, cy - r);
        mPath.lineTo(cx + r * 0.72f, cy - r * 0.62f);
        mPath.lineTo(cx - r * 0.72f, cy - r * 0.18f);
        canvas.drawPath(mPath, mPaint);
    }
}
