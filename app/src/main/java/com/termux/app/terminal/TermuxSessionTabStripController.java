package com.termux.app.terminal;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
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

    private final TermuxActivity mActivity;
    private final LinearLayout mTabStrip;
    private final HorizontalScrollView mTabStripScrollView;
    private final List<TerminalSession> mRenderedSessions = new ArrayList<>();
    private TerminalSession mLastCurrentSession;
    private final Runnable mScrollToCurrentSessionRunnable = this::smoothScrollToCurrentSessionNow;

    public TermuxSessionTabStripController(TermuxActivity activity) {
        mActivity = activity;
        mTabStrip = activity.findViewById(R.id.terminal_sessions_tab_strip);
        mTabStripScrollView = activity.findViewById(R.id.terminal_sessions_tab_strip_scroll);
    }

    public void notifyUpdated(List<TermuxSession> sessions) {
        if (mTabStrip == null) return;

        List<TerminalSession> terminalSessions = getTerminalSessions(sessions);
        boolean sameSessions = hasSameRenderedSessions(terminalSessions)
            && mTabStrip.getChildCount() == terminalSessions.size() + 1;
        TerminalSession currentSession = mActivity.getCurrentSession();
        boolean shouldScrollToCurrentSession = !sameSessions || currentSession != mLastCurrentSession;

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
        if (shouldScrollToCurrentSession)
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
        tab.setOnLongClickListener(v -> {
            mActivity.getTermuxTerminalSessionClient().renameSession(session);
            return true;
        });
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
        if (mTabStripScrollView == null || mActivity.getTermuxService() == null) return;

        mTabStripScrollView.removeCallbacks(mScrollToCurrentSessionRunnable);
        mTabStripScrollView.postDelayed(mScrollToCurrentSessionRunnable, 200);
    }

    private void smoothScrollToCurrentSessionNow() {
        if (mTabStripScrollView == null || mActivity.getTermuxService() == null) return;

        TerminalSession session = mActivity.getCurrentSession();
        int index = mActivity.getTermuxService().getIndexOfSession(session);
        if (index < 0 || index >= mTabStrip.getChildCount()) return;

        View selectedTab = mTabStrip.getChildAt(index);
        mTabStripScrollView.smoothScrollTo(selectedTab.getLeft(), 0);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

}
