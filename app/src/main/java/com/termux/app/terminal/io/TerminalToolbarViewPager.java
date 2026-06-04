package com.termux.app.terminal.io;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.widget.NestedScrollView;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.TermuxPlusSnippetsActivity;
import com.termux.app.ui.TpChrome;
import com.termux.app.ui.TpIconView;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TerminalToolbarViewPager {

    public static final int MODE_KEYS = 0;

    public static final String MODE_NAME_KEYS = "keys";
    private static final String EXTRA_KEYS_VIEW_TAG_PREFIX = "termuxplus_extra_keys_page_";
    private static final String EXTRA_KEYS_PAGE_TAG_PREFIX = "termuxplus_extra_keys_container_";

    public static class PageAdapter extends PagerAdapter {

        final TermuxActivity mActivity;
        BottomSheetDialog mSnippetsSheetDialog;
        View mSnippetsSheetContent;
        LinearLayout mSnippetsPopupList;
        EditText mSnippetSearchInput;

        public PageAdapter(TermuxActivity activity) {
            this.mActivity = activity;
        }

        @Override
        public int getCount() {
            TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
            return extraKeys == null ? 0 : extraKeys.getExtraKeysPageCount();
        }

        @Override
        public boolean isViewFromObject(@NonNull View view, @NonNull Object object) {
            return view == object;
        }

        @NonNull
        @Override
        public Object instantiateItem(@NonNull ViewGroup collection, int position) {
            LayoutInflater inflater = LayoutInflater.from(mActivity);
            View layout = inflateExtraKeysPage(inflater, collection, position);
            collection.addView(layout);
            return layout;
        }

        @Override
        public void destroyItem(@NonNull ViewGroup collection, int position, @NonNull Object view) {
            dismissSnippetsPopup();
            collection.removeView((View) view);
        }

        private View inflateExtraKeysPage(LayoutInflater inflater, ViewGroup collection, int position) {
            View layout = inflater.inflate(R.layout.view_terminal_toolbar_extra_keys, collection, false);
            ExtraKeysView extraKeysView = layout.findViewById(R.id.terminal_toolbar_extra_keys);
            ExtraKeysView expandedExtraKeysView = layout.findViewById(R.id.terminal_toolbar_extra_keys_expanded);
            configureExtraKeysView(extraKeysView);
            configureExtraKeysView(expandedExtraKeysView);
            layout.setTag(getExtraKeysPageTag(position));
            extraKeysView.setTag(getExtraKeysViewTag(position));
            if (position == mActivity.getTerminalToolbarViewPager().getCurrentItem())
                mActivity.setExtraKeysView(extraKeysView);
            reloadExtraKeysViews(layout, position);

            if (mActivity.getProperties().isUsingFullScreen() && mActivity.getProperties().isUsingFullScreenWorkAround())
                FullScreenWorkAround.apply(mActivity);

            return layout;
        }

        public void reloadExtraKeysViews() {
            ViewPager viewPager = mActivity.getTerminalToolbarViewPager();
            if (viewPager == null) return;

            for (int i = 0; i < viewPager.getChildCount(); i++) {
                View child = viewPager.getChildAt(i);
                Object tag = child.getTag();
                if (!(tag instanceof String)) continue;
                String tagValue = (String) tag;
                if (!tagValue.startsWith(EXTRA_KEYS_PAGE_TAG_PREFIX)) continue;
                int position = parseExtraKeysPagePosition(tagValue);
                if (position >= 0)
                    reloadExtraKeysViews(child, position);
            }
        }

        public void refreshExtraKeysPanelLayouts() {
            ViewPager viewPager = mActivity.getTerminalToolbarViewPager();
            if (viewPager == null) return;

            for (int i = 0; i < viewPager.getChildCount(); i++)
                refreshExtraKeysPanelLayout(viewPager.getChildAt(i));
        }

        private void reloadExtraKeysViews(View layout, int position) {
            TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
            if (extraKeys == null) return;

            ExtraKeysView primaryExtraKeysView = layout.findViewById(R.id.terminal_toolbar_extra_keys);
            ExtraKeysView expandedExtraKeysView = layout.findViewById(R.id.terminal_toolbar_extra_keys_expanded);
            ExtraKeysInfo primaryExtraKeysInfo = extraKeys.getPrimaryExtraKeysInfo(position);
            ExtraKeysInfo expandedExtraKeysInfo = extraKeys.getExpandedExtraKeysInfo(position);
            if (primaryExtraKeysView != null)
                primaryExtraKeysView.reload(primaryExtraKeysInfo, mActivity.getTermuxPlusPinnedExtraKeysHeight());
            if (expandedExtraKeysView != null)
                expandedExtraKeysView.reload(expandedExtraKeysInfo, Math.max(mActivity.getTermuxPlusExpandedExtraKeysPanelHeight(), mActivity.getTerminalToolbarDefaultHeight()));

            refreshExtraKeysPanelLayout(layout);
        }

        private void configureExtraKeysView(ExtraKeysView extraKeysView) {
            if (extraKeysView == null) return;

            extraKeysView.setExtraKeysViewClient(mActivity.getTermuxTerminalExtraKeys());
            extraKeysView.setButtonTextAllCaps(mActivity.getProperties().shouldExtraKeysTextBeAllCaps());
            // HUD keys: borderless on the frosted bar (transparent rest), text in muted
            // light, modifier-active text in phosphor green, and a green press tint.
            extraKeysView.setButtonColors(
                TpChrome.TEXT,
                TpChrome.ACCENT,
                Color.TRANSPARENT,
                TpChrome.PRESS);
            extraKeysView.setFixedButtonWidthPx(0);
        }

        private void refreshExtraKeysPanelLayout(View layout) {
            if (layout == null) return;

            ExtraKeysView primaryExtraKeysView = layout.findViewById(R.id.terminal_toolbar_extra_keys);
            View expandedContainer = layout.findViewById(R.id.terminal_toolbar_extra_keys_expanded_container);
            int pinnedHeight = mActivity.getTermuxPlusPinnedExtraKeysHeight();
            int expandedHeight = mActivity.getTermuxPlusExpandedExtraKeysPanelHeight();

            if (primaryExtraKeysView != null) {
                ViewGroup.LayoutParams primaryParams = primaryExtraKeysView.getLayoutParams();
                if (primaryParams.height != pinnedHeight) {
                    primaryParams.height = pinnedHeight;
                    primaryExtraKeysView.setLayoutParams(primaryParams);
                }
            }

            if (expandedContainer != null) {
                ViewGroup.LayoutParams expandedParams = expandedContainer.getLayoutParams();
                if (expandedParams.height != expandedHeight) {
                    expandedParams.height = expandedHeight;
                    expandedContainer.setLayoutParams(expandedParams);
                }
                expandedContainer.setVisibility(expandedHeight > 0 ? View.VISIBLE : View.GONE);
            }
        }

        private int parseExtraKeysPagePosition(String tagValue) {
            try {
                return Integer.parseInt(tagValue.substring(EXTRA_KEYS_PAGE_TAG_PREFIX.length()));
            } catch (Exception e) {
                return -1;
            }
        }

        private void renderSnippets(EditText searchInput, LinearLayout snippetsList) {
            if (snippetsList == null) return;
            snippetsList.removeAllViews();

            if (!mActivity.getPreferences().areTermuxPlusSnippetsEnabled()) {
                snippetsList.addView(createMessageView(mActivity.getString(R.string.termuxplus_snippets_disabled)));
                return;
            }

            List<TermuxPlusSnippet> snippets = TermuxPlusSnippetRepository.getSnippets(mActivity);
            String query = searchInput == null ? "" : searchInput.getText().toString();
            int count = 0;
            for (TermuxPlusSnippet snippet : snippets) {
                if (!snippet.matches(query)) continue;
                snippetsList.addView(createSnippetRow(snippet));
                count++;
            }
            if (count == 0)
                snippetsList.addView(createMessageView(mActivity.getString(R.string.termuxplus_no_snippets)));
        }

        private View createSnippetRow(TermuxPlusSnippet snippet) {
            LinearLayout row = new LinearLayout(mActivity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(62));
            row.setPadding(dp(10), dp(8), dp(12), dp(8));
            row.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_action_row_bg));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(0, 0, 0, dp(2));
            row.setLayoutParams(rowParams);

            FrameLayout tile = new FrameLayout(mActivity);
            tile.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_icon_tile_bg));
            TpIconView icon = new TpIconView(mActivity, TpIconView.BRACES);
            icon.setColor(TpChrome.ACCENT);
            FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(28), dp(28));
            iconParams.gravity = Gravity.CENTER;
            tile.addView(icon, iconParams);
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(38), dp(38));
            tileParams.setMarginEnd(dp(12));
            row.addView(tile, tileParams);

            LinearLayout textColumn = new LinearLayout(mActivity);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            TextView title = createSnippetText(snippet.getTitle(), color(R.color.termuxplus_text_primary), 14, Typeface.NORMAL, false);
            TextView command = createSnippetText(snippet.getCommand(), color(R.color.termuxplus_text_secondary), 11, Typeface.NORMAL, true);
            TextView description = createSnippetText(snippet.getDescription(), color(R.color.termuxplus_text_secondary), 11, Typeface.NORMAL, false);
            String metaText = buildSnippetMetaText(snippet);
            TextView meta = createSnippetText(metaText, color(R.color.termuxplus_text_muted), 10, Typeface.NORMAL, false);
            textColumn.addView(title);
            if (!TextUtils.isEmpty(snippet.getCommand())) textColumn.addView(command);
            if (!TextUtils.isEmpty(snippet.getDescription())) textColumn.addView(description);
            if (!TextUtils.isEmpty(metaText)) textColumn.addView(meta);
            row.addView(textColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            Button insert = createSnippetActionButton(mActivity.getString(R.string.termuxplus_insert), false);
            insert.setOnClickListener(v -> sendSnippetText(snippet.getCommand(), false));
            row.addView(insert, createSnippetActionLayoutParams(60));

            Button run = createSnippetActionButton(mActivity.getString(R.string.termuxplus_run), true);
            run.setOnClickListener(v -> sendSnippetText(snippet.getCommand(), true));
            row.addView(run, createSnippetActionLayoutParams(54));

            row.setOnClickListener(v -> sendSnippetText(snippet.getCommand(), snippet.shouldRunByDefault()));
            return row;
        }

        private TextView createSnippetText(String text, int color, int textSize, int typefaceStyle, boolean monospace) {
            TextView textView = new TextView(mActivity);
            textView.setText(text);
            textView.setTextColor(color);
            textView.setTextSize(textSize);
            textView.setIncludeFontPadding(false);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setTypeface(monospace ? Typeface.MONOSPACE : Typeface.DEFAULT, typefaceStyle);
            return textView;
        }

        private TextView createMessageView(String text) {
            TextView textView = new TextView(mActivity);
            textView.setText(text);
            textView.setTextColor(color(R.color.termuxplus_text_secondary));
            textView.setTextSize(12);
            textView.setIncludeFontPadding(false);
            textView.setPadding(dp(12), dp(8), dp(12), dp(8));
            return textView;
        }

        private Button createToolbarButton(String text) {
            Button button = new Button(mActivity);
            button.setText(text);
            styleActionButton(button, false);
            return button;
        }

        private Button createSnippetActionButton(String text, boolean primary) {
            Button button = new Button(mActivity);
            button.setText(text);
            styleActionButton(button, primary);
            return button;
        }

        private void styleTextInput(EditText editText) {
            editText.setTextSize(12);
            editText.setIncludeFontPadding(false);
            editText.setTextColor(color(R.color.termuxplus_text_primary));
            editText.setHintTextColor(color(R.color.termuxplus_text_muted));
            editText.setTypeface(Typeface.MONOSPACE);
        }

        private void styleActionButton(Button button, boolean primary) {
            button.setAllCaps(false);
            button.setGravity(Gravity.CENTER);
            button.setIncludeFontPadding(false);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(dp(8), 0, dp(8), 0);
            button.setSingleLine(true);
            button.setTextSize(12);
            button.setTextColor(primary ? TpChrome.ACCENT : TpChrome.TEXT_DIM);
            button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            button.setBackground(ContextCompat.getDrawable(mActivity,
                primary ? R.drawable.tp_accent_button_bg : R.drawable.tp_chip_bg));
        }

        private LinearLayout.LayoutParams createSnippetActionLayoutParams(int widthDp) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(34));
            params.setMargins(dp(4), 0, 0, 0);
            return params;
        }

        private String buildSnippetMetaText(TermuxPlusSnippet snippet) {
            StringBuilder builder = new StringBuilder();
            if (!TextUtils.isEmpty(snippet.getCategory()))
                builder.append(snippet.getCategory());
            for (String tag : snippet.getTags()) {
                if (builder.length() > 0) builder.append("  ");
                builder.append('#').append(tag);
            }
            return builder.toString();
        }

        private void sendSnippetText(String text, boolean run) {
            sendTextToSession(text, run);
            dismissSnippetsPopup();
            mActivity.getTerminalView().requestFocus();
        }

        private void sendTextToSession(String text, boolean run) {
            TerminalSession session = mActivity.getCurrentSession();
            if (session == null) return;

            if (session.isRunning()) {
                String textToSend = text == null ? "" : text;
                if (run) textToSend += "\r";
                mActivity.getTerminalView().scrollToBottomAndRender();
                session.write(textToSend);
            } else {
                mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
            }
        }

        public void showSnippetsPopup() {
            View decorView = mActivity.getWindow().getDecorView();
            if (decorView == null || !decorView.isAttachedToWindow()) return;

            if (mSnippetsSheetDialog == null) {
                mSnippetsSheetContent = createSnippetsSheetContent();
                mSnippetsSheetDialog = new BottomSheetDialog(mActivity, R.style.Theme_TermuxPlus_BottomSheet);
                mSnippetsSheetDialog.setContentView(mSnippetsSheetContent);
                mSnippetsSheetDialog.setOnDismissListener(dialog -> clearSnippetsPopupReferences());
                mSnippetsSheetDialog.setOnShowListener(dialog -> configureSnippetsSheet());
            }

            renderSnippets(mSnippetSearchInput, mSnippetsPopupList);
            if (!mSnippetsSheetDialog.isShowing())
                mSnippetsSheetDialog.show();
            else
                configureSnippetsSheet();
        }

        public void dismissSnippetsPopup() {
            BottomSheetDialog dialog = mSnippetsSheetDialog;
            if (dialog == null) return;

            dialog.setOnDismissListener(null);
            dialog.dismiss();
            clearSnippetsPopupReferences();
        }

        private void clearSnippetsPopupReferences() {
            mSnippetsSheetDialog = null;
            mSnippetsSheetContent = null;
            mSnippetsPopupList = null;
            mSnippetSearchInput = null;
        }

        private View createSnippetsSheetContent() {
            LinearLayout root = new LinearLayout(mActivity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(8), dp(8), dp(8), dp(12));
            root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, getSnippetsSheetHeight()));

            root.addView(createSnippetsSheetHandle());
            root.addView(createSnippetsSheetTitleRow(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));

            LinearLayout searchRow = new LinearLayout(mActivity);
            searchRow.setOrientation(LinearLayout.HORIZONTAL);
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            searchRow.setPadding(dp(4), 0, dp(4), dp(8));

            mSnippetSearchInput = new EditText(mActivity);
            mSnippetSearchInput.setHint(R.string.termuxplus_snippet_search_hint);
            mSnippetSearchInput.setSingleLine(true);
            mSnippetSearchInput.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_sheet_input_bg));
            mSnippetSearchInput.setPadding(dp(12), 0, dp(12), 0);
            styleTextInput(mSnippetSearchInput);
            mSnippetSearchInput.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(Editable editable) {
                    renderSnippets(mSnippetSearchInput, mSnippetsPopupList);
                }
            });
            searchRow.addView(mSnippetSearchInput, new LinearLayout.LayoutParams(0, dp(36), 1));
            root.addView(searchRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            NestedScrollView scrollView = new NestedScrollView(mActivity);
            scrollView.setFillViewport(false);
            scrollView.setClipToPadding(false);
            scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

            mSnippetsPopupList = new LinearLayout(mActivity);
            mSnippetsPopupList.setOrientation(LinearLayout.VERTICAL);
            scrollView.addView(mSnippetsPopupList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return root;
        }

        private View createSnippetsSheetHandle() {
            View handle = new View(mActivity);
            handle.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.tp_sheet_handle));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(4));
            params.gravity = Gravity.CENTER_HORIZONTAL;
            params.topMargin = dp(4);
            params.bottomMargin = dp(10);
            handle.setLayoutParams(params);
            return handle;
        }

        private View createSnippetsSheetTitleRow() {
            LinearLayout titleRow = new LinearLayout(mActivity);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.setPadding(dp(12), 0, dp(4), dp(8));

            TextView title = new TextView(mActivity);
            title.setText(R.string.termuxplus_snippets_title);
            title.setTextColor(color(R.color.termuxplus_text_primary));
            title.setTextSize(17);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setSingleLine(true);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

            Button manageButton = createToolbarButton(mActivity.getString(R.string.termuxplus_manage));
            manageButton.setOnClickListener(v -> {
                dismissSnippetsPopup();
                TermuxPlusSnippetsActivity.start(mActivity);
            });
            titleRow.addView(manageButton, new LinearLayout.LayoutParams(dp(76), dp(34)));
            return titleRow;
        }

        private void configureSnippetsSheet() {
            if (mSnippetsSheetDialog == null) return;

            int sheetHeight = getSnippetsSheetHeight();
            FrameLayout bottomSheet = mSnippetsSheetDialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (bottomSheet != null) {
                ViewGroup.LayoutParams params = bottomSheet.getLayoutParams();
                params.height = sheetHeight;
                bottomSheet.setLayoutParams(params);
            }

            BottomSheetBehavior<FrameLayout> behavior = mSnippetsSheetDialog.getBehavior();
            if (behavior != null) {
                behavior.setPeekHeight(sheetHeight);
                behavior.setSkipCollapsed(true);
                behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
            }
        }

        private int getSnippetsSheetHeight() {
            int screenHeight = mActivity.getResources().getDisplayMetrics().heightPixels;
            int desiredHeight = Math.min(dp(420), Math.round(screenHeight * 0.62f));
            int minimumHeight = Math.min(dp(220), screenHeight);
            return Math.max(minimumHeight, desiredHeight);
        }

        private int dp(int value) {
            return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
        }

        private int color(int colorResId) {
            return ContextCompat.getColor(mActivity, colorResId);
        }

    }


    public static class OnPageChangeListener extends ViewPager.SimpleOnPageChangeListener {

        final TermuxActivity mActivity;
        final ViewPager mTerminalToolbarViewPager;

        public OnPageChangeListener(TermuxActivity activity, ViewPager viewPager) {
            this.mActivity = activity;
            this.mTerminalToolbarViewPager = viewPager;
        }

        @Override
        public void onPageSelected(int position) {
            TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
            if (extraKeys != null)
                extraKeys.setCurrentExtraKeysPage(position);

            View extraKeysView = mTerminalToolbarViewPager.findViewWithTag(getExtraKeysViewTag(position));
            if (extraKeysView instanceof ExtraKeysView)
                mActivity.setExtraKeysView((ExtraKeysView) extraKeysView);

            PagerAdapter adapter = mTerminalToolbarViewPager.getAdapter();
            if (position != 0 && adapter instanceof PageAdapter)
                ((PageAdapter) adapter).dismissSnippetsPopup();

            mActivity.updateTerminalToolbarHeight();
            if (!mActivity.getTerminalView().hasFocus())
                mActivity.getTerminalView().requestFocus();
        }

    }

    private static String getExtraKeysViewTag(int position) {
        return EXTRA_KEYS_VIEW_TAG_PREFIX + position;
    }

    private static String getExtraKeysPageTag(int position) {
        return EXTRA_KEYS_PAGE_TAG_PREFIX + position;
    }


    public static int getModeIndex(String modeName) {
        return MODE_KEYS;
    }

    public static String getModeName(int mode) {
        return MODE_NAME_KEYS;
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }

}
