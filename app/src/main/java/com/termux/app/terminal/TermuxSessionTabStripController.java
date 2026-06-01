package com.termux.app.terminal;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

public class TermuxSessionTabStripController {

    private static final int TAB_HEIGHT_DP = 30;
    private static final int TAB_TITLE_WIDTH_DP = 92;
    private static final int TAB_MIN_WIDTH_DP = 92;
    private static final int CLOSE_BUTTON_WIDTH_DP = 26;
    private static final int TAB_REORDER_LONG_PRESS_EXTRA_DELAY_MS = 140;
    private static final long TAB_REORDER_ANIMATION_MS = 120L;

    private final TermuxActivity mActivity;
    private final LinearLayout mTabStrip;
    private final HorizontalScrollView mTabStripScrollView;
    private final int mDragTouchSlop;
    private final int mDragLongPressDelayMs;
    private final List<TerminalSession> mRenderedSessions = new ArrayList<>();
    private TerminalSession mLastCurrentSession;
    private TerminalSession mDraggingSession;
    private TerminalSession mPendingScrollSession;
    private TerminalSession mScrollTargetSession;
    private View mDraggingTab;
    private boolean mIsDraggingTab;
    private boolean mIsSessionTabDragPending;
    private boolean mHasMovedTabTouch;
    private int mDraggingTabStartIndex = -1;
    private int mCurrentDropTargetIndex = -1;
    private float mTouchDownRawX;
    private float mTouchDownRawY;
    private float mDragStartRawX;
    private float mDragStartRawY;
    private float mLastTouchRawX;
    private float mLastTouchRawY;
    private float mDraggingTabStartElevation;
    private final Runnable mScrollToCurrentSessionRunnable = this::smoothScrollToCurrentSessionNow;
    private final Runnable mStartSessionTabDragRunnable = () -> {
        if (mIsSessionTabDragPending && mDraggingTab != null && mDraggingSession != null) {
            mDragStartRawX = mLastTouchRawX;
            mDragStartRawY = mLastTouchRawY;
            startSessionTabDrag(mDraggingTab);
        }
    };

    public TermuxSessionTabStripController(TermuxActivity activity) {
        mActivity = activity;
        mTabStrip = activity.findViewById(R.id.terminal_sessions_tab_strip);
        mTabStripScrollView = activity.findViewById(R.id.terminal_sessions_tab_strip_scroll);
        mDragTouchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        mDragLongPressDelayMs = ViewConfiguration.getLongPressTimeout() + TAB_REORDER_LONG_PRESS_EXTRA_DELAY_MS;
    }

    public void notifyUpdated(List<TermuxSession> sessions) {
        if (mTabStrip == null) return;
        if (mIsDraggingTab)
            finishSessionTabDrag(false);

        List<TerminalSession> terminalSessions = getTerminalSessions(sessions);
        boolean sameSessions = hasSameRenderedSessions(terminalSessions)
            && mTabStrip.getChildCount() == terminalSessions.size() + 1;
        TerminalSession currentSession = mActivity.getCurrentSession();
        boolean shouldScrollToCurrentSession = !sameSessions || currentSession != mLastCurrentSession;
        TerminalSession pendingScrollSession = mPendingScrollSession;
        mPendingScrollSession = null;

        if (sameSessions) {
            for (int i = 0; i < terminalSessions.size(); i++)
                updateSessionTab(mTabStrip.getChildAt(i), i, terminalSessions.get(i));
        } else {
            mTabStrip.removeAllViews();
            for (int i = 0; i < terminalSessions.size(); i++)
                mTabStrip.addView(createSessionTab(i, terminalSessions.get(i)));

            mTabStrip.addView(createNewSessionTab());
            mRenderedSessions.clear();
            mRenderedSessions.addAll(terminalSessions);
        }

        mLastCurrentSession = currentSession;
        if (pendingScrollSession != null)
            scrollToSession(pendingScrollSession);
        else if (shouldScrollToCurrentSession)
            scrollToCurrentSession();
    }

