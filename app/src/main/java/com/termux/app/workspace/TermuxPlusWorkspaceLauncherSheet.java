package com.termux.app.workspace;

import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalFontManager;
import com.termux.app.ui.TpChrome;
import com.termux.app.ui.TpIconView;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.util.List;
import java.util.Locale;

public class TermuxPlusWorkspaceLauncherSheet {

    private final TermuxActivity mActivity;
    private final Typeface mTypeface;
    private BottomSheetDialog mDialog;
    private LinearLayout mList;

    public TermuxPlusWorkspaceLauncherSheet(TermuxActivity activity) {
        mActivity = activity;
        mTypeface = TermuxTerminalFontManager.loadTerminalTypeface(activity);
    }

    public void show() {
        mDialog = new BottomSheetDialog(mActivity, R.style.Theme_TermuxPlus_BottomSheet);
        mDialog.setContentView(buildContent());
        BottomSheetBehavior<FrameLayout> behavior = mDialog.getBehavior();
        if (behavior != null) {
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
        mDialog.show();
        loadWorkspacesAsync();
    }

    private void dismiss() {
        if (mDialog != null)
            mDialog.dismiss();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(12));

        root.addView(buildHandle());
        root.addView(buildTitle());

        NestedScrollView scroll = new NestedScrollView(mActivity);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        mList = new LinearLayout(mActivity);
        mList.setOrientation(LinearLayout.VERTICAL);
        mList.setPadding(dp(2), dp(2), dp(2), 0);
        scroll.addView(mList, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        showMessage(mActivity.getString(R.string.termuxplus_projects_loading));
        return root;
    }

    private View buildHandle() {
        View handle = new View(mActivity);
        handle.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_sheet_handle));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(4));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(4);
        params.bottomMargin = dp(10);
        handle.setLayoutParams(params);
        return handle;
    }

    private View buildTitle() {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(8), dp(8));

        TextView title = new TextView(mActivity);
        title.setText(mActivity.getString(R.string.termuxplus_projects_title));
        title.setTextColor(TpChrome.TEXT);
        title.setTextSize(16);
        title.setTypeface(mTypeface, Typeface.BOLD);
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TpIconView copyConfigPath = new TpIconView(mActivity, TpIconView.LINK);
        copyConfigPath.setColor(TpChrome.TEXT_DIM);
        copyConfigPath.setContentDescription(mActivity.getString(R.string.termuxplus_agent_launch_config_copy_path));
        copyConfigPath.setOnClickListener(v -> copyAgentLaunchConfigPath());
        row.addView(copyConfigPath, new LinearLayout.LayoutParams(dp(30), dp(30)));

        return row;
    }

    private void loadWorkspacesAsync() {
        new Thread(() -> {
            List<TermuxPlusWorkspace> workspaces = TermuxPlusWorkspaceRepository.loadWorkspaces();
            mActivity.runOnUiThread(() -> {
                if (mDialog == null || !mDialog.isShowing() || mList == null) return;
                renderWorkspaces(workspaces);
            });
        }, "tp-workspace-scan").start();
    }

    private void renderWorkspaces(List<TermuxPlusWorkspace> workspaces) {
        mList.removeAllViews();
        if (workspaces == null || workspaces.isEmpty()) {
            showMessage(mActivity.getString(R.string.termuxplus_projects_empty));
            return;
        }

        for (TermuxPlusWorkspace workspace : workspaces) {
            addWorkspaceHeader(workspace);
            for (TermuxPlusAgentSession session : workspace.getSessions())
                addAgentSessionRow(workspace, session);
            addDivider();
        }
    }

    private void addWorkspaceHeader(TermuxPlusWorkspace workspace) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(6));
        row.setMinimumHeight(dp(50));

        TpIconView folder = new TpIconView(mActivity, TpIconView.FOLDER);
        folder.setColor(TpChrome.TEXT_DIM);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        iconParams.setMarginEnd(dp(10));
        row.addView(folder, iconParams);

        LinearLayout textColumn = new LinearLayout(mActivity);
        textColumn.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(mActivity);
        name.setText(workspace.getName());
        name.setTextColor(TpChrome.TEXT);
        name.setTextSize(15);
        name.setTypeface(mTypeface, Typeface.BOLD);
        name.setIncludeFontPadding(false);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(name);

        TextView path = new TextView(mActivity);
        path.setText(shortPath(workspace.getPath()));
        path.setTextColor(TpChrome.TEXT_DIM);
        path.setTextSize(11);
        path.setTypeface(mTypeface, Typeface.NORMAL);
        path.setIncludeFontPadding(false);
        path.setSingleLine(true);
        path.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(path);

        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TpIconView add = new TpIconView(mActivity, TpIconView.ADD);
        add.setColor(TpChrome.TEXT);
        add.setContentDescription("New workspace session");
        add.setOnClickListener(v -> showWorkspaceActionMenu(add, workspace));
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(dp(32), dp(32));
        addParams.setMarginStart(dp(8));
        row.addView(add, addParams);

        mList.addView(row, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addAgentSessionRow(TermuxPlusWorkspace workspace, TermuxPlusAgentSession session) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_action_row_bg));
        row.setPadding(dp(12), dp(6), dp(10), dp(6));
        row.setMinimumHeight(dp(44));
        row.setOnClickListener(v -> openAgentSession(workspace, session));

        TextView title = new TextView(mActivity);
        title.setText(session.getTitle());
        title.setTextColor(TpChrome.TEXT);
        title.setTextSize(14);
        title.setTypeface(mTypeface, Typeface.NORMAL);
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout meta = new LinearLayout(mActivity);
        meta.setOrientation(LinearLayout.VERTICAL);
        meta.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);

        TextView time = new TextView(mActivity);
        time.setText(relativeTime(session.getUpdatedAt()));
        time.setTextColor(TpChrome.TEXT_DIM);
        time.setTextSize(12);
        time.setTypeface(mTypeface, Typeface.NORMAL);
        time.setIncludeFontPadding(false);
        time.setGravity(Gravity.END);
        time.setSingleLine(true);
        meta.addView(time, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView agent = new TextView(mActivity);
        agent.setText(session.getAgent().toUpperCase(Locale.US));
        agent.setTextColor(TpChrome.TEXT_DIM);
        agent.setTextSize(10);
        agent.setTypeface(mTypeface, Typeface.BOLD);
        agent.setIncludeFontPadding(false);
        agent.setGravity(Gravity.END);
        agent.setSingleLine(true);
        meta.addView(agent, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT);
        metaParams.setMarginStart(dp(8));
        row.addView(meta, metaParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(1);
        mList.addView(row, params);
    }

    private void openAgentSession(TermuxPlusWorkspace workspace, TermuxPlusAgentSession session) {
        String command;
        if (TermuxPlusAgentSession.AGENT_CODEX.equals(session.getAgent()))
            command = TermuxPlusAgentLaunchConfig.getResumeCommand(
                TermuxPlusAgentSession.AGENT_CODEX, session.getSessionId());
        else
            command = TermuxPlusAgentLaunchConfig.getResumeCommand(
                TermuxPlusAgentSession.AGENT_CLAUDE, session.getSessionId());

        openWorkspaceCommand(workspace, session.getAgent() + ":" + workspace.getName(), command);
    }

    private void showWorkspaceActionMenu(View anchor, TermuxPlusWorkspace workspace) {
        LinearLayout menu = new LinearLayout(mActivity);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(4), dp(4), dp(4), dp(4));
        menu.setBackground(TpChrome.roundRect(TpChrome.SOLID_BG, dp(8), TpChrome.HAIRLINE, Math.max(1, dp(1))));

        PopupWindow popup = new PopupWindow(menu, dp(136), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(TpChrome.roundRect(0x00000000, 0));
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
            popup.setElevation(dp(6));

        menu.addView(createWorkspaceActionMenuItem(mActivity.getString(R.string.termuxplus_project_shell),
            () -> {
                popup.dismiss();
                openWorkspaceCommand(workspace, "sh:" + workspace.getName(), null);
            }));
        menu.addView(createWorkspaceActionMenuItem(mActivity.getString(R.string.termuxplus_project_new_codex),
            () -> {
                popup.dismiss();
                openWorkspaceCommand(workspace, "codex:" + workspace.getName(),
                    TermuxPlusAgentLaunchConfig.getNewCommand(TermuxPlusAgentSession.AGENT_CODEX));
            }));
        menu.addView(createWorkspaceActionMenuItem(mActivity.getString(R.string.termuxplus_project_new_claude),
            () -> {
                popup.dismiss();
                openWorkspaceCommand(workspace, "claude:" + workspace.getName(),
                    TermuxPlusAgentLaunchConfig.getNewCommand(TermuxPlusAgentSession.AGENT_CLAUDE));
            }));

        popup.showAsDropDown(anchor, -dp(104), dp(2), Gravity.NO_GRAVITY);
    }

    private TextView createWorkspaceActionMenuItem(String text, Runnable action) {
        TextView item = new TextView(mActivity);
        item.setText(text);
        item.setTextColor(TpChrome.TEXT);
        item.setTextSize(13);
        item.setTypeface(mTypeface, Typeface.BOLD);
        item.setIncludeFontPadding(false);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setSingleLine(true);
        item.setPadding(dp(10), 0, dp(10), dp(1));
        item.setMinHeight(dp(34));
        item.setBackground(TpChrome.pressBg(mActivity, false));
        item.setOnClickListener(v -> action.run());
        item.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        return item;
    }

    private void copyAgentLaunchConfigPath() {
        try {
            TermuxPlusAgentLaunchConfig.ensureDefaultConfig();
            ShareUtils.copyTextToClipboard(mActivity, "TermuxPlus agent launch config",
                TermuxPlusAgentLaunchConfig.getConfigFile().getAbsolutePath(),
                mActivity.getString(R.string.termuxplus_agent_launch_config_path_copied));
        } catch (Exception e) {
            Logger.logWarn("TermuxPlusWorkspaceLauncherSheet",
                "Failed to create agent launch config: " + e.getMessage());
            Logger.showToast(mActivity,
                mActivity.getString(R.string.termuxplus_agent_launch_config_copy_failed), true);
        }
    }

    private void openWorkspaceCommand(TermuxPlusWorkspace workspace, String sessionName, String command) {
        if (workspace == null || workspace.getPath() == null) return;
        if (mActivity.getTermuxTerminalSessionClient() == null) {
            Logger.showToast(mActivity, "Terminal service is not ready.", true);
            return;
        }

        dismiss();
        mActivity.getTermuxTerminalSessionClient().addWorkspaceSession(workspace.getPath(), sessionName, command);
    }

    private void showMessage(String message) {
        if (mList == null) return;
        mList.removeAllViews();
        TextView view = new TextView(mActivity);
        view.setText(message);
        view.setTextColor(TpChrome.TEXT_DIM);
        view.setTextSize(13);
        view.setTypeface(mTypeface, Typeface.NORMAL);
        view.setGravity(Gravity.CENTER);
        view.setIncludeFontPadding(false);
        view.setPadding(dp(12), dp(24), dp(12), dp(24));
        mList.addView(view, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void addDivider() {
        View divider = new View(mActivity);
        divider.setBackgroundColor(TpChrome.HAIRLINE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        params.setMargins(dp(10), dp(6), dp(10), dp(4));
        mList.addView(divider, params);
    }

    private String shortPath(String path) {
        if (path == null) return "";
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        if (path.equals(home)) return "~";
        if (path.startsWith(home + "/"))
            return "~/" + path.substring(home.length() + 1);
        return path;
    }

    private String relativeTime(long timeMillis) {
        if (timeMillis <= 0) return "";
        long delta = Math.max(0, System.currentTimeMillis() - timeMillis);
        long minute = 60 * 1000L;
        long hour = 60 * minute;
        long day = 24 * hour;
        long week = 7 * day;
        if (delta < minute) return "now";
        if (delta < hour) return String.format(Locale.US, "%dm", delta / minute);
        if (delta < day) return String.format(Locale.US, "%dh", delta / hour);
        if (delta < week) return String.format(Locale.US, "%dd", delta / day);
        if (delta < 9 * week) return String.format(Locale.US, "%dw", delta / week);
        return String.format(Locale.US, "%dmo", Math.max(1, delta / (30 * day)));
    }

    private int dp(float value) {
        return TpChrome.dp(mActivity, value);
    }
}
