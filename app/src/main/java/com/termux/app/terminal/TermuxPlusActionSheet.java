package com.termux.app.terminal;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.android.PermissionUtils;
import com.termux.terminal.TerminalSession;

/**
 * A modern, Termius-style "action center" presented as a Material {@link BottomSheetDialog}
 * with a fully custom dark skin. Replaces the legacy long-press / hardware-menu
 * {@code ContextMenu} with a grouped, icon-led, single-tap sheet.
 */
public class TermuxPlusActionSheet {

    private final TermuxActivity mActivity;
    private final float mDensity;
    private BottomSheetDialog mDialog;

    public TermuxPlusActionSheet(TermuxActivity activity) {
        mActivity = activity;
        mDensity = activity.getResources().getDisplayMetrics().density;
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
    }

    private void dismiss() {
        if (mDialog != null) mDialog.dismiss();
    }

    private View buildContent() {
        LinearLayout root = new LinearLayout(mActivity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(8), dp(8), dp(8), dp(12));

        root.addView(buildHandle());
        root.addView(buildTitle());

        NestedScrollView scroll = new NestedScrollView(mActivity);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        LinearLayout list = new LinearLayout(mActivity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(2), dp(2), dp(2), 0);
        scroll.addView(list, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TerminalSession session = mActivity.getCurrentSession();
        boolean running = session != null && session.isRunning();

        // ---- Terminal ----
        addSection(list, R.string.termuxplus_section_terminal);
        addRow(list, R.drawable.ic_tp_grid, string(R.string.termuxplus_tab_overview), null, false,
            mActivity::showTabOverview);
        addRow(list, R.drawable.ic_tp_link, string(R.string.action_select_url), null, false,
            mActivity::tpSelectUrl);
        addRow(list, R.drawable.ic_tp_share, string(R.string.action_share_transcript), null, false,
            mActivity::tpShareTranscript);
        if (mActivity.tpHasSelectedText())
            addRow(list, R.drawable.ic_tp_share, string(R.string.action_share_selected_text), null, false,
                mActivity::tpShareSelectedText);
        addRow(list, R.drawable.ic_tp_refresh, string(R.string.action_reset_terminal), null, false,
            mActivity::tpResetTerminal);
        if (running) {
            String killTitle = mActivity.getString(R.string.action_kill_process, session.getPid());
            addRow(list, R.drawable.ic_tp_power, killTitle, null, true, mActivity::tpKillProcess);
        }

        // ---- View & appearance ----
        addSection(list, R.string.termuxplus_section_view);
        addRow(list, R.drawable.ic_tp_fullscreen, string(R.string.termuxplus_fullscreen), null, false,
            mActivity::toggleFullscreen);
        addRow(list, R.drawable.ic_tp_palette, string(R.string.action_style_terminal), null, false,
            mActivity::tpStyle);
        addRow(list, R.drawable.ic_tp_screen_on, string(R.string.action_toggle_keep_screen_on),
            mActivity.tpIsKeepScreenOn() ? "On" : "Off", false, mActivity::tpToggleKeepScreenOn);

        // ---- Tools ----
        addSection(list, R.string.termuxplus_section_tools);
        addBackgroundFloatingTerminalRow(list);
        addRow(list, R.drawable.ic_tp_braces, string(R.string.termuxplus_snippets_title), null, false,
            mActivity::tpSnippets);
        if (mActivity.tpIsAutoFillEnabled()) {
            addRow(list, R.drawable.ic_tp_user, string(R.string.action_autofill_username), null, false,
                mActivity::tpAutofillUsername);
            addRow(list, R.drawable.ic_tp_key, string(R.string.action_autofill_password), null, false,
                mActivity::tpAutofillPassword);
        }

        // ---- App ----
        addSection(list, R.string.termuxplus_section_app);
        addRow(list, R.drawable.ic_tp_settings, string(R.string.action_open_settings), null, false,
            mActivity::tpSettings);
        addRow(list, R.drawable.ic_tp_help, string(R.string.action_open_help), null, false,
            mActivity::tpHelp);
        addRow(list, R.drawable.ic_tp_report, string(R.string.action_report_issue), null, false,
            mActivity::tpReport);

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
        TextView title = new TextView(mActivity);
        TerminalSession session = mActivity.getCurrentSession();
        String name = session == null ? null : (session.mSessionName != null ? session.mSessionName : session.getTitle());
        title.setText(name != null && !name.isEmpty()
            ? mActivity.getString(R.string.termuxplus_actions_title) + "  ·  " + name
            : mActivity.getString(R.string.termuxplus_actions_title));
        title.setTextColor(color(R.color.termuxplus_text_primary));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setPadding(dp(12), 0, dp(12), dp(8));
        return title;
    }

    private void addSection(LinearLayout parent, int textRes) {
        TextView header = new TextView(mActivity);
        header.setText(mActivity.getString(textRes).toUpperCase());
        header.setTextColor(color(R.color.termuxplus_text_muted));
        header.setTextSize(11);
        header.setLetterSpacing(0.08f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(dp(12), dp(12), dp(12), dp(6));
        parent.addView(header);
    }

    private void addRow(LinearLayout parent, @DrawableRes int iconRes, String title,
                        @Nullable String subtitle, boolean danger, Runnable action) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_action_row_bg));
        row.setPadding(dp(10), dp(8), dp(12), dp(8));
        row.setMinimumHeight(dp(54));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(2);
        row.setLayoutParams(rowParams);
        row.setOnClickListener(v -> {
            dismiss();
            View terminalView = mActivity.getTerminalView();
            if (terminalView != null) terminalView.post(action);
            else action.run();
        });

