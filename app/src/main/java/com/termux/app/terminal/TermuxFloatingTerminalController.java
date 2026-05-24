package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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
import com.termux.view.TerminalView;

public final class TermuxFloatingTerminalController {

    private static final String LOG_TAG = "TermuxFloatingTerminal";
    private static final int REFRESH_INTERVAL_MS = 250;

    private final TermuxService mService;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final TermuxFloatingTerminalViewClient mTerminalViewClient = new TermuxFloatingTerminalViewClient();

    private FrameLayout mRootView;
    private TerminalView mTerminalView;
    private TextView mTitleView;
    private WindowManager.LayoutParams mLayoutParams;
    private boolean mAttached;
    private boolean mExpanded;
    private int mLastWidth;
    private int mLastHeight;

    private final Runnable mRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mAttached) return;

            attachCurrentSession();
            if (mTerminalView != null)
                mTerminalView.onScreenUpdated();
            updateTitle();

            mHandler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

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
        if (!mAttached || mRootView == null) return;

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
    }

    public void onSessionsChanged() {
        if (!mAttached) return;
        if (mService.getTermuxSessionsSize() == 0) {
            hide();
            return;
        }
        attachCurrentSession();
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

        mRootView.removeAllViews();
        if (expanded)
            buildExpandedView();
        else
            buildCollapsedView();

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
        updateTitle();
        mHandler.post(mRefreshRunnable);

        if (expanded)
            focusTerminalAndShowKeyboard();
    }

    private WindowManager.LayoutParams createLayoutParams(boolean expanded) {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;

        int width = expanded ? expandedWidth() : dp(58);
        int height = expanded ? expandedHeight() : dp(58);
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
        bubble.setTextSize(15);
        bubble.setBackground(makeRoundRect(Color.argb(230, 8, 18, 15), dp(18), Color.rgb(11, 201, 137), dp(1)));
        bubble.setOnTouchListener(new DragTouchListener(() -> showExpandedIfAllowed()));

        mRootView.addView(bubble, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void buildExpandedView() {
        LinearLayout panel = new LinearLayout(mService);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(7), dp(8), dp(8));
        panel.setBackground(makeRoundRect(Color.argb(235, 8, 12, 13), dp(8), Color.argb(180, 11, 201, 137), dp(1)));

        LinearLayout header = new LinearLayout(mService);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setOnTouchListener(new DragTouchListener(null));

        mTitleView = new TextView(mService);
        mTitleView.setTextColor(Color.rgb(210, 235, 225));
        mTitleView.setTextSize(12);
        mTitleView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        mTitleView.setSingleLine(true);
        header.addView(mTitleView, new LinearLayout.LayoutParams(0, dp(32), 1));

        header.addView(makeHeaderButton("-", () -> show(false)));
        header.addView(makeHeaderButton("Open", () -> {
            hide();
            TermuxActivity.startTermuxActivity(mService);
        }));
        header.addView(makeHeaderButton("x", this::hide));
        panel.addView(header, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(34)));

        mTerminalView = new TerminalView(mService, null);
        mTerminalView.setTerminalViewClient(mTerminalViewClient);
        mTerminalView.setTextSize(getFloatingTerminalFontSize());
        mTerminalView.setFocusable(true);
        mTerminalView.setFocusableInTouchMode(true);
        mTerminalView.setBackgroundColor(Color.rgb(0, 0, 0));
        panel.addView(mTerminalView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1));

        mRootView.addView(panel, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private TextView makeHeaderButton(String label, Runnable action) {
        TextView button = new TextView(mService);
        button.setText(label);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.rgb(220, 255, 238));
        button.setTextSize(12);
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setBackground(makeRoundRect(Color.argb(230, 16, 36, 30), dp(5), Color.argb(100, 11, 201, 137), dp(1)));
        button.setOnClickListener(v -> action.run());

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(label.length() > 2 ? 54 : 32), dp(28));
        params.leftMargin = dp(5);
        button.setLayoutParams(params);
        return button;
    }

    private int getFloatingTerminalFontSize() {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(mService);
        if (preferences != null)
            return preferences.getFontSize();

        return TermuxAppSharedPreferences.getDefaultFontSizes(mService)[0];
    }

    private void attachCurrentSession() {
        if (mTerminalView == null) return;

        TerminalSession session = mService.getCurrentStoredTerminalSessionOrLast();
        if (session == null) return;

        mTerminalView.attachSession(session);
    }

    private void updateTitle() {
        if (mTitleView == null) return;

        TerminalSession session = mService.getCurrentStoredTerminalSessionOrLast();
        int sessionIndex = session == null ? -1 : mService.getIndexOfSession(session);
        String label = session == null
            ? mService.getString(R.string.termuxplus_floating_terminal_title)
            : "[" + (sessionIndex + 1) + "] " + getSessionLabel(session);
        mTitleView.setText(label);
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

    private int expandedWidth() {
        return Math.max(dp(280), Math.min(screenWidth() - dp(24), dp(560)));
    }

    private int expandedHeight() {
        return Math.max(dp(240), Math.min((int) (screenHeight() * 0.48f), dp(430)));
    }

    private int screenWidth() {
        return mService.getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return mService.getResources().getDisplayMetrics().heightPixels;
    }

    private int dp(int value) {
        return Math.round(value * mService.getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
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