    private View createSessionTab(int index, TerminalSession session) {
        boolean selected = session == mActivity.getCurrentSession();

        LinearLayout tab = new LinearLayout(mActivity);
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setActivated(selected);
        tab.setSelected(selected);
        tab.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.termuxplus_session_tab_bg));
        tab.setPadding(dp(11), 0, dp(2), 0);
        tab.setMinimumWidth(dp(TAB_MIN_WIDTH_DP));
        tab.setOnClickListener(v -> mActivity.getTermuxTerminalSessionClient().setCurrentSession(session));
        tab.setOnTouchListener((v, event) -> onSessionTabTouch(v, session, event));
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(TAB_HEIGHT_DP));
        tabParams.setMargins(0, 0, dp(6), 0);
        tab.setLayoutParams(tabParams);

        TextView title = new TextView(mActivity);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(getTabTitle(index, session));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setIncludeFontPadding(false);
        title.setTextSize(11);
        title.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
        title.setTextColor(getTabTextColor(selected, session));
        if (!session.isRunning())
            title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        title.setOnTouchListener((v, event) -> onSessionTabTouch(tab, session, event));
        tab.addView(title, new LinearLayout.LayoutParams(dp(TAB_TITLE_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT));

        ImageButton close = new ImageButton(mActivity);
        close.setImageResource(R.drawable.ic_termuxplus_close_18);
        close.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.termuxplus_icon_button_bg));
        close.setContentDescription(mActivity.getString(R.string.action_close_session));
        close.setColorFilter(ContextCompat.getColor(mActivity,
            selected ? R.color.termuxplus_text_primary : R.color.termuxplus_text_secondary));
        close.setPadding(dp(5), dp(5), dp(5), dp(5));
        close.setOnClickListener(v -> mActivity.getTermuxTerminalSessionClient().closeSession(session));
        tab.addView(close, new LinearLayout.LayoutParams(dp(CLOSE_BUTTON_WIDTH_DP), LinearLayout.LayoutParams.MATCH_PARENT));

        return tab;
    }

    private void updateSessionTab(View tabView, int index, TerminalSession session) {
        if (!(tabView instanceof LinearLayout)) return;

        boolean selected = session == mActivity.getCurrentSession();
        tabView.setActivated(selected);
        tabView.setSelected(selected);

        LinearLayout tab = (LinearLayout) tabView;
        if (tab.getChildCount() > 0 && tab.getChildAt(0) instanceof TextView) {
            TextView title = (TextView) tab.getChildAt(0);
            title.setText(getTabTitle(index, session));
            title.setTypeface(Typeface.MONOSPACE, selected ? Typeface.BOLD : Typeface.NORMAL);
            title.setTextColor(getTabTextColor(selected, session));

            int paintFlags = title.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG;
            if (!session.isRunning())
                paintFlags |= Paint.STRIKE_THRU_TEXT_FLAG;
            title.setPaintFlags(paintFlags);
        }

        if (tab.getChildCount() > 1 && tab.getChildAt(1) instanceof ImageButton) {
            ImageButton close = (ImageButton) tab.getChildAt(1);
            close.setColorFilter(ContextCompat.getColor(mActivity,
                selected ? R.color.termuxplus_text_primary : R.color.termuxplus_text_secondary));
        }
    }

    private View createNewSessionTab() {
        TextView add = new TextView(mActivity);
        add.setText("+");
        add.setGravity(Gravity.CENTER);
        add.setIncludeFontPadding(false);
        add.setTextSize(18);
        add.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
        add.setTextColor(ContextCompat.getColor(mActivity, R.color.termuxplus_text_secondary));
        add.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.termuxplus_session_add_bg));
        add.setContentDescription(mActivity.getString(R.string.action_new_session));
        add.setOnClickListener(v -> mActivity.getTermuxTerminalSessionClient().addNewSession(false, null));
        add.setOnLongClickListener(v -> {
            mActivity.showCreateNamedSessionDialog();
            return true;
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(TAB_HEIGHT_DP));
        params.setMargins(0, 0, dp(4), 0);
        add.setLayoutParams(params);
        return add;
    }

    private boolean onSessionTabTouch(View tab, TerminalSession session, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelPendingSessionTabDragStart();
                mDraggingSession = session;
                mDraggingTab = tab;
                mTouchDownRawX = event.getRawX();
                mTouchDownRawY = event.getRawY();
                mDragStartRawX = mTouchDownRawX;
                mDragStartRawY = mTouchDownRawY;
                mLastTouchRawX = mTouchDownRawX;
                mLastTouchRawY = mTouchDownRawY;
                mHasMovedTabTouch = false;
                mIsSessionTabDragPending = mRenderedSessions.size() >= 2;
                tab.setPressed(true);
                if (mIsSessionTabDragPending)
                    tab.postDelayed(mStartSessionTabDragRunnable, mDragLongPressDelayMs);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (mDraggingTab != tab || mDraggingSession != session) return false;

                mLastTouchRawX = event.getRawX();
                mLastTouchRawY = event.getRawY();
                float touchDeltaX = mLastTouchRawX - mTouchDownRawX;
                float touchDeltaY = mLastTouchRawY - mTouchDownRawY;
                if (!mIsDraggingTab) {
                    if (hasMovedPastDragSlop(touchDeltaX, touchDeltaY)) {
                        mHasMovedTabTouch = true;
                        cancelPendingSessionTabDragStart();
                        tab.setPressed(false);
                    }
                    return true;
                }

                float dragDeltaX = mLastTouchRawX - mDragStartRawX;
                tab.setTranslationX(clampTabTranslation(tab, dragDeltaX));
                updateSessionTabReorderPreview(findSessionTabDropTargetIndex());
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (mDraggingTab != tab || mDraggingSession != session) return false;
                boolean wasDragging = mIsDraggingTab;
                boolean hadMovedTouch = mHasMovedTabTouch;
                if (wasDragging) {
                    finishSessionTabDrag(true);
                } else {
                    clearSessionTabDragState();
                    if (!hadMovedTouch)
                        tab.performClick();
                }
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                if (mDraggingTab != tab || mDraggingSession != session) return false;
                boolean wasDragging = mIsDraggingTab;
                finishSessionTabDrag(false);
                return true;
            }
            default:
                return mIsDraggingTab && mDraggingTab == tab;
        }
    }

    private boolean hasMovedPastDragSlop(float deltaX, float deltaY) {
        return Math.abs(deltaX) > mDragTouchSlop || Math.abs(deltaY) > mDragTouchSlop;
    }

    private void startSessionTabDrag(View tab) {
        cancelPendingSessionTabDragStart();
        mIsDraggingTab = true;
        mDraggingTabStartIndex = mTabStrip != null ? mTabStrip.indexOfChild(tab) : -1;
        mCurrentDropTargetIndex = mDraggingTabStartIndex;
        tab.cancelLongPress();
        tab.setPressed(false);
        tab.animate()
            .alpha(0.86f)
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(TAB_REORDER_ANIMATION_MS)
            .start();
        tab.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (mTabStripScrollView != null)
            mTabStripScrollView.requestDisallowInterceptTouchEvent(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mDraggingTabStartElevation = tab.getElevation();
            tab.setElevation(dp(8));
        }
    }

    private void finishSessionTabDrag(boolean commitMove) {
        TerminalSession session = mDraggingSession;
        int targetIndex = commitMove ? findSessionTabDropTargetIndex() : -1;

        resetDraggingTabVisuals();
        resetSessionTabReorderPreview(false);
        clearSessionTabDragState();

        if (commitMove && session != null && targetIndex >= 0) {
            mPendingScrollSession = session;
            boolean moved = mActivity.getTermuxTerminalSessionClient().moveSession(session, targetIndex);
            if (!moved)
                mPendingScrollSession = null;
        }
    }

    private float clampTabTranslation(View tab, float translationX) {
        if (mTabStrip == null || tab == null) return translationX;

        float edgeAllowance = tab.getWidth() / 2f;
        float minTranslation = -tab.getLeft() - edgeAllowance;
        float maxTranslation = mTabStrip.getWidth() - tab.getRight() + edgeAllowance;
        return Math.max(minTranslation, Math.min(maxTranslation, translationX));
    }

    private void updateSessionTabReorderPreview(int targetIndex) {
        if (targetIndex < 0 || targetIndex == mCurrentDropTargetIndex) return;

        mCurrentDropTargetIndex = targetIndex;
        int sessionTabCount = getSessionTabCount();
        if (mDraggingTabStartIndex < 0 || mDraggingTabStartIndex >= sessionTabCount) return;

        int draggedTabSlotWidth = getTabSlotWidth(mDraggingTab);
        for (int i = 0; i < sessionTabCount; i++) {
            View child = mTabStrip.getChildAt(i);
            if (child == null || child == mDraggingTab) continue;

            float targetTranslation = 0f;
            if (targetIndex < mDraggingTabStartIndex && i >= targetIndex && i < mDraggingTabStartIndex) {
                targetTranslation = draggedTabSlotWidth;
            } else if (targetIndex > mDraggingTabStartIndex && i > mDraggingTabStartIndex && i <= targetIndex) {
                targetTranslation = -draggedTabSlotWidth;
            }

            animateSessionTabTranslation(child, targetTranslation);
        }
    }

    private void resetSessionTabReorderPreview(boolean animate) {
        int sessionTabCount = getSessionTabCount();
        for (int i = 0; i < sessionTabCount; i++) {
            View child = mTabStrip.getChildAt(i);
            if (child == null || child == mDraggingTab) continue;

            if (animate) {
                animateSessionTabTranslation(child, 0f);
            } else {
                child.animate().cancel();
                child.setTranslationX(0f);
            }
        }
    }

    private void animateSessionTabTranslation(View tab, float translationX) {
        tab.animate()
            .translationX(translationX)
            .setDuration(TAB_REORDER_ANIMATION_MS)
            .start();
    }

    private int findSessionTabDropTargetIndex() {
        if (mDraggingTab == null || mTabStrip == null) return -1;

        int sessionTabCount = getSessionTabCount();
        if (sessionTabCount < 2) return -1;

        float draggedCenter = mDraggingTab.getLeft() + mDraggingTab.getTranslationX() + (mDraggingTab.getWidth() / 2f);
        int targetIndex = 0;
        for (int i = 0; i < sessionTabCount; i++) {
            View child = mTabStrip.getChildAt(i);
            if (child == null || child == mDraggingTab) continue;

            float childCenter = child.getLeft() + (child.getWidth() / 2f);
            if (childCenter < draggedCenter)
                targetIndex++;
        }

        return Math.max(0, Math.min(targetIndex, sessionTabCount - 1));
    }

    private int getSessionTabCount() {
        if (mTabStrip == null) return 0;
        return Math.min(mRenderedSessions.size(), Math.max(0, mTabStrip.getChildCount() - 1));
    }

    private int getTabSlotWidth(View tab) {
        if (tab == null) return 0;

        int width = tab.getWidth();
        if (tab.getLayoutParams() instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) tab.getLayoutParams();
            width += params.leftMargin + params.rightMargin;
        }
        return width;
    }

    private void resetDraggingTabVisuals() {
        if (mDraggingTab == null) return;

        mDraggingTab.animate().cancel();
        mDraggingTab.setTranslationX(0f);
        mDraggingTab.setAlpha(1f);
        mDraggingTab.setScaleX(1f);
        mDraggingTab.setScaleY(1f);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            mDraggingTab.setElevation(mDraggingTabStartElevation);
    }

    private void clearSessionTabDragState() {
        cancelPendingSessionTabDragStart();
        if (mDraggingTab != null)
            mDraggingTab.setPressed(false);
        if (mTabStripScrollView != null)
            mTabStripScrollView.requestDisallowInterceptTouchEvent(false);

        mDraggingSession = null;
        mDraggingTab = null;
        mIsDraggingTab = false;
        mIsSessionTabDragPending = false;
        mHasMovedTabTouch = false;
        mDraggingTabStartIndex = -1;
        mCurrentDropTargetIndex = -1;
        mTouchDownRawX = 0f;
        mTouchDownRawY = 0f;
        mDragStartRawX = 0f;
        mDragStartRawY = 0f;
        mLastTouchRawX = 0f;
        mLastTouchRawY = 0f;
        mDraggingTabStartElevation = 0f;
    }

    private void cancelPendingSessionTabDragStart() {
        if (mDraggingTab != null)
            mDraggingTab.removeCallbacks(mStartSessionTabDragRunnable);
        mIsSessionTabDragPending = false;
    }

    private String getTabTitle(int index, TerminalSession session) {
        String name = session.mSessionName;
        String title = session.getTitle();
        if (!TextUtils.isEmpty(name)) return name;
        if (!TextUtils.isEmpty(title)) return title;
        return "Session " + (index + 1);
    }

    private int getTabTextColor(boolean selected, TerminalSession session) {
        if (!session.isRunning() && session.getExitStatus() != 0)
            return ContextCompat.getColor(mActivity, R.color.termuxplus_text_error);
        return ContextCompat.getColor(mActivity,
            selected ? R.color.termuxplus_text_primary : R.color.termuxplus_text_secondary);
    }

    private List<TerminalSession> getTerminalSessions(List<TermuxSession> sessions) {
        List<TerminalSession> terminalSessions = new ArrayList<>();
        if (sessions == null) return terminalSessions;

        for (TermuxSession termuxSession : sessions) {
            if (termuxSession == null) continue;

            TerminalSession terminalSession = termuxSession.getTerminalSession();
            if (terminalSession != null)
                terminalSessions.add(terminalSession);
        }

        return terminalSessions;
    }

    private boolean hasSameRenderedSessions(List<TerminalSession> sessions) {
        if (mRenderedSessions.size() != sessions.size()) return false;

        for (int i = 0; i < sessions.size(); i++) {
            if (mRenderedSessions.get(i) != sessions.get(i))
                return false;
        }

        return true;
    }

    private void scrollToCurrentSession() {
        scrollToSession(null);
    }

    private void scrollToSession(TerminalSession session) {
        if (mTabStripScrollView == null || mActivity.getTermuxService() == null) return;

        mScrollTargetSession = session;
        mTabStripScrollView.removeCallbacks(mScrollToCurrentSessionRunnable);
        mTabStripScrollView.postDelayed(mScrollToCurrentSessionRunnable, 200);
    }

    private void smoothScrollToCurrentSessionNow() {
        if (mTabStripScrollView == null || mActivity.getTermuxService() == null) return;

        TerminalSession session = mScrollTargetSession != null ? mScrollTargetSession : mActivity.getCurrentSession();
        mScrollTargetSession = null;
        int index = mActivity.getTermuxService().getIndexOfSession(session);
        if (index < 0 || index >= mTabStrip.getChildCount()) return;

        View selectedTab = mTabStrip.getChildAt(index);
        mTabStripScrollView.smoothScrollTo(selectedTab.getLeft(), 0);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

}
