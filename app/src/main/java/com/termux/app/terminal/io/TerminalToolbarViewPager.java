package com.termux.app.terminal.io;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.activities.TermuxPlusSnippetsActivity;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TerminalToolbarViewPager {

    public static final int MODE_KEYS = 0;

    public static final String MODE_NAME_KEYS = "keys";
    private static final String EXTRA_KEYS_VIEW_TAG_PREFIX = "termuxplus_extra_keys_page_";

    public static class PageAdapter extends PagerAdapter {

        final TermuxActivity mActivity;
        PopupWindow mSnippetsPopupWindow;
        View mSnippetsPopupContent;
        LinearLayout mSnippetsPopupList;
        EditText mSnippetSearchInput;
        View mSnippetsAnchor;

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
            View keyActions = layout.findViewById(R.id.terminal_toolbar_key_actions);
            Button snippetsButton = layout.findViewById(R.id.terminal_toolbar_snippets_button);
            ExtraKeysView extraKeysView = layout.findViewById(R.id.terminal_toolbar_extra_keys);
            mSnippetsAnchor = collection;
            setupSnippetsButton(keyActions, snippetsButton, position);
            extraKeysView.setExtraKeysViewClient(mActivity.getTermuxTerminalExtraKeys());
            extraKeysView.setButtonTextAllCaps(mActivity.getProperties().shouldExtraKeysTextBeAllCaps());
            extraKeysView.setButtonColors(
                color(R.color.termuxplus_text_primary),
                color(R.color.termuxplus_outline_selected),
                color(R.color.termuxplus_control),
                color(R.color.termuxplus_control_selected));
            extraKeysView.setTag(getExtraKeysViewTag(position));
            if (position == mActivity.getTerminalToolbarViewPager().getCurrentItem())
                mActivity.setExtraKeysView(extraKeysView);
            extraKeysView.setOnHorizontalSwipeListener(direction -> {
                TermuxTerminalExtraKeys extraKeys = mActivity.getTermuxTerminalExtraKeys();
                ViewPager toolbarPager = mActivity.getTerminalToolbarViewPager();
                if (extraKeys == null || toolbarPager == null) return;
                if (extraKeys.switchExtraKeysPage(direction))
                    toolbarPager.setCurrentItem(extraKeys.getCurrentExtraKeysPage(), true);
            });
            extraKeysView.reload(mActivity.getTermuxTerminalExtraKeys().getExtraKeysInfo(position),
                mActivity.getTerminalToolbarDefaultHeight());

            if (mActivity.getProperties().isUsingFullScreen() && mActivity.getProperties().isUsingFullScreenWorkAround())
                FullScreenWorkAround.apply(mActivity);

            return layout;
        }

        private void setupSnippetsButton(View keyActions, Button snippetsButton, int position) {
            if (keyActions == null || snippetsButton == null) return;

            keyActions.setVisibility(position == 0 ? View.VISIBLE : View.INVISIBLE);
            if (position != 0) return;

            snippetsButton.setOnClickListener(v -> showSnippetsPopup());
            final float[] downY = new float[1];
            final boolean[] manageOpened = new boolean[1];
            snippetsButton.setOnTouchListener((view, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downY[0] = event.getRawY();
                        manageOpened[0] = false;
                        view.setPressed(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!manageOpened[0] && downY[0] - event.getRawY() > dp(20)) {
                            manageOpened[0] = true;
                            view.setPressed(false);
                            dismissSnippetsPopup();
                            mActivity.openTermuxPlusSnippetsManager();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        view.setPressed(false);
                        if (!manageOpened[0]) view.performClick();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        view.setPressed(false);
                        return true;
                    default:
                        return true;
                }
            });
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
            row.setMinimumHeight(dp(40));
            row.setPadding(dp(8), dp(4), dp(4), dp(4));
            row.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.termuxplus_snippet_row_bg));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(dp(6), dp(2), dp(6), dp(4));
            row.setLayoutParams(rowParams);

            LinearLayout textColumn = new LinearLayout(mActivity);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            TextView title = createSnippetText(snippet.getTitle(), color(R.color.termuxplus_text_primary), 12, Typeface.BOLD);
            TextView description = createSnippetText(snippet.getDescription(), color(R.color.termuxplus_text_secondary), 10, Typeface.NORMAL);
            String metaText = buildSnippetMetaText(snippet);
            TextView meta = createSnippetText(metaText, color(R.color.termuxplus_text_muted), 10, Typeface.NORMAL);
            textColumn.addView(title);
            if (!TextUtils.isEmpty(snippet.getDescription())) textColumn.addView(description);
            if (!TextUtils.isEmpty(metaText)) textColumn.addView(meta);
            row.addView(textColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            Button insert = createToolbarButton(mActivity.getString(R.string.termuxplus_insert));
            insert.setOnClickListener(v -> sendSnippetText(snippet.getCommand(), false));
            row.addView(insert, createSnippetActionLayoutParams(58));

            Button run = createToolbarButton(mActivity.getString(R.string.termuxplus_run));
            run.setOnClickListener(v -> sendSnippetText(snippet.getCommand(), true));
            row.addView(run, createSnippetActionLayoutParams(50));

            row.setOnClickListener(v -> sendSnippetText(snippet.getCommand(), snippet.shouldRunByDefault()));
            return row;
        }

        private TextView createSnippetText(String text, int color, int textSize, int typefaceStyle) {
            TextView textView = new TextView(mActivity);
            textView.setText(text);
            textView.setTextColor(color);
            textView.setTextSize(textSize);
            textView.setIncludeFontPadding(false);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setTypeface(Typeface.MONOSPACE, typefaceStyle);
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
            styleActionButton(button);
            return button;
        }

        private void styleTextInput(EditText editText) {
            editText.setTextSize(12);
            editText.setIncludeFontPadding(false);
            editText.setTextColor(color(R.color.termuxplus_text_primary));
            editText.setHintTextColor(color(R.color.termuxplus_text_muted));
            editText.setTypeface(Typeface.MONOSPACE);
        }

        private void styleActionButton(Button button) {
            button.setAllCaps(false);
            button.setGravity(Gravity.CENTER);
            button.setIncludeFontPadding(false);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(dp(5), 0, dp(5), 0);
            button.setSingleLine(true);
            button.setTextSize(11);
            button.setTextColor(color(R.color.termuxplus_text_primary));
            button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            button.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.termuxplus_toolbar_action_button));
        }

        private LinearLayout.LayoutParams createSnippetActionLayoutParams(int widthDp) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(30));
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
                session.write(textToSend);
            } else {
                mActivity.getTermuxTerminalSessionClient().removeFinishedSession(session);
            }
        }

        public void showSnippetsPopup() {
            View anchor = mSnippetsAnchor != null ? mSnippetsAnchor : mActivity.getTerminalToolbarViewPager();
            if (anchor == null || !anchor.isAttachedToWindow()) return;

            int popupHeight = getSnippetsPopupHeight(anchor);
            int popupWidth = anchor.getWidth() > 0 ? anchor.getWidth() : mActivity.getResources().getDisplayMetrics().widthPixels;
            if (mSnippetsPopupWindow == null) {
                mSnippetsPopupContent = createSnippetsPopupContent();
                mSnippetsPopupWindow = new PopupWindow(mSnippetsPopupContent, popupWidth, popupHeight, true);
                mSnippetsPopupWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                mSnippetsPopupWindow.setOutsideTouchable(true);
                mSnippetsPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
                mSnippetsPopupWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
                    mSnippetsPopupWindow.setElevation(dp(8));
            }

            renderSnippets(mSnippetSearchInput, mSnippetsPopupList);
            if (mSnippetsPopupWindow.isShowing()) {
                mSnippetsPopupWindow.update(anchor, 0, -popupHeight - anchor.getHeight(), popupWidth, popupHeight);
            } else {
                mSnippetsPopupWindow.setWidth(popupWidth);
                mSnippetsPopupWindow.setHeight(popupHeight);
                mSnippetsPopupWindow.showAsDropDown(anchor, 0, -popupHeight - anchor.getHeight());
            }
        }

        public void dismissSnippetsPopup() {
            if (mSnippetsPopupWindow == null) return;
            mSnippetsPopupWindow.setContentView(null);
            mSnippetsPopupWindow.dismiss();
            mSnippetsPopupWindow = null;
            mSnippetsPopupContent = null;
            mSnippetsPopupList = null;
            mSnippetSearchInput = null;
        }

        private View createSnippetsPopupContent() {
            LinearLayout root = new LinearLayout(mActivity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(0, dp(6), 0, dp(8));
            root.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.termuxplus_snippets_popup_bg));

            LinearLayout searchRow = new LinearLayout(mActivity);
            searchRow.setOrientation(LinearLayout.HORIZONTAL);
            searchRow.setGravity(Gravity.CENTER_VERTICAL);
            searchRow.setPadding(dp(6), 0, dp(6), dp(6));

            mSnippetSearchInput = new EditText(mActivity);
            mSnippetSearchInput.setHint(R.string.termuxplus_snippet_search_hint);
            mSnippetSearchInput.setSingleLine(true);
            mSnippetSearchInput.setBackground(ContextCompat.getDrawable(mActivity, R.drawable.termuxplus_toolbar_input_bg));
            mSnippetSearchInput.setPadding(dp(10), 0, dp(10), 0);
            styleTextInput(mSnippetSearchInput);
            mSnippetSearchInput.addTextChangedListener(new SimpleTextWatcher() {
                @Override
                public void afterTextChanged(Editable editable) {
                    renderSnippets(mSnippetSearchInput, mSnippetsPopupList);
                }
            });
            searchRow.addView(mSnippetSearchInput, new LinearLayout.LayoutParams(0, dp(36), 1));

            Button manageButton = createToolbarButton(mActivity.getString(R.string.termuxplus_manage));
            manageButton.setOnClickListener(v -> {
                dismissSnippetsPopup();
                TermuxPlusSnippetsActivity.start(mActivity);
            });
            LinearLayout.LayoutParams manageParams = new LinearLayout.LayoutParams(dp(72), dp(36));
            manageParams.setMargins(dp(4), 0, 0, 0);
            searchRow.addView(manageButton, manageParams);
            root.addView(searchRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            ScrollView scrollView = new ScrollView(mActivity);
            scrollView.setFillViewport(false);
            scrollView.setClipToPadding(false);
            scrollView.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

            mSnippetsPopupList = new LinearLayout(mActivity);
            mSnippetsPopupList.setOrientation(LinearLayout.VERTICAL);
            scrollView.addView(mSnippetsPopupList, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
            root.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            return root;
        }

        private int getSnippetsPopupHeight(View anchor) {
            int screenHeight = mActivity.getResources().getDisplayMetrics().heightPixels;
            int availableHeight = anchor.getTop() - dp(8);
            if (availableHeight <= 0) availableHeight = Math.round(screenHeight * 0.45f);

            int desiredHeight = Math.min(dp(280), Math.round(screenHeight * 0.42f));
            int minimumHeight = Math.min(dp(132), availableHeight);
            return Math.max(minimumHeight, Math.min(desiredHeight, availableHeight));
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
            mActivity.getTerminalView().requestFocus();
        }

    }

    private static String getExtraKeysViewTag(int position) {
        return EXTRA_KEYS_VIEW_TAG_PREFIX + position;
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
