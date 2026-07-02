package com.termux.app.terminal;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.ui.TpChrome;
import com.termux.app.ui.TpIconView;
import com.termux.shared.android.PermissionUtils;
import com.termux.terminal.TerminalSession;

/**
 * Terminal HUD action center presented as a Material {@link BottomSheetDialog}.
 * Replaces the legacy long-press / hardware-menu {@code ContextMenu} with a
 * grouped, icon-led, single-tap sheet that shares the main terminal chrome.
 */
public class TermuxPlusActionSheet {

    private final TermuxActivity mActivity;
    private final float mDensity;
    private final Typeface mChromeTypeface;
    private BottomSheetDialog mDialog;

    public TermuxPlusActionSheet(TermuxActivity activity) {
        mActivity = activity;
        mDensity = activity.getResources().getDisplayMetrics().density;
        mChromeTypeface = TermuxTerminalFontManager.loadTerminalTypeface(activity);
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

    public void dismiss() {
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
        addRow(list, TpIconView.FOLDER, string(R.string.termuxplus_projects_title), null, false,
            mActivity::showWorkspaceLauncher);
        addRow(list, TpIconView.GRID, string(R.string.termuxplus_tab_overview), null, false,
            mActivity::showTabOverview);
        addRow(list, TpIconView.LINK, string(R.string.action_select_url), null, false,
            mActivity::tpSelectUrl);
        addRow(list, TpIconView.SHARE, string(R.string.action_share_transcript), null, false,
            mActivity::tpShareTranscript);
        if (mActivity.tpHasSelectedText())
            addRow(list, TpIconView.SHARE, string(R.string.action_share_selected_text), null, false,
                mActivity::tpShareSelectedText);
        addRow(list, TpIconView.REFRESH, string(R.string.action_reset_terminal), null, false,
            mActivity::tpResetTerminal);
        if (running) {
            String killTitle = mActivity.getString(R.string.action_kill_process, session.getPid());
            addRow(list, TpIconView.POWER, killTitle, null, true, mActivity::tpKillProcess);
        }

        // ---- View & appearance ----
        addSection(list, R.string.termuxplus_section_view);
        addRow(list, TpIconView.FULLSCREEN, string(R.string.termuxplus_fullscreen), null, false,
            mActivity::toggleFullscreen);
        addRow(list, TpIconView.PALETTE, string(R.string.action_style_terminal), null, false,
            mActivity::tpStyle);
        addRow(list, TpIconView.SCREEN_ON, string(R.string.action_toggle_keep_screen_on),
            mActivity.tpIsKeepScreenOn() ? "On" : "Off", false, mActivity::tpToggleKeepScreenOn);

        // ---- Tools ----
        addSection(list, R.string.termuxplus_section_tools);
        addBackgroundFloatingTerminalRow(list);
        addRow(list, TpIconView.BRACES, string(R.string.termuxplus_snippets_title), null, false,
            mActivity::tpSnippets);
        if (mActivity.tpIsAutoFillEnabled()) {
            addRow(list, TpIconView.USER, string(R.string.action_autofill_username), null, false,
                mActivity::tpAutofillUsername);
            addRow(list, TpIconView.KEY, string(R.string.action_autofill_password), null, false,
                mActivity::tpAutofillPassword);
        }

        // ---- App ----
        addSection(list, R.string.termuxplus_section_app);
        addRow(list, TpIconView.SETTINGS, string(R.string.action_open_settings), null, false,
            mActivity::tpSettings);
        addRow(list, TpIconView.HELP, string(R.string.action_open_help), null, false,
            mActivity::tpHelp);
        addRow(list, TpIconView.REPORT, string(R.string.action_report_issue), null, false,
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
        title.setTextColor(TpChrome.TEXT);
        title.setTextSize(15);
        title.setTypeface(mChromeTypeface, Typeface.BOLD);
        title.setLetterSpacing(0.04f);
        title.setIncludeFontPadding(false);
        title.setSingleLine(true);
        title.setPadding(dp(12), 0, dp(12), dp(8));
        return title;
    }

    private void addSection(LinearLayout parent, int textRes) {
        TextView header = new TextView(mActivity);
        header.setText(mActivity.getString(textRes).toUpperCase());
        header.setTextColor(TpChrome.TEXT_DIM);
        header.setTextSize(11);
        header.setLetterSpacing(0.08f);
        header.setTypeface(mChromeTypeface, Typeface.BOLD);
        header.setIncludeFontPadding(false);
        header.setPadding(dp(12), dp(12), dp(12), dp(6));
        parent.addView(header);
    }

    private void addRow(LinearLayout parent, int icon, String title,
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

        FrameLayout tile = createIconTile(icon, danger, false);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        tileParams.setMarginEnd(dp(12));
        row.addView(tile, tileParams);

        LinearLayout textColumn = new LinearLayout(mActivity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(mActivity);
        titleView.setText(title);
        titleView.setTextColor(danger ? TpChrome.ERROR : TpChrome.TEXT);
        titleView.setTextSize(14);
        titleView.setTypeface(mChromeTypeface, Typeface.NORMAL);
        titleView.setIncludeFontPadding(false);
        titleView.setSingleLine(true);
        textColumn.addView(titleView);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subView = new TextView(mActivity);
            subView.setText(subtitle);
            subView.setTextColor(TpChrome.TEXT_DIM);
            subView.setTextSize(12);
            subView.setTypeface(mChromeTypeface, Typeface.NORMAL);
            subView.setIncludeFontPadding(false);
            subView.setSingleLine(true);
            textColumn.addView(subView);
        }
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        parent.addView(row);
    }

    private void addBackgroundFloatingTerminalRow(LinearLayout parent) {
        addCheckRow(parent, TpIconView.FLOAT,
            string(R.string.termuxplus_background_floating_terminal_title),
            mActivity.tpIsBackgroundFloatingTerminalEnabled(),
            desired -> {
                if (desired && !PermissionUtils.checkDisplayOverOtherAppsPermission(mActivity))
                    dismiss();
                return mActivity.tpSetBackgroundFloatingTerminalEnabled(desired);
            });
    }

    private void addCheckRow(LinearLayout parent, int icon, String title,
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

        FrameLayout tile = createIconTile(icon, false, checked);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        tileParams.setMarginEnd(dp(12));
        row.addView(tile, tileParams);

        LinearLayout textColumn = new LinearLayout(mActivity);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = new TextView(mActivity);
        titleView.setText(title);
        titleView.setTextColor(TpChrome.TEXT);
        titleView.setTextSize(14);
        titleView.setTypeface(mChromeTypeface, Typeface.NORMAL);
        titleView.setIncludeFontPadding(false);
        titleView.setSingleLine(true);
        textColumn.addView(titleView);

        TextView subView = new TextView(mActivity);
        subView.setText(backgroundFloatingTerminalSummary(checked));
        subView.setTextColor(TpChrome.TEXT_DIM);
        subView.setTextSize(12);
        subView.setTypeface(mChromeTypeface, Typeface.NORMAL);
        subView.setIncludeFontPadding(false);
        subView.setSingleLine(true);
        textColumn.addView(subView);
        row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView stateView = createStateChip(checked);
        row.addView(stateView, new LinearLayout.LayoutParams(dp(44), dp(26)));

        row.setOnClickListener(v -> {
            boolean newChecked = action.setChecked(!"ON".contentEquals(stateView.getText()));
            subView.setText(backgroundFloatingTerminalSummary(newChecked));
            updateStateChip(stateView, newChecked);
            if (tile.getChildAt(0) instanceof TpIconView)
                ((TpIconView) tile.getChildAt(0)).setActive(newChecked);
        });

        parent.addView(row);
    }

    private FrameLayout createIconTile(int icon, boolean danger, boolean active) {
        FrameLayout tile = new FrameLayout(mActivity);
        tile.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_icon_tile_bg));
        TpIconView glyph = new TpIconView(mActivity, icon);
        glyph.setColor(danger ? TpChrome.ERROR : TpChrome.ACCENT);
        glyph.setActiveColor(TpChrome.ACCENT);
        glyph.setActive(active);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(28), dp(28));
        iconParams.gravity = Gravity.CENTER;
        tile.addView(glyph, iconParams);
        return tile;
    }

    private TextView createStateChip(boolean checked) {
        TextView state = new TextView(mActivity);
        state.setGravity(Gravity.CENTER);
        state.setIncludeFontPadding(false);
        state.setTextSize(10);
        state.setTypeface(mChromeTypeface, Typeface.BOLD);
        updateStateChip(state, checked);
        return state;
    }

    private void updateStateChip(TextView state, boolean checked) {
        state.setText(checked ? "ON" : "OFF");
        state.setTextColor(checked ? TpChrome.ACCENT : TpChrome.TEXT_DIM);
        state.setBackground(TpChrome.roundRect(checked ? color(R.color.termuxplus_accent_soft) : color(R.color.termuxplus_pill_bg),
            dp(7), checked ? TpChrome.HAIRLINE : color(R.color.termuxplus_pill_border), dp(1)));
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
