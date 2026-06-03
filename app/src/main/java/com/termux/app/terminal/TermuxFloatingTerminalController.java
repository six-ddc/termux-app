package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.TermuxService;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalRenderer;
import com.termux.view.TerminalView;

public final class TermuxFloatingTerminalController {

    private static final String LOG_TAG = "TermuxFloatingTerminal";
    // Low-frequency safety fallback only. Per-output refresh is event-driven via
    // refreshFromOutput() (called from the session service client); this timer
    // just catches anything not tied to terminal output (e.g. title changes).
    private static final int REFRESH_INTERVAL_MS = 1000;
    private static final int EXPANDED_PANEL_PADDING_DP = 6;
    private static final float MIN_FLOATING_FONT_SCALE = 0.75f;
    private static final int FLOATING_FONT_SIZE_STEP_PX = 2;
    /** Floating terminal background transparency (0 = opaque … 255 = fully
     * see-through). Applied to the terminal body and mirrored into the header
     * background alpha so the whole panel reads as one frosted glass surface. */
    private static final int FLOATING_TERMINAL_TRANSPARENCY = 64;

    // --- Floating chrome palette: a precision "terminal HUD" — frosted near-black
    // bars (alpha matched to the terminal body), a single phosphor-green accent
    // used only for the session marker, hairline, and active/pressed states.
    // Everything else is a muted desaturated light. ---
    private static final int CHROME_BG = Color.argb(255 - FLOATING_TERMINAL_TRANSPARENCY, 9, 13, 14);
    private static final int CHROME_HAIRLINE = Color.argb(56, 11, 201, 137);
    private static final int CHROME_TEXT = Color.argb(236, 197, 222, 213);
    private static final int CHROME_TEXT_DIM = Color.argb(128, 121, 150, 141);
    private static final int CHROME_ACCENT = Color.rgb(11, 201, 137);
    private static final int CHROME_PRESS = Color.argb(40, 11, 201, 137);
    private static final int CHROME_PRESS_CLOSE = Color.argb(50, 232, 104, 92);

    private static final int ICON_MINIMIZE = 0;
    private static final int ICON_MAXIMIZE = 1;
    private static final int ICON_CLOSE = 2;

    private final TermuxService mService;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final TermuxFloatingTerminalViewClient mTerminalViewClient = new TermuxFloatingTerminalViewClient();

