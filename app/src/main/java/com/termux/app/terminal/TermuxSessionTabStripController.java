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

import java.util.List;

public class TermuxSessionTabStripController {

    private static final int TAB_HEIGHT_DP = 24;
    private static final int TAB_TITLE_WIDTH_DP = 92;
    private static final int TAB_MIN_WIDTH_DP = 92;
    private static final int CLOSE_BUTTON_WIDTH_DP = 24;

    private final TermuxActivity mActivity;
    private final LinearLayout mTabStrip;
    private final HorizontalScrollView mTabStripScrollView;

    public TermuxSessionTabStripController(TermuxActivity activity) {
        mActivity = activity;
        mTabStrip = activity.findViewById(R.id.terminal_sessions_tab_strip);
        mTabStripScrollView = activity.findViewById(R.id.terminal_sessions_tab_strip_scroll);
    }

    public void notifyUpdated(List<TermuxSession> sessions) {
        if (mTabStrip == null) return;

        mTabStrip.removeAllViews();
        if (sessions == null) return;

        for (int i = 0; i < sessions.size(); i++) {
            TermuxSession termuxSession = sessions.get(i);
            if (termuxSession == null) continue;

            TerminalSession terminalSession = termuxSession.getTerminalSession();
            if (terminalSession == null) continue;

            mTabStrip.addView(createSessionTab(i, terminalSession));
        }

        mTabStrip.addView(createNewSessionTab());
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
        tab.setPadding(dp(8), 0, dp(1), 0);
        tab.setMinimumWidth(dp(TAB_MIN_WIDTH_DP));
        tab.setOnClickListener(v -> mActivity.getTermuxTerminalSessionClient().setCurrentSession(session));
        tab.setOnLongClickListener(v -> {
            mActivity.getTermuxTerminalSessionClient().renameSession(session);
            return true;
        });
        LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(TAB_HEIGHT_DP));
        tabParams.setMargins(0, dp(3), dp(4), dp(3));
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(30), dp(TAB_HEIGHT_DP));
        params.setMargins(0, dp(3), dp(4), dp(3));
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

    private void scrollToCurrentSession() {
        if (mTabStripScrollView == null || mActivity.getTermuxService() == null) return;

        TerminalSession session = mActivity.getCurrentSession();
        int index = mActivity.getTermuxService().getIndexOfSession(session);
        if (index < 0 || index >= mTabStrip.getChildCount()) return;

        View selectedTab = mTabStrip.getChildAt(index);
        mTabStripScrollView.postDelayed(() -> mTabStripScrollView.smoothScrollTo(selectedTab.getLeft(), 0), 200);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

}
