package com.termux.app.terminal;

import android.graphics.Color;
import android.graphics.Paint;
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
import com.termux.shared.theme.NightMode;
import com.termux.shared.theme.ThemeUtils;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TermuxSessionTabStripController {

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
        boolean darkTheme = ThemeUtils.shouldEnableDarkTheme(mActivity, NightMode.getAppNightMode().getName());

        LinearLayout tab = new LinearLayout(mActivity);
        tab.setOrientation(LinearLayout.HORIZONTAL);
        tab.setGravity(Gravity.CENTER_VERTICAL);
        tab.setActivated(selected);
        tab.setBackground(ContextCompat.getDrawable(mActivity,
            darkTheme ? R.drawable.session_background_black_selected : R.drawable.session_background_selected));
        tab.setPadding(dp(8), 0, dp(2), 0);
        tab.setMinimumWidth(dp(96));
        tab.setOnClickListener(v -> mActivity.getTermuxTerminalSessionClient().setCurrentSession(session));
        tab.setOnLongClickListener(v -> {
            mActivity.getTermuxTerminalSessionClient().renameSession(session);
            return true;
        });

        TextView title = new TextView(mActivity);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(getTabTitle(index, session));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextSize(13);
        title.setTextColor(getTabTextColor(darkTheme, session));
        if (!session.isRunning())
            title.setPaintFlags(title.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
        tab.addView(title, new LinearLayout.LayoutParams(dp(112), LinearLayout.LayoutParams.MATCH_PARENT));

        ImageButton close = new ImageButton(mActivity);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription(mActivity.getString(R.string.action_close_session));
        close.setColorFilter(darkTheme ? Color.WHITE : Color.BLACK);
        close.setPadding(dp(8), dp(8), dp(8), dp(8));
        close.setOnClickListener(v -> mActivity.getTermuxTerminalSessionClient().closeSession(session));
        tab.addView(close, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT));

        return tab;
    }

    private View createNewSessionTab() {
        TextView add = new TextView(mActivity);
        add.setText("+");
        add.setGravity(Gravity.CENTER);
        add.setTextSize(24);
        add.setContentDescription(mActivity.getString(R.string.action_new_session));
        add.setOnClickListener(v -> mActivity.getTermuxTerminalSessionClient().addNewSession(false, null));
        add.setOnLongClickListener(v -> {
            mActivity.findViewById(R.id.new_session_button).performLongClick();
            return true;
        });
        add.setMinWidth(dp(48));
        return add;
    }

    private String getTabTitle(int index, TerminalSession session) {
        String name = session.mSessionName;
        String title = session.getTitle();
        if (!TextUtils.isEmpty(name)) return name;
        if (!TextUtils.isEmpty(title)) return title;
        return "Session " + (index + 1);
    }

    private int getTabTextColor(boolean darkTheme, TerminalSession session) {
        int defaultColor = darkTheme ? Color.WHITE : Color.BLACK;
        return session.isRunning() || session.getExitStatus() == 0 ? defaultColor : Color.RED;
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
