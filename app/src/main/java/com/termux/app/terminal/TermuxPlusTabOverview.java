package com.termux.app.terminal;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalEngine;
import com.termux.terminal.TerminalRenderSnapshot;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen session switcher. Each page shows four terminal previews in a 2x2
 * grid; each preview renders from the terminal's top-left cell and clips to the
 * card bounds, matching the main terminal's anchoring instead of letterboxing.
 */
public class TermuxPlusTabOverview {

    private static final int SESSIONS_PER_PAGE = 4;
    private static final int PREVIEW_REFRESH_INTERVAL_MS = 1000;

    private final TermuxActivity mActivity;
    private final float mDensity;
    private final Typeface mTerminalTypeface;
    private Dialog mDialog;
    private TextView mPageLabel;
    private LinearLayout mPageDots;

    public TermuxPlusTabOverview(TermuxActivity activity) {
        mActivity = activity;
        mDensity = activity.getResources().getDisplayMetrics().density;
        mTerminalTypeface = TermuxTerminalFontManager.loadTerminalTypeface(activity);
    }

    public void show() {
        mDialog = new Dialog(mActivity);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mDialog.setContentView(buildContent());
        mDialog.setCanceledOnTouchOutside(true);
        mDialog.show();

        Window window = mDialog.getWindow();
        if (window != null) {
            int surface = color(R.color.termuxplus_surface);
            window.setBackgroundDrawable(new ColorDrawable(surface));
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(surface);
            window.setNavigationBarColor(surface);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                window.setNavigationBarContrastEnforced(false);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
    }

    private void dismiss() {
        if (mDialog != null) mDialog.dismiss();
    }

    private View buildContent() {
        List<TerminalSession> sessions = currentSessions();
        if (sessions.isEmpty()) {
            dismiss();
            return new View(mActivity);
        }

        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.termuxplus_surface));
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        applyRootInsets(root);