    private FrameLayout mRootView;
    private TerminalView mTerminalView;
    private TextView mTitleView;
    private Typeface mTerminalTypeface;
    private WindowManager.LayoutParams mLayoutParams;
    private boolean mAttached;
    private boolean mExpanded;
    private int mLastWidth;
    private int mLastHeight;
    private int mLastAppliedTerminalFontSize = -1;
    private int mUserExpandedWidth = -1;
    private int mUserExpandedHeight = -1;

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mAttached) return;

            refreshNow();

            mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    private final Runnable mGeometryRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mAttached) return;

            refreshTerminalGeometry();
        }
    };

    /** Refresh the floating terminal once. */
    private void refreshNow() {
        attachCurrentSession();
        refreshTerminalGeometry();
        updateTitle();
    }

    /**
     * Event-driven refresh hook: called from the session service client when the
     * shared session produces output while the app is backgrounded (the only
     * time the floating terminal is shown). Replaces the old 250 ms busy poll.
     */
    public void refreshFromOutput() {
        if (!mAttached) return;
        mHandler.post(this::refreshNow);
    }

    public TermuxFloatingTerminalController(TermuxService service) {
        mService = service;
        mWindowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    public void showCollapsedIfAllowed() {
        if (mAttached && mExpanded) return;
        if (!canShowOverlay()) return;
        show(false);
    }

    public void showExpandedIfAllowed() {
        if (!canShowOverlay()) return;
        show(true);
    }

    public void hide() {
        mHandler.removeCallbacks(mRefreshRunnable);
        mHandler.removeCallbacks(mGeometryRefreshRunnable);
        if (!mAttached || mRootView == null) return;

        if (mTerminalView != null)
            mTerminalView.onPause();

        try {
            mWindowManager.removeView(mRootView);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to remove floating terminal", e);
        }

        mAttached = false;
        mExpanded = false;
        mRootView = null;
        mTerminalView = null;
        mTitleView = null;
        mLayoutParams = null;
        mTerminalTypeface = null;
        mLastAppliedTerminalFontSize = -1;
    }

    public void onSessionsChanged() {
        if (!mAttached) return;
        if (mService.getTermuxSessionsSize() == 0) {
            hide();
            return;
        }
        attachCurrentSession();
        scheduleTerminalGeometryRefresh();
        updateTitle();
    }

    private boolean canShowOverlay() {
        if (mService.getTermuxSessionsSize() == 0) return false;
        if (!PermissionUtils.checkDisplayOverOtherAppsPermission(mService)) {
            Logger.logWarn(LOG_TAG, "Display over other apps permission is not granted");
            return false;
        }
        return true;
    }

    private void show(boolean expanded) {
        mExpanded = expanded;
        mHandler.removeCallbacks(mRefreshRunnable);

        if (mRootView == null)
            mRootView = new FrameLayout(mService);

        if (mTerminalView != null)
            mTerminalView.onPause();

        mRootView.removeAllViews();
        mTerminalView = null;
        mTitleView = null;
        if (expanded)
            buildExpandedView();
        else
            buildCollapsedView();

        if (mTerminalView != null)
            mTerminalView.onResume();

        WindowManager.LayoutParams params = createLayoutParams(expanded);
        if (!mAttached) {
            try {
                mWindowManager.addView(mRootView, params);
                mAttached = true;
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to show floating terminal", e);
                hide();
                return;
            }
        } else {
            try {
                mWindowManager.updateViewLayout(mRootView, params);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to update floating terminal", e);
            }
        }

        mLayoutParams = params;
        attachCurrentSession();
        scheduleTerminalGeometryRefresh();
        updateTitle();
        mHandler.post(mRefreshRunnable);

        // Focus the terminal so hardware keys work, but do NOT pop the soft
        // keyboard on open — it shows on the first tap (see onSingleTapUp).
        if (expanded && mTerminalView != null)
            mTerminalView.requestFocus();
    }

    private WindowManager.LayoutParams createLayoutParams(boolean expanded) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        int width = expanded ? expandedWidth() : dp(44);
        int height = expanded ? expandedHeight() : dp(44);
        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!expanded)
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        if (mLayoutParams == null) {
            params.x = Math.max(dp(8), screenWidth() - width - dp(12));
            params.y = dp(96);
        } else {
            params.x = clamp(mLayoutParams.x, 0, Math.max(0, screenWidth() - width));
            params.y = clamp(mLayoutParams.y, 0, Math.max(0, screenHeight() - height));
        }

        mLastWidth = width;
        mLastHeight = height;
        return params;
    }

    private void buildCollapsedView() {
        TextView bubble = new TextView(mService);
        bubble.setText("TP");
        bubble.setGravity(Gravity.CENTER);
        bubble.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        bubble.setTextColor(Color.rgb(220, 255, 238));
        bubble.setTextSize(10);
        bubble.setBackground(makeRoundRect(Color.argb(230, 8, 18, 15), dp(14), Color.rgb(11, 201, 137), dp(1)));
        bubble.setOnTouchListener(new DragTouchListener(() -> showExpandedIfAllowed()));

        mRootView.addView(bubble, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void buildExpandedView() {
        mTerminalTypeface = TermuxTerminalFontManager.loadTerminalTypeface(mService);

        LinearLayout panel = new LinearLayout(mService);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(EXPANDED_PANEL_PADDING_DP), dp(EXPANDED_PANEL_PADDING_DP),
            dp(EXPANDED_PANEL_PADDING_DP), dp(EXPANDED_PANEL_PADDING_DP));
        // No background on the panel: the terminal renders on a media-overlay GL
        // surface that sits *below* the view layer, so anything opaque painted
        // over the terminal region (a panel background) would hide it. The header
        // carries the dark chrome; the terminal area is backed by the GL surface's
        // own opaque terminal background.

        LinearLayout header = new LinearLayout(mService);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackground(makeTopRoundRect(CHROME_BG, dp(9)));
        header.setPadding(dp(11), 0, dp(5), 0);
        header.setOnTouchListener(new DragTouchListener(null));

        mTitleView = new TextView(mService);
        mTitleView.setTextColor(CHROME_TEXT);
        mTitleView.setTextSize(11f);
        mTitleView.setTypeface(mTerminalTypeface != null ? mTerminalTypeface : Typeface.MONOSPACE);
        mTitleView.setSingleLine(true);
        mTitleView.setEllipsize(TextUtils.TruncateAt.END);
        mTitleView.setLetterSpacing(0.03f);
        mTitleView.setIncludeFontPadding(false);
        mTitleView.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(mTitleView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        header.addView(makeControlButton(ICON_MINIMIZE, () -> show(false)));
        header.addView(makeControlButton(ICON_MAXIMIZE, () -> {
            hide();
            TermuxActivity.startTermuxActivity(mService);
        }));
        header.addView(makeControlButton(ICON_CLOSE, this::hide));
        panel.addView(header, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(27)));

        View hairline = new View(mService);
        hairline.setBackgroundColor(CHROME_HAIRLINE);
        panel.addView(hairline, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1));

        mTerminalView = new TerminalView(mService, null);
        // The expanded floating terminal is the active terminal surface while
        // the main activity is backgrounded, so let it size the shared PTY to
        // its own window. The activity view will size it back on resume.
        mTerminalView.setDrivesSessionResize(true);
        mTerminalView.setTerminalViewClient(mTerminalViewClient);
        mLastAppliedTerminalFontSize = getFloatingTerminalFontSize();
        mTerminalView.setTextSize(mLastAppliedTerminalFontSize);
        mTerminalView.setTypeface(mTerminalTypeface);
        // Media overlay (not z-order-on-top): the surface composites above the
        // (transparent) window but below the view layer, so the resize handle —
        // a normal sibling view — can paint on top of the terminal and stay
        // visible and touchable.
        mTerminalView.setZOrderMediaOverlay(true);
        mTerminalView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        // Frost the terminal body so the screen behind shows through, matching the
        // header alpha. Set before the surface is created (before onResume()) so
        // the GL surface comes up translucent. Glyphs stay opaque — only the cell
        // backgrounds take the alpha — so text remains readable.
        mTerminalView.setTerminalTransparency(FLOATING_TERMINAL_TRANSPARENCY);
        mTerminalView.setFocusable(true);
        mTerminalView.setFocusableInTouchMode(true);
        mTerminalView.setBackgroundColor(Color.TRANSPARENT);
        mTerminalView.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                scheduleTerminalGeometryRefresh();
        });
        FrameLayout terminalContainer = new FrameLayout(mService);
        terminalContainer.addView(mTerminalView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        terminalContainer.addView(makeResizeHandle(), resizeHandleLayoutParams());
        panel.addView(terminalContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1));

        mRootView.addView(panel, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private View makeControlButton(int icon, Runnable action) {
        ControlIconView button = new ControlIconView(mService, icon);
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(26), dp(26));
        params.leftMargin = dp(2);
        button.setLayoutParams(params);
        return button;
    }

    private Drawable makeIconPressBackground(boolean destructive) {
        GradientDrawable pressed = new GradientDrawable();
        pressed.setColor(destructive ? CHROME_PRESS_CLOSE : CHROME_PRESS);
        pressed.setCornerRadius(dp(7));
        GradientDrawable idle = new GradientDrawable();
        idle.setColor(Color.TRANSPARENT);
        idle.setCornerRadius(dp(7));
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[0], idle);
        return states;
    }

    private View makeResizeHandle() {
        return new ResizeGripView(mService);
    }

    private FrameLayout.LayoutParams resizeHandleLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(26), dp(26));
        params.gravity = Gravity.BOTTOM | Gravity.END;
        params.setMargins(0, 0, dp(2), dp(2));
        return params;
    }

    private int getConfiguredTerminalFontSize() {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(mService);
        if (preferences != null)
            return preferences.getFontSize();

        return TermuxAppSharedPreferences.getDefaultFontSizes(mService)[0];
    }

    private int getFloatingTerminalFontSize() {
        if (mTerminalView == null)
            return getConfiguredTerminalFontSize();

        return resolveFloatingTerminalFontSize(mTerminalView.getWidth());
    }

    /**
     * Scale the floating font gently with window <em>width</em> only. The old
     * code took {@code min(widthScale, heightScale)}, but the floating window is
     * short, so the height ratio always won and pinned the font to its floor —
     * which made the terminal look like a shrunk full-screen view. Width-only
     * scaling with a 0.75 floor keeps a normal, readable terminal that simply
     * reflows to fewer columns.
     */
    private int resolveFloatingTerminalFontSize(int terminalWidth) {
        int configuredFontSize = getConfiguredTerminalFontSize();
        if (terminalWidth <= 0)
            return configuredFontSize;

        float widthScale = terminalWidth / (float) Math.max(1, screenWidth());
        float scale = Math.min(1f, Math.max(MIN_FLOATING_FONT_SCALE, widthScale));
        int targetFontSize = roundFontSizeToStep(Math.round(configuredFontSize * scale));

        int minimumFontSize = Math.max(
            TermuxAppSharedPreferences.getDefaultFontSizes(mService)[1],
            roundFontSizeToStep(Math.round(configuredFontSize * MIN_FLOATING_FONT_SCALE)));
        return clamp(targetFontSize, Math.min(configuredFontSize, minimumFontSize), configuredFontSize);
    }

    private int roundFontSizeToStep(int fontSize) {
        return Math.max(FLOATING_FONT_SIZE_STEP_PX,
            Math.round(fontSize / (float) FLOATING_FONT_SIZE_STEP_PX) * FLOATING_FONT_SIZE_STEP_PX);
    }

    private void attachCurrentSession() {
        if (mTerminalView == null) return;

        TerminalSession session = mService.getCurrentStoredTerminalSessionOrLast();
        if (session == null) return;

        mTerminalView.attachSession(session);
    }

    private void scheduleTerminalGeometryRefresh() {
        mHandler.removeCallbacks(mGeometryRefreshRunnable);
        mHandler.post(mGeometryRefreshRunnable);
    }

    private void refreshTerminalGeometry() {
        updateExpandedWindowSizeIfNeeded();

        if (mTerminalView == null) return;

        updateFloatingTerminalFontSize();
        mTerminalView.updateSize();
        mTerminalView.onScreenUpdated();
    }

    private void updateExpandedWindowSizeIfNeeded() {
        if (!mAttached || !mExpanded || mRootView == null || mLayoutParams == null) return;

        int width = expandedWidth();
        int height = expandedHeight();
        int clampedX = clamp(mLayoutParams.x, 0, Math.max(0, screenWidth() - width));
        int clampedY = clamp(mLayoutParams.y, 0, Math.max(0, screenHeight() - height));
        if (mLayoutParams.width == width && mLayoutParams.height == height
            && mLayoutParams.x == clampedX && mLayoutParams.y == clampedY) {
            mLastWidth = width;
            mLastHeight = height;
            return;
        }

        mLayoutParams.width = width;
        mLayoutParams.height = height;
        mLayoutParams.x = clampedX;
        mLayoutParams.y = clampedY;
        mLastWidth = width;
        mLastHeight = height;

        try {
            mWindowManager.updateViewLayout(mRootView, mLayoutParams);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to resize floating terminal", e);
        }
    }

    private void updateFloatingTerminalFontSize() {
        int targetFontSize = getFloatingTerminalFontSize();
        if (targetFontSize == mLastAppliedTerminalFontSize) return;

        mLastAppliedTerminalFontSize = targetFontSize;
        mTerminalView.setTextSize(targetFontSize);
    }

    private void updateTitle() {
        if (mTitleView == null) return;

        TerminalSession session = mService.getCurrentStoredTerminalSessionOrLast();
        int sessionIndex = session == null ? -1 : mService.getIndexOfSession(session);
        String name = session == null
            ? mService.getString(R.string.termuxplus_floating_terminal_title)
            : getSessionLabel(session);

        if (sessionIndex < 0) {
            mTitleView.setText(name);
            return;
        }

        // A slim accent session marker instead of bulky "[n]" brackets — the
        // index in phosphor green, then the name in muted light.
        String index = String.valueOf(sessionIndex + 1);
        SpannableStringBuilder builder = new SpannableStringBuilder();
        builder.append(index);
        builder.setSpan(new ForegroundColorSpan(CHROME_ACCENT), 0, index.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.append("  ");
        int nameStart = builder.length();
        builder.append(name);
        builder.setSpan(new ForegroundColorSpan(CHROME_TEXT), nameStart, builder.length(),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        mTitleView.setText(builder);
    }

    private String getSessionLabel(TerminalSession session) {
        if (session.mSessionName != null && !session.mSessionName.trim().isEmpty())
            return session.mSessionName;

        String title = session.getTitle();
        if (title != null && !title.trim().isEmpty())
            return title;

        return mService.getString(R.string.termuxplus_floating_terminal_title);
    }

    private void focusTerminalAndShowKeyboard() {
        if (mTerminalView == null) return;

        mTerminalView.requestFocus();
        mTerminalView.postDelayed(() -> KeyboardUtils.showSoftKeyboard(mService, mTerminalView), 150);
    }

    private GradientDrawable makeRoundRect(int color, int radius, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0)
            drawable.setStroke(strokeWidth, strokeColor);
        return drawable;
    }

    /** Rounded only on the top corners — used for the expanded panel header,
     * which is the only chrome that may paint over the (transparent) window;
     * the terminal area below is left to the GL surface. */
    private GradientDrawable makeTopRoundRect(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        float r = radius;
        drawable.setCornerRadii(new float[]{r, r, r, r, 0f, 0f, 0f, 0f});
        return drawable;
    }

    private int expandedWidth() {
        if (mUserExpandedWidth > 0)
            return clamp(mUserExpandedWidth, minimumExpandedWindowWidth(), maxExpandedWindowWidth());
        return defaultExpandedWidth();
    }

    private int defaultExpandedWidth() {
        // A fixed, comfortable target (~70 % width, capped) — deliberately NOT
        // derived from the shared session's column count, which still reflects
        // the full-screen view and would make the floating window echo the
        // full-screen shape. Snap to the floating font's cell grid so there is
        // no half-column gap on the right edge.
        int cap = (int) (screenWidth() * 0.7f);
        int maxWidth = Math.min(cap, dp(420));
        int minWidth = minimumExpandedWindowWidth();
        return snapExpandedWidthToCellGrid(Math.max(minWidth, maxWidth), minWidth, maxWidth);
    }

    private int minimumExpandedWindowWidth() {
        int headerButtons = 3 * dp(26) + 3 * dp(2);
        int headerPadding = dp(11) + dp(5);
        int panelPadding = 2 * dp(EXPANDED_PANEL_PADDING_DP);
        int minimumTitleWidth = dp(40);
        return panelPadding + headerPadding + headerButtons + minimumTitleWidth;
    }

    private int maxExpandedWindowWidth() {
        return Math.max(minimumExpandedWindowWidth(), screenWidth() - dp(8));
    }

    private int snapExpandedWidthToCellGrid(int width, int minWidth, int maxWidth) {
        Typeface typeface = mTerminalTypeface != null
            ? mTerminalTypeface
            : TermuxTerminalFontManager.loadTerminalTypeface(mService);
        TerminalRenderer renderer = new TerminalRenderer(getFloatingTerminalFontSize(), typeface);
        float cellWidth = renderer.getFontWidth();
        if (cellWidth <= 0)
            return Math.max(minWidth, Math.min(maxWidth, width));

        int horizontalChrome = 2 * dp(EXPANDED_PANEL_PADDING_DP);
        int availableTerminalWidth = Math.max(1, width - horizontalChrome);
        int columns = Math.max(1, (int) Math.floor(availableTerminalWidth / cellWidth));
        int snappedWidth = horizontalChrome + (int) Math.ceil(columns * cellWidth);

        while (snappedWidth < minWidth && snappedWidth < maxWidth) {
            columns++;
            snappedWidth = horizontalChrome + (int) Math.ceil(columns * cellWidth);
        }
        while (snappedWidth > maxWidth && columns > 1) {
            columns--;
            snappedWidth = horizontalChrome + (int) Math.ceil(columns * cellWidth);
        }

        return Math.max(minWidth, Math.min(maxWidth, snappedWidth));
    }

    private int expandedHeight() {
        if (mUserExpandedHeight > 0)
            return clamp(mUserExpandedHeight, minimumExpandedWindowHeight(), maxExpandedWindowHeight());
        return defaultExpandedHeight();
    }

    private int defaultExpandedHeight() {
        return Math.max(dp(220), Math.min((int) (screenHeight() * 0.42f), dp(380)));
    }

    private int minimumExpandedWindowHeight() {
        return dp(142);
    }

    private int maxExpandedWindowHeight() {
        return Math.max(minimumExpandedWindowHeight(), screenHeight() - dp(24));
    }

    private int screenWidth() {
        return getScreenBounds().width();
    }

    private int screenHeight() {
        return getScreenBounds().height();
    }

    private Rect getScreenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            return mWindowManager.getCurrentWindowMetrics().getBounds();

        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private int dp(int value) {
        return Math.round(value * mService.getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class ResizeTouchListener implements View.OnTouchListener {
        private final ResizeGripView mGrip;
        private int mStartWidth;
        private int mStartHeight;
        private float mStartRawX;
        private float mStartRawY;

        ResizeTouchListener(ResizeGripView grip) {
            mGrip = grip;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (mLayoutParams == null || !mExpanded) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mStartWidth = mLayoutParams.width;
                    mStartHeight = mLayoutParams.height;
                    mStartRawX = event.getRawX();
                    mStartRawY = event.getRawY();
                    mGrip.setActive(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    resizeExpandedWindow(
                        mStartWidth + Math.round(event.getRawX() - mStartRawX),
                        mStartHeight + Math.round(event.getRawY() - mStartRawY));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mGrip.setActive(false);
                    scheduleTerminalGeometryRefresh();
                    return true;
                default:
                    return false;
            }
        }

        private void resizeExpandedWindow(int desiredWidth, int desiredHeight) {
            int maxWidth = Math.max(minimumExpandedWindowWidth(), screenWidth() - mLayoutParams.x);
            int maxHeight = Math.max(minimumExpandedWindowHeight(), screenHeight() - mLayoutParams.y);
            int width = clamp(desiredWidth, minimumExpandedWindowWidth(), maxWidth);
            int height = clamp(desiredHeight, minimumExpandedWindowHeight(), maxHeight);
            if (width == mLayoutParams.width && height == mLayoutParams.height)
                return;

            mUserExpandedWidth = width;
            mUserExpandedHeight = height;
            mLayoutParams.width = width;
            mLayoutParams.height = height;
            mLastWidth = width;
            mLastHeight = height;

            try {
                mWindowManager.updateViewLayout(mRootView, mLayoutParams);
            } catch (Exception e) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to resize floating terminal", e);
                return;
            }

            scheduleTerminalGeometryRefresh();
        }
    }

    /** A minimal control glyph (minimize / maximize / close), stroke-drawn rather
     * than typeset so it stays crisp and font-independent, with a borderless
     * rounded press state. */
    private final class ControlIconView extends View {
        private final int mIcon;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        ControlIconView(Context context, int icon) {
            super(context);
            mIcon = icon;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setColor(CHROME_TEXT);
            mPaint.setStrokeWidth(Math.max(2f, strokePx()));
            setBackground(makeIconPressBackground(icon == ICON_CLOSE));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float r = dp(5);
            switch (mIcon) {
                case ICON_MINIMIZE:
                    canvas.drawLine(cx - r, cy, cx + r, cy, mPaint);
                    break;
                case ICON_MAXIMIZE:
                    float rad = dp(2);
                    canvas.drawRoundRect(cx - r, cy - r, cx + r, cy + r, rad, rad, mPaint);
                    break;
                case ICON_CLOSE:
                    canvas.drawLine(cx - r, cy - r, cx + r, cy + r, mPaint);
                    canvas.drawLine(cx - r, cy + r, cx + r, cy - r, mPaint);
                    break;
                default:
                    break;
            }
        }
    }

    /** The resize affordance: three nested diagonal strokes anchored to the
     * bottom-right corner (the universal grip), muted at rest and lit to the
     * accent green while dragging. */
    private final class ResizeGripView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean mActive;

        ResizeGripView(Context context) {
            super(context);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeWidth(Math.max(2f, strokePx()));
            setOnTouchListener(new ResizeTouchListener(this));
        }

        void setActive(boolean active) {
            if (mActive == active) return;
            mActive = active;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            mPaint.setColor(mActive ? CHROME_ACCENT : CHROME_TEXT_DIM);
            float pad = dp(5);
            float x = getWidth() - pad;
            float y = getHeight() - pad;
            float base = dp(3);
            float gap = dp(4);
            for (int i = 0; i < 3; i++) {
                float len = base + i * gap;
                canvas.drawLine(x, y - len, x - len, y, mPaint);
            }
        }
    }

    private float strokePx() {
        return mService.getResources().getDisplayMetrics().density * 1.3f;
    }

    private final class DragTouchListener implements View.OnTouchListener {
        @Nullable
        private final Runnable mClickAction;
        private int mStartX;
        private int mStartY;
        private float mStartRawX;
        private float mStartRawY;
        private boolean mMoved;

        DragTouchListener(@Nullable Runnable clickAction) {
            mClickAction = clickAction;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            if (mLayoutParams == null) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mStartX = mLayoutParams.x;
                    mStartY = mLayoutParams.y;
                    mStartRawX = event.getRawX();
                    mStartRawY = event.getRawY();
                    mMoved = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = Math.round(event.getRawX() - mStartRawX);
                    int dy = Math.round(event.getRawY() - mStartRawY);
                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4))
                        mMoved = true;
                    mLayoutParams.x = clamp(mStartX + dx, 0, Math.max(0, screenWidth() - mLastWidth));
                    mLayoutParams.y = clamp(mStartY + dy, 0, Math.max(0, screenHeight() - mLastHeight));
                    try {
                        mWindowManager.updateViewLayout(mRootView, mLayoutParams);
                    } catch (Exception e) {
                        Logger.logStackTraceWithMessage(LOG_TAG, "Failed to drag floating terminal", e);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!mMoved && mClickAction != null)
                        mClickAction.run();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }
    }

    private final class TermuxFloatingTerminalViewClient extends TermuxTerminalViewClientBase {
        @Override
        public void onSingleTapUp(MotionEvent e) {
            focusTerminalAndShowKeyboard();
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return true;
        }
    }
}