        FrameLayout tile = new FrameLayout(mActivity);
        tile.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_icon_tile_bg));
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color(danger ? R.color.termuxplus_text_error : R.color.termuxplus_text_primary));
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(22), dp(22));
        iconParams.gravity = Gravity.CENTER;
        tile.addView(icon, iconParams);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        tileParams.setMarginEnd(dp(12));
        row.addView(tile, tileParams);

        LinearLayout textColumn = new LinearLayout(mActivity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(mActivity);
        titleView.setText(title);
        titleView.setTextColor(color(danger ? R.color.termuxplus_text_error : R.color.termuxplus_text_primary));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT);
        titleView.setSingleLine(true);
        textColumn.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = new TextView(mActivity);
            subView.setText(subtitle);
            subView.setTextColor(color(R.color.termuxplus_text_secondary));
            subView.setTextSize(12);
            subView.setSingleLine(true);
            textColumn.addView(subView);
        }
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

    private void addBackgroundFloatingTerminalRow(LinearLayout parent) {
        addCheckRow(parent, R.drawable.ic_tp_float,
            string(R.string.termuxplus_background_floating_terminal_title),
            mActivity.tpIsBackgroundFloatingTerminalEnabled(),
            desired -> {
                if (desired && !PermissionUtils.checkDisplayOverOtherAppsPermission(mActivity))
                    dismiss();
                return mActivity.tpSetBackgroundFloatingTerminalEnabled(desired);
            });
    }

    private void addCheckRow(LinearLayout parent, @DrawableRes int iconRes, String title,
                             boolean checked, CheckAction action) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_action_row_bg));
        row.setPadding(dp(10), dp(8), dp(12), dp(8));
        row.setMinimumHeight(dp(54));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(2);
        row.setLayoutParams(rowParams);

        FrameLayout tile = new FrameLayout(mActivity);
        tile.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_icon_tile_bg));
        ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(color(R.color.termuxplus_text_primary));
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(22), dp(22));
        iconParams.gravity = Gravity.CENTER;
        tile.addView(icon, iconParams);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        tileParams.setMarginEnd(dp(12));
        row.addView(tile, tileParams);

        LinearLayout textColumn = new LinearLayout(mActivity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(mActivity);
        titleView.setText(title);
        titleView.setTextColor(color(R.color.termuxplus_text_primary));
        titleView.setTextSize(15);
        titleView.setTypeface(Typeface.DEFAULT);
        titleView.setSingleLine(true);
        textColumn.addView(titleView);

        TextView subView = new TextView(mActivity);
        subView.setText(backgroundFloatingTerminalSummary(checked));
        subView.setTextColor(color(R.color.termuxplus_text_secondary));
        subView.setTextSize(12);
        subView.setSingleLine(true);
        textColumn.addView(subView);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        CheckBox checkBox = new CheckBox(mActivity);
        checkBox.setChecked(checked);
        checkBox.setClickable(false);
        checkBox.setFocusable(false);
        row.addView(checkBox, new LinearLayout.LayoutParams(dp(40), dp(40)));

        row.setOnClickListener(v -> {
            boolean newChecked = action.setChecked(!checkBox.isChecked());
            checkBox.setChecked(newChecked);
            subView.setText(backgroundFloatingTerminalSummary(newChecked));
        });

        parent.addView(row);
    }

    private String backgroundFloatingTerminalSummary(boolean checked) {
        return string(checked
            ? R.string.termuxplus_background_floating_terminal_sheet_on
            : R.string.termuxplus_background_floating_terminal_sheet_off);
    }

    private interface CheckAction {
        boolean setChecked(boolean checked);
    }

    private String string(int resId) {
        return mActivity.getString(resId);
    }

    private int color(int colorResId) {
        return ContextCompat.getColor(mActivity, colorResId);
    }

    private int dp(int value) {
        return Math.round(value * mDensity);
    }
}