        root.addView(buildHeader(sessions), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(46)));

        ViewPager pager = new ViewPager(mActivity);
        int initialPage = currentSessionPage(sessions);
        pager.setClipToPadding(false);
        pager.setPageMargin(dp(10));
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(new SessionPreviewPagerAdapter(sessions));
        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                updatePageIndicator(position, pageCount(sessions));
            }
        });
        pager.setCurrentItem(initialPage, false);
        root.addView(pager, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        updatePageIndicator(initialPage, pageCount(sessions));

        return root;
    }

    private void applyRootInsets(View root) {
        int basePadding = dp(8);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(basePadding, basePadding + insets.getSystemWindowInsetTop(),
                basePadding, basePadding);
            return insets;
        });
        root.requestApplyInsets();
    }

    private View buildHeader(List<TerminalSession> sessions) {
        LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), 0, dp(2), 0);

        LinearLayout titleBlock = new LinearLayout(mActivity);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(mActivity);
        title.setText(R.string.termuxplus_overview_title);
        title.setTextColor(color(R.color.termuxplus_text_primary));
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        titleBlock.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView count = new TextView(mActivity);
        count.setText(sessions.size() + (sessions.size() == 1 ? " session" : " sessions"));
        count.setTextColor(color(R.color.termuxplus_text_muted));
        count.setTextSize(11);
        count.setSingleLine(true);
        titleBlock.addView(count, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        header.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        mPageLabel = new TextView(mActivity);
        mPageLabel.setTextColor(color(R.color.termuxplus_text_muted));
        mPageLabel.setTextSize(11);
        mPageLabel.setGravity(Gravity.CENTER);
        mPageLabel.setSingleLine(true);
        LinearLayout.LayoutParams pageLabelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        pageLabelParams.setMarginEnd(dp(6));
        header.addView(mPageLabel, pageLabelParams);

        mPageDots = new LinearLayout(mActivity);
        mPageDots.setOrientation(LinearLayout.HORIZONTAL);
        mPageDots.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dotsParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
        dotsParams.setMarginEnd(dp(8));
        header.addView(mPageDots, dotsParams);

        ImageButton newButton = headerIconButton(R.drawable.ic_tp_add, R.string.termuxplus_overview_new);
        newButton.setColorFilter(color(R.color.termuxplus_accent));
        newButton.setOnClickListener(v -> {
            dismiss();
            mActivity.getTermuxTerminalSessionClient().addNewSession(false, null);
        });
        LinearLayout.LayoutParams newParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        newParams.setMarginEnd(dp(4));
        header.addView(newButton, newParams);

        ImageButton close = headerIconButton(R.drawable.ic_tp_close, android.R.string.cancel);
        close.setColorFilter(color(R.color.termuxplus_text_primary));
        close.setOnClickListener(v -> dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));

        return header;
    }

    private ImageButton headerIconButton(int iconResId, int descriptionResId) {
        ImageButton button = new ImageButton(mActivity);
        button.setImageResource(iconResId);
        button.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_header_button_bg));
        button.setContentDescription(mActivity.getString(descriptionResId));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(9), dp(9), dp(9), dp(9));
        return button;
    }

    private void updatePageIndicator(int page, int pageCount) {
        if (mPageDots == null || mPageLabel == null) return;
        boolean hasMultiplePages = pageCount > 1;
        mPageLabel.setVisibility(hasMultiplePages ? View.VISIBLE : View.GONE);
        mPageDots.setVisibility(hasMultiplePages ? View.VISIBLE : View.GONE);
        mPageDots.removeAllViews();
        if (!hasMultiplePages) return;

        mPageLabel.setText((page + 1) + " / " + pageCount);

        for (int i = 0; i < pageCount; i++) {
            boolean active = i == page;
            View dot = new View(mActivity);
            dot.setBackground(pageDotBackground(active));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                active ? dp(16) : dp(5), dp(5));
            params.setMarginStart(dp(2));
            params.setMarginEnd(dp(2));
            mPageDots.addView(dot, params);
        }
    }

    private class SessionPreviewPagerAdapter extends PagerAdapter {
        private final List<TerminalSession> mSessions;

        SessionPreviewPagerAdapter(List<TerminalSession> sessions) {
            mSessions = sessions;
        }

        @Override
        public int getCount() {
            return pageCount(mSessions);
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup container, int position) {
            View page = buildPreviewPage(mSessions, position);
            container.addView(page);
            return page;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup container, int position, @NonNull Object object) {
            container.removeView((View) object);
        }
    }

    private View buildPreviewPage(List<TerminalSession> sessions, int page) {
        GridLayout grid = new GridLayout(mActivity);
        grid.setColumnCount(2);
        grid.setRowCount(2);
        grid.setUseDefaultMargins(false);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setPadding(0, dp(4), 0, 0);

        int firstIndex = page * SESSIONS_PER_PAGE;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                int sessionIndex = firstIndex + (row * 2) + col;
                View cell = buildCell(sessions, sessionIndex);
                GridLayout.LayoutParams cellParams = new GridLayout.LayoutParams(
                    GridLayout.spec(row, 1, GridLayout.FILL, 1f),
                    GridLayout.spec(col, 1, GridLayout.FILL, 1f));
                cellParams.width = 0;
                cellParams.height = 0;
                cellParams.setMargins(
                    col == 0 ? 0 : dp(4),
                    row == 0 ? 0 : dp(4),
                    col == 0 ? dp(4) : 0,
                    row == 0 ? dp(4) : 0);
                grid.addView(cell, cellParams);
            }
        }

        return grid;
    }

    private View buildCell(List<TerminalSession> sessions, int sessionIndex) {
        if (sessionIndex < sessions.size())
            return buildSessionPreviewCard(sessionIndex, sessions.get(sessionIndex));

        return buildEmptyCell();
    }

    private View buildSessionPreviewCard(int index, TerminalSession session) {
        boolean current = session == mActivity.getCurrentSession();

        LinearLayout card = new LinearLayout(mActivity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(previewCardBackground(current));
        card.setPadding(0, 0, 0, 0);
        card.setContentDescription(cardTitle(index, session) + ", " + statusText(session));
        card.setOnClickListener(v -> {
            dismiss();
            mActivity.getTermuxTerminalSessionClient().setCurrentSession(session);
        });

        card.addView(buildCardHeader(index, session), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        FrameLayout previewSurface = new FrameLayout(mActivity);
        previewSurface.setBackground(previewSurfaceBackground());
        previewSurface.setPadding(dp(7), dp(6), dp(7), dp(6));

        TerminalSnapshotPreviewView preview = new TerminalSnapshotPreviewView(mActivity, session);
        preview.setEmptyText(mActivity.getString(R.string.termuxplus_overview_empty));
        previewSurface.addView(preview, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        previewParams.setMargins(dp(7), 0, dp(7), dp(7));
        card.addView(previewSurface, previewParams);

        return card;
    }

    private View buildCardHeader(int index, TerminalSession session) {
        LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(8), 0, dp(8), 0);

        View dot = new View(mActivity);
        GradientDrawable dotShape = new GradientDrawable();
        dotShape.setShape(GradientDrawable.OVAL);
        dotShape.setColor(statusColor(session));
        dot.setBackground(dotShape);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(6), dp(6));
        dotParams.setMarginEnd(dp(7));
        header.addView(dot, dotParams);

        TextView title = new TextView(mActivity);
        title.setText(cardTitle(index, session));
        title.setTextColor(color(R.color.termuxplus_text_primary));
        title.setTextSize(11.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView status = new TextView(mActivity);
        status.setText(statusText(session));
        status.setTextColor(statusColor(session));
        status.setTextSize(9.5f);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMarginStart(dp(6));
        header.addView(status, statusParams);

        return header;
    }

    private View buildEmptyCell() {
        FrameLayout empty = new FrameLayout(mActivity);
        empty.setBackground(emptyCellBackground());
        empty.setAlpha(0.08f);
        return empty;
    }

    private GradientDrawable previewCardBackground(boolean current) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color(R.color.termuxplus_card_bg));
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1),
            color(current ? R.color.termuxplus_run : R.color.termuxplus_card_border));
        return drawable;
    }

    private GradientDrawable previewSurfaceBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.BLACK);
        drawable.setCornerRadius(dp(4));
        drawable.setStroke(dp(1), color(R.color.termuxplus_outline_subtle));
        return drawable;
    }

    private GradientDrawable emptyCellBackground() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(dp(1), color(R.color.termuxplus_card_border));
        return drawable;
    }

    private GradientDrawable pageDotBackground(boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color(active ? R.color.termuxplus_run : R.color.termuxplus_text_muted));
        drawable.setCornerRadius(dp(4));
        return drawable;
    }

    private int pageCount(List<TerminalSession> sessions) {
        return Math.max(1, (sessions.size() + SESSIONS_PER_PAGE - 1) / SESSIONS_PER_PAGE);
    }

    private int currentSessionPage(List<TerminalSession> sessions) {
        TerminalSession currentSession = mActivity.getCurrentSession();
        if (currentSession == null) return 0;
        int currentIndex = sessions.indexOf(currentSession);
        if (currentIndex < 0) return 0;
        return currentIndex / SESSIONS_PER_PAGE;
    }

    private List<TerminalSession> currentSessions() {
        List<TerminalSession> result = new ArrayList<>();
        if (mActivity.getTermuxService() == null) return result;
        List<TermuxSession> sessions = mActivity.getTermuxService().getTermuxSessions();
        if (sessions == null) return result;
        for (TermuxSession s : sessions) {
            if (s == null) continue;
            TerminalSession ts = s.getTerminalSession();
            if (ts != null) result.add(ts);
        }
        return result;
    }

    private String cardTitle(int index, TerminalSession session) {
        if (session.mSessionName != null && !session.mSessionName.isEmpty()) return session.mSessionName;
        String title = session.getTitle();
        if (title != null && !title.isEmpty()) return title;
        return "Session " + (index + 1);
    }

    private String statusText(TerminalSession session) {
        if (session.isRunning()) return mActivity.getString(R.string.termuxplus_session_running);
        return mActivity.getString(R.string.termuxplus_session_exited) + " (" + session.getExitStatus() + ")";
    }

    private int statusColor(TerminalSession session) {
        if (session.isRunning()) return color(R.color.termuxplus_run);
        if (session.getExitStatus() != 0) return color(R.color.termuxplus_text_error);
        return color(R.color.termuxplus_text_muted);
    }

    private int color(int colorResId) {
        return ContextCompat.getColor(mActivity, colorResId);
    }

    private int dp(int value) {
        return Math.round(value * mDensity);
    }

    private class TerminalSnapshotPreviewView extends View {
        private final TerminalSession mSession;
        private final Paint mCellPaint = new Paint();
        private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final Runnable mRefreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isAttachedToWindow()) return;
                invalidate();
                mHandler.postDelayed(this, PREVIEW_REFRESH_INTERVAL_MS);
            }
        };
        private TerminalRenderer mRenderer;
        private String mEmptyText;

        TerminalSnapshotPreviewView(Context context, TerminalSession session) {
            super(context);
            mSession = session;
            mTextPaint.setSubpixelText(true);
            mTextPaint.setTextAlign(Paint.Align.LEFT);
        }

        void setEmptyText(String emptyText) {
            mEmptyText = emptyText;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mHandler.post(mRefreshRunnable);
        }

        @Override
        protected void onDetachedFromWindow() {
            mHandler.removeCallbacks(mRefreshRunnable);
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            TerminalEngine engine = mSession == null ? null : mSession.getTerminalEngine();
            TerminalRenderSnapshot snapshot = engine == null ? null : engine.getRenderSnapshot();
            if (snapshot == null || snapshot.cells == null || snapshot.colors == null ||
                snapshot.columns <= 0 || snapshot.rows <= 0) {
                drawEmpty(canvas);
                return;
            }

            int columns = snapshot.columns;
            int rows = snapshot.rows;
            int requiredCells = columns * rows * TerminalEngine.RENDER_CELL_STRIDE;
            if (snapshot.cells.length < requiredCells) {
                drawEmpty(canvas);
                return;
            }

            TerminalRenderer renderer = getPreviewRenderer(columns);
            float cellWidth = renderer.getFontWidth();
            int cellHeight = renderer.getFontLineSpacing();
            if (cellWidth <= 0 || cellHeight <= 0) return;

            int defaultBackground = snapshot.colors[
                snapshot.reverseVideo ? TextStyle.COLOR_INDEX_FOREGROUND : TextStyle.COLOR_INDEX_BACKGROUND];
            mCellPaint.setColor(defaultBackground);
            canvas.drawRect(0, 0, getWidth(), getHeight(), mCellPaint);

            canvas.save();
            canvas.clipRect(0, 0, getWidth(), getHeight());
            drawCellBackgrounds(canvas, snapshot, columns, rows, cellWidth, cellHeight, defaultBackground);
            drawCellText(canvas, renderer, snapshot, columns, rows, cellWidth, cellHeight);
            canvas.restore();
        }

        private TerminalRenderer getPreviewRenderer(int columns) {
            int targetTextSize = resolvePreviewTextSize(columns);
            if (mRenderer == null || mRenderer.getTextSize() != targetTextSize)
                mRenderer = new TerminalRenderer(targetTextSize, mTerminalTypeface);
            return mRenderer;
        }

        private int resolvePreviewTextSize(int columns) {
            int preferred = mActivity.getPreferences() == null
                ? TermuxAppSharedPreferences.getDefaultFontSizes(mActivity)[0]
                : mActivity.getPreferences().getFontSize();
            int readableMinimum = TermuxAppSharedPreferences.getDefaultFontSizes(mActivity)[1];
            if (columns <= 0 || getWidth() <= 0)
                return Math.max(readableMinimum, preferred);

            int low = Math.max(1, readableMinimum);
            int high = Math.max(low, preferred);
            int best = low;
            while (low <= high) {
                int mid = (low + high) / 2;
                TerminalRenderer renderer = new TerminalRenderer(mid, mTerminalTypeface);
                if (renderer.getFontWidth() * columns <= getWidth()) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            return Math.max(readableMinimum, best);
        }

        private void drawCellBackgrounds(Canvas canvas, TerminalRenderSnapshot snapshot, int columns, int rows,
                                         float cellWidth, int cellHeight, int defaultBackground) {
            int visibleRows = Math.min(rows, (int) Math.ceil(getHeight() / (float) Math.max(1, cellHeight)));
            for (int row = 0; row < visibleRows; row++) {
                for (int column = 0; column < columns && column * cellWidth < getWidth(); ) {
                    int base = renderCellBase(row, column, columns);
                    int widthColumns = normalizedCellWidth(snapshot, base, column, columns);
                    if (widthColumns <= 0) {
                        column++;
                        continue;
                    }
                    int effect = snapshot.cells[base + TerminalEngine.RENDER_CELL_EFFECT];
                    boolean selected = snapshot.cells[base + TerminalEngine.RENDER_CELL_SELECTED] != 0;
                    boolean cursor = isCursorCell(snapshot, row, column);
                    int foreground = resolveCellForeground(snapshot.cells[base + TerminalEngine.RENDER_CELL_FOREGROUND],
                        effect, snapshot.colors);
                    int background = resolveCellBackground(snapshot.cells[base + TerminalEngine.RENDER_CELL_BACKGROUND],
                        snapshot.colors);
                    if (shouldSwapColors(snapshot.reverseVideo, effect, selected, cursor)) {
                        int swap = foreground;
                        foreground = background;
                        background = swap;
                    }
                    if (background != defaultBackground || selected || cursor) {
                        mCellPaint.setColor(background);
                        canvas.drawRect(column * cellWidth, row * cellHeight,
                            (column + widthColumns) * cellWidth, (row + 1) * cellHeight, mCellPaint);
                    }
                    column += widthColumns;
                }
            }
        }

        private void drawCellText(Canvas canvas, TerminalRenderer renderer, TerminalRenderSnapshot snapshot,
                                  int columns, int rows, float cellWidth, int cellHeight) {
            renderer.copyGlyphPaintTo(mTextPaint);
            mTextPaint.setSubpixelText(true);
            mTextPaint.setTextAlign(Paint.Align.LEFT);
            float baselineOffset = cellHeight - renderer.getFontLineSpacingAndAscent();

            int visibleRows = Math.min(rows, (int) Math.ceil(getHeight() / (float) Math.max(1, cellHeight)));
            for (int row = 0; row < visibleRows; row++) {
                for (int column = 0; column < columns && column * cellWidth < getWidth(); ) {
                    int base = renderCellBase(row, column, columns);
                    int widthColumns = normalizedCellWidth(snapshot, base, column, columns);
                    if (widthColumns <= 0) {
                        column++;
                        continue;
                    }

                    int codePoint = snapshot.cells[base + TerminalEngine.RENDER_CELL_CODEPOINT];
                    int cellIndex = row * columns + column;
                    String text = snapshot.cellText != null && cellIndex < snapshot.cellText.length
                        ? snapshot.cellText[cellIndex]
                        : null;
                    if (text == null && codePoint > 0 && codePoint != ' ')
                        text = new String(Character.toChars(codePoint));

                    int effect = snapshot.cells[base + TerminalEngine.RENDER_CELL_EFFECT];
                    boolean hidden = (effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) != 0;
                    if (!hidden && text != null) {
                        boolean selected = snapshot.cells[base + TerminalEngine.RENDER_CELL_SELECTED] != 0;
                        boolean cursor = isCursorCell(snapshot, row, column);
                        int foreground = resolveCellForeground(snapshot.cells[base + TerminalEngine.RENDER_CELL_FOREGROUND],
                            effect, snapshot.colors);
                        int background = resolveCellBackground(snapshot.cells[base + TerminalEngine.RENDER_CELL_BACKGROUND],
                            snapshot.colors);
                        if (shouldSwapColors(snapshot.reverseVideo, effect, selected, cursor)) {
                            int swap = foreground;
                            foreground = background;
                            background = swap;
                        }
                        foreground = applyDimEffect(foreground, effect);
                        mTextPaint.setColor(foreground);
                        mTextPaint.setFakeBoldText((effect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0);
                        mTextPaint.setTextSkewX((effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0 ? -0.35f : 0f);
                        mTextPaint.setUnderlineText((effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0);
                        mTextPaint.setStrikeThruText((effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0);
                        canvas.drawText(text, column * cellWidth, row * cellHeight + baselineOffset, mTextPaint);
                    }

                    column += widthColumns;
                }
            }

            mTextPaint.setFakeBoldText(false);
            mTextPaint.setTextSkewX(0f);
            mTextPaint.setUnderlineText(false);
            mTextPaint.setStrikeThruText(false);
        }

        private void drawEmpty(Canvas canvas) {
            mCellPaint.setColor(Color.BLACK);
            canvas.drawRect(0, 0, getWidth(), getHeight(), mCellPaint);
            if (mEmptyText == null) return;
            mTextPaint.setColor(color(R.color.termuxplus_text_muted));
            mTextPaint.setTextSize(dp(10));
            mTextPaint.setFakeBoldText(false);
            mTextPaint.setTextSkewX(0f);
            mTextPaint.setUnderlineText(false);
            mTextPaint.setStrikeThruText(false);
            mTextPaint.setTextAlign(Paint.Align.CENTER);
            Paint.FontMetrics metrics = mTextPaint.getFontMetrics();
            float y = (getHeight() - metrics.ascent - metrics.descent) / 2f;
            canvas.drawText(mEmptyText, getWidth() / 2f, y, mTextPaint);
            mTextPaint.setTextAlign(Paint.Align.LEFT);
        }

        private int renderCellBase(int row, int column, int columns) {
            return (row * columns + column) * TerminalEngine.RENDER_CELL_STRIDE;
        }

        private int normalizedCellWidth(TerminalRenderSnapshot snapshot, int base, int column, int columns) {
            int widthColumns = snapshot.cells[base + TerminalEngine.RENDER_CELL_WIDTH];
            if (widthColumns <= 0)
                return 0;
            return Math.max(1, Math.min(widthColumns, columns - column));
        }

        private boolean isCursorCell(TerminalRenderSnapshot snapshot, int row, int column) {
            if (!snapshot.cursorVisibleIgnoringBlink) return false;
            int cursorColumn = snapshot.cursorWideTail ? Math.max(0, snapshot.cursorCol - 1) : snapshot.cursorCol;
            return row == snapshot.cursorRow && column == cursorColumn &&
                snapshot.cursorStyle == TerminalEngine.TERMINAL_CURSOR_STYLE_BLOCK;
        }

        private boolean shouldSwapColors(boolean reverseVideo, int effect, boolean selected, boolean blockCursor) {
            boolean swap = reverseVideo || selected || blockCursor;
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVERSE) != 0)
                swap = !swap;
            return swap;
        }

        private int resolveCellForeground(int color, int effect, int[] palette) {
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0 && color >= 0 && color < 8)
                color += 8;
            return resolveColor(color, palette);
        }

        private int resolveCellBackground(int color, int[] palette) {
            return resolveColor(color, palette);
        }

        private int resolveColor(int terminalColor, int[] palette) {
            if ((terminalColor & 0xff000000) == 0xff000000)
                return terminalColor;
            if (terminalColor >= 0 && terminalColor < palette.length)
                return palette[terminalColor];
            return Color.WHITE;
        }

        private int applyDimEffect(int color, int effect) {
            if ((effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) == 0)
                return color;
            int red = (0xFF & (color >> 16)) * 2 / 3;
            int green = (0xFF & (color >> 8)) * 2 / 3;
            int blue = (0xFF & color) * 2 / 3;
            return 0xFF000000 | (red << 16) | (green << 8) | blue;
        }
    }
}
