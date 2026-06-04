package com.termux.app.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.termux.R;
import com.termux.app.terminal.io.TermuxPlusSnippet;
import com.termux.app.terminal.io.TermuxPlusSnippetRepository;
import com.termux.app.ui.TpChrome;
import com.termux.app.ui.TpIconView;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class TermuxPlusSnippetsActivity extends AppCompatActivity {

    private LinearLayout mSnippetList;
    private EditText mSearchInput;
    private BottomSheetDialog mDialog;

    public static void start(Context context) {
        if (context == null) return;
        if (context instanceof AppCompatActivity) {
            TermuxPlusSnippetsSheet.show((AppCompatActivity) context);
            return;
        }
        context.startActivity(new Intent(context, TermuxPlusSnippetsActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
        configureSheetWindow();
        showSnippetList();
    }

    @Override
    protected void onDestroy() {
        if (mDialog != null) {
            mDialog.setOnDismissListener(null);
            if (mDialog.isShowing()) mDialog.dismiss();
            mDialog = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        Object tag = getWindow().getDecorView().getTag();
        if ("editor".equals(tag)) {
            showSnippetList();
            return;
        }
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
            return;
        }
        super.onBackPressed();
    }

    private void showSnippetList() {
        getWindow().getDecorView().setTag("list");

        LinearLayout root = createRootLayout();
        root.addView(createTopBar(getString(R.string.termuxplus_snippets_title),
            getString(R.string.termuxplus_back), v -> finish(),
            getString(R.string.termuxplus_add), v -> showSnippetEditor(null)));

        mSearchInput = new EditText(this);
        mSearchInput.setHint(R.string.termuxplus_snippet_search_hint);
        mSearchInput.setSingleLine(true);
        mSearchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        styleTextInput(mSearchInput);
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        searchParams.setMargins(dp(8), dp(6), dp(8), dp(6));
        root.addView(mSearchInput, searchParams);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(0, 0, 0, dp(8));
        scrollView.setBackgroundColor(Color.TRANSPARENT);

        mSnippetList = new LinearLayout(this);
        mSnippetList.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(mSnippetList, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        mSearchInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                renderSnippetList();
            }
        });

        setSheetContent(root);
        renderSnippetList();
    }

    private void renderSnippetList() {
        if (mSnippetList == null) return;
        mSnippetList.removeAllViews();

        String query = mSearchInput == null ? "" : mSearchInput.getText().toString();
        int count = 0;
        for (TermuxPlusSnippet snippet : TermuxPlusSnippetRepository.getSnippets(this)) {
            if (!snippet.matches(query)) continue;

            mSnippetList.addView(createSnippetRow(snippet));
            count++;
        }

        if (count == 0)
            mSnippetList.addView(createMessageView(getString(R.string.termuxplus_no_snippets)));
    }

    private View createSnippetRow(TermuxPlusSnippet snippet) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(76));
        row.setPadding(dp(10), dp(9), dp(10), dp(9));
        row.setBackground(ContextCompat.getDrawable(this, R.drawable.tp_overview_card_bg));
        row.setOnClickListener(v -> showSnippetEditor(snippet));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(dp(4), 0, dp(4), dp(8));
        row.setLayoutParams(rowParams);

        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(ContextCompat.getDrawable(this, R.drawable.tp_icon_tile_bg));
        TpIconView icon = new TpIconView(this, TpIconView.BRACES);
        icon.setColor(TpChrome.ACCENT);
        FrameLayout.LayoutParams iconParams = new FrameLayout.LayoutParams(dp(28), dp(28));
        iconParams.gravity = Gravity.CENTER;
        tile.addView(icon, iconParams);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(dp(40), dp(40));
        tileParams.setMarginEnd(dp(12));
        row.addView(tile, tileParams);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.addView(createText(snippet.getTitle(), R.color.termuxplus_text_primary, 14, Typeface.BOLD, true));
        textColumn.addView(createText(snippet.getCommand(), R.color.termuxplus_text_secondary, 11, Typeface.NORMAL, true));
        if (!TextUtils.isEmpty(snippet.getDescription()))
            textColumn.addView(createText(snippet.getDescription(), R.color.termuxplus_text_secondary, 11, Typeface.NORMAL, true));
        String metaText = buildSnippetMetaText(snippet);
        if (!TextUtils.isEmpty(metaText))
            textColumn.addView(createText(metaText, R.color.termuxplus_text_muted, 10, Typeface.NORMAL, true));
        row.addView(textColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TpIconView edit = new TpIconView(this, TpIconView.EDIT);
        edit.setColor(TpChrome.TEXT_DIM);
        edit.setContentDescription(getString(R.string.termuxplus_edit));
        edit.setOnClickListener(v -> showSnippetEditor(snippet));
        row.addView(edit, snippetActionIconParams());

        TpIconView delete = new TpIconView(this, TpIconView.TRASH);
        delete.setColor(TpChrome.ERROR);
        delete.setContentDescription(getString(R.string.termuxplus_delete));
        delete.setOnClickListener(v -> showDeleteConfirmation(snippet, true));
        row.addView(delete, snippetActionIconParams());

        return row;
    }

    private LinearLayout.LayoutParams snippetActionIconParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(34), dp(34));
        params.setMargins(dp(2), 0, 0, 0);
        return params;
    }

    private void showSnippetEditor(@Nullable TermuxPlusSnippet snippet) {
        getWindow().getDecorView().setTag("editor");

        LinearLayout root = createRootLayout();
        root.addView(createTopBar(snippet == null ? getString(R.string.termuxplus_new_snippet_title) : getString(R.string.termuxplus_edit_snippet_title),
            getString(R.string.termuxplus_back), v -> showSnippetList(),
            getString(R.string.termuxplus_save), null));

        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setPadding(dp(8), dp(8), dp(8), dp(12));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);

        EditText title = addFormField(form, R.string.termuxplus_snippet_field_title,
            snippet == null ? "" : snippet.getTitle(), false);
        EditText command = addFormField(form, R.string.termuxplus_snippet_field_command,
            snippet == null ? "" : snippet.getCommand(), true);
        EditText description = addFormField(form, R.string.termuxplus_snippet_field_description,
            snippet == null ? "" : snippet.getDescription(), true);
        EditText category = addFormField(form, R.string.termuxplus_snippet_field_category,
            snippet == null ? "" : snippet.getCategory(), false);
        EditText tags = addFormField(form, R.string.termuxplus_snippet_field_tags,
            snippet == null ? "" : formatTags(snippet.getTags()), false);

        CheckBox runByDefault = new CheckBox(this);
        runByDefault.setText(R.string.termuxplus_snippet_run_by_default);
        runByDefault.setTextColor(color(R.color.termuxplus_text_secondary));
        runByDefault.setTextSize(12);
        runByDefault.setTypeface(Typeface.DEFAULT);
        runByDefault.setIncludeFontPadding(false);
        runByDefault.setChecked(snippet != null && snippet.shouldRunByDefault());
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        checkParams.setMargins(0, dp(2), 0, dp(8));
        form.addView(runByDefault, checkParams);

        if (snippet != null) {
            Button delete = createActionButton(getString(R.string.termuxplus_delete), false, true);
            delete.setOnClickListener(v -> showDeleteConfirmation(snippet, false));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
            deleteParams.setMargins(0, dp(8), 0, 0);
            form.addView(delete, deleteParams);
        }

        scrollView.addView(form, new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setSheetContent(root);

        Button save = root.findViewWithTag("top_bar_action");
        save.setOnClickListener(v -> saveSnippet(snippet, title, command, description, category, tags, runByDefault));
    }

    private void saveSnippet(@Nullable TermuxPlusSnippet originalSnippet, EditText title, EditText command,
                             EditText description, EditText category, EditText tags, CheckBox runByDefault) {
        String titleText = title.getText().toString().trim();
        String commandText = command.getText().toString().trim();
        if (TextUtils.isEmpty(titleText) || TextUtils.isEmpty(commandText)) {
            showToast(getString(R.string.termuxplus_snippet_required_fields));
            return;
        }

        String id = originalSnippet == null ? createSnippetId(titleText) : originalSnippet.getId();
        TermuxPlusSnippet snippet = new TermuxPlusSnippet(id, titleText, description.getText().toString().trim(),
            commandText, category.getText().toString().trim(), parseTags(tags.getText().toString()),
            runByDefault.isChecked() ? "run" : "insert");

        if (TermuxPlusSnippetRepository.saveUserSnippet(this, snippet)) {
            showToast(getString(R.string.termuxplus_snippet_saved));
            showSnippetList();
        } else {
            showToast(getString(R.string.termuxplus_snippet_save_failed));
        }
    }

    private void showDeleteConfirmation(TermuxPlusSnippet snippet, boolean stayOnList) {
        if (snippet == null) return;

        new AlertDialog.Builder(this)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(getString(R.string.termuxplus_snippet_delete_confirm, snippet.getTitle()))
            .setPositiveButton(android.R.string.yes, (dialog, which) -> {
                dialog.dismiss();
                if (TermuxPlusSnippetRepository.deleteSnippet(this, snippet.getId())) {
                    showToast(getString(R.string.termuxplus_snippet_deleted));
                    showSnippetList();
                } else {
                    showToast(getString(R.string.termuxplus_snippet_delete_failed));
                    if (!stayOnList) showSnippetEditor(snippet);
                }
            })
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

    private LinearLayout createRootLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(ContextCompat.getDrawable(this, R.drawable.tp_sheet_bg));
        root.setPadding(dp(8), dp(8), dp(8), dp(12));
        root.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, getSheetHeight()));
        root.addView(createSheetHandle());
        return root;
    }

    private View createSheetHandle() {
        View handle = new View(this);
        handle.setBackground(ContextCompat.getDrawable(this, R.drawable.tp_sheet_handle));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(36), dp(4));
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(4);
        params.bottomMargin = dp(10);
        handle.setLayoutParams(params);
        return handle;
    }

    private View createTopBar(String titleText, String backText, View.OnClickListener backListener,
                              String actionText, @Nullable View.OnClickListener actionListener) {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(4), 0, dp(4), dp(8));
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));

        Button back = createActionButton(backText, false, false);
        back.setOnClickListener(backListener);
        topBar.addView(back, new LinearLayout.LayoutParams(dp(74), dp(34)));

        TextView title = createText(titleText, R.color.termuxplus_text_primary, 14, Typeface.BOLD, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        titleParams.setMargins(dp(8), 0, dp(8), 0);
        topBar.addView(title, titleParams);

        Button action = createActionButton(actionText, true, false);
        action.setTag("top_bar_action");
        if (actionListener != null)
            action.setOnClickListener(actionListener);
        topBar.addView(action, new LinearLayout.LayoutParams(dp(74), dp(34)));

        return topBar;
    }

    private EditText addFormField(LinearLayout form, int labelResId, String value, boolean multiLine) {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);

        TextView label = createText(getString(labelResId), R.color.termuxplus_text_muted, 10, Typeface.BOLD, true);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(18));
        wrapper.addView(label, labelParams);

        EditText field = new EditText(this);
        field.setText(value);
        styleTextInput(field);
        if (multiLine) {
            field.setSingleLine(false);
            field.setMinLines(labelResId == R.string.termuxplus_snippet_field_command ? 3 : 2);
            field.setGravity(Gravity.TOP | Gravity.START);
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        } else {
            field.setSingleLine(true);
            field.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        wrapper.addView(field, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, multiLine ? LinearLayout.LayoutParams.WRAP_CONTENT : dp(36)));

        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wrapperParams.setMargins(0, 0, 0, dp(10));
        wrapper.setLayoutParams(wrapperParams);
        form.addView(wrapper);
        return field;
    }

    private Button createActionButton(String text) {
        return createActionButton(text, false, false);
    }

    private Button createActionButton(String text, boolean primary, boolean danger) {
        Button button = new Button(this);
        button.setText(text);
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
        button.setTextColor(danger ? TpChrome.ERROR :
            (primary ? TpChrome.ACCENT : TpChrome.TEXT_DIM));
        button.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        button.setBackground(ContextCompat.getDrawable(this,
            primary ? R.drawable.tp_accent_button_bg : R.drawable.tp_chip_bg));
        return button;
    }

    private TextView createText(String text, int colorResId, int textSize, int typefaceStyle, boolean singleLine) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(color(colorResId));
        textView.setTextSize(textSize);
        textView.setIncludeFontPadding(false);
        textView.setTypeface(Typeface.DEFAULT, typefaceStyle);
        textView.setSingleLine(singleLine);
        if (singleLine)
            textView.setEllipsize(TextUtils.TruncateAt.END);
        return textView;
    }

    private TextView createMessageView(String text) {
        TextView textView = createText(text, R.color.termuxplus_text_secondary, 12, Typeface.NORMAL, true);
        textView.setPadding(dp(12), dp(8), dp(12), dp(8));
        return textView;
    }

    private void styleTextInput(EditText editText) {
        editText.setTextSize(12);
        editText.setIncludeFontPadding(false);
        editText.setTextColor(color(R.color.termuxplus_text_primary));
        editText.setHintTextColor(color(R.color.termuxplus_text_muted));
        editText.setTypeface(Typeface.MONOSPACE);
        editText.setBackground(ContextCompat.getDrawable(this, R.drawable.tp_sheet_input_bg));
        editText.setPadding(dp(12), 0, dp(12), 0);
    }

    private void setSheetContent(View sheet) {
        ensureDialog();
        mDialog.setContentView(sheet);
        if (!mDialog.isShowing())
            mDialog.show();
        configureBottomSheet();
    }

    private void configureSheetWindow() {
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        getWindow().setDimAmount(0f);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        applyDarkSystemBars(getWindow());
    }

    private void ensureDialog() {
        if (mDialog != null) return;

        mDialog = new BottomSheetDialog(this, R.style.Theme_TermuxPlus_BottomSheet);
        mDialog.setOnDismissListener(dialog -> finish());
        mDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP &&
                "editor".equals(getWindow().getDecorView().getTag())) {
                showSnippetList();
                return true;
            }
            return false;
        });
        mDialog.setOnShowListener(dialog -> configureBottomSheet());
    }

    private void configureBottomSheet() {
        if (mDialog == null) return;

        int sheetHeight = getSheetHeight();
        FrameLayout bottomSheet = mDialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            ViewGroup.LayoutParams params = bottomSheet.getLayoutParams();
            params.height = sheetHeight;
            bottomSheet.setLayoutParams(params);
        }

        BottomSheetBehavior<FrameLayout> behavior = mDialog.getBehavior();
        if (behavior != null) {
            behavior.setPeekHeight(sheetHeight);
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }

        applyDarkSystemBars(mDialog.getWindow());
    }

    private void applyDarkSystemBars(@Nullable Window window) {
        if (window == null) return;

        window.setNavigationBarColor(color(R.color.termuxplus_sheet_bg));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int flags = window.getDecorView().getSystemUiVisibility();
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            window.setNavigationBarContrastEnforced(false);
    }

    private int getSheetHeight() {
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int desiredHeight = Math.round(screenHeight * 0.88f);
        int minimumHeight = Math.min(dp(520), screenHeight);
        return Math.max(minimumHeight, desiredHeight);
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

    private String createSnippetId(String title) {
        String base = title.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("^-+|-+$", "");
        if (TextUtils.isEmpty(base))
            base = "snippet";

        Set<String> existingIds = new HashSet<>();
        for (TermuxPlusSnippet snippet : TermuxPlusSnippetRepository.getSnippets(this))
            existingIds.add(snippet.getId());

        String id = base;
        int suffix = 2;
        while (existingIds.contains(id)) {
            id = base + "-" + suffix;
            suffix++;
        }
        return id;
    }

    private List<String> parseTags(String text) {
        ArrayList<String> tags = new ArrayList<>();
        if (TextUtils.isEmpty(text)) return tags;

        String[] parts = text.split(",");
        for (String part : parts) {
            String tag = part.trim();
            if (!TextUtils.isEmpty(tag))
                tags.add(tag);
        }
        return tags;
    }

    private String formatTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "";

        StringBuilder builder = new StringBuilder();
        for (String tag : tags) {
            if (builder.length() > 0) builder.append(", ");
            builder.append(tag);
        }
        return builder.toString();
    }

    private void showToast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int color(int colorResId) {
        return ContextCompat.getColor(this, colorResId);
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }

}
