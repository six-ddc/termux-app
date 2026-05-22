package com.termux.app.activities;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.terminal.io.TermuxPlusSnippet;
import com.termux.app.terminal.io.TermuxPlusSnippetRepository;
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

    public static void start(Context context) {
        if (context == null) return;
        context.startActivity(new Intent(context, TermuxPlusSnippetsActivity.class));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
        showSnippetList();
    }

    @Override
    public void onBackPressed() {
        Object tag = getWindow().getDecorView().getTag();
        if ("editor".equals(tag)) {
            showSnippetList();
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
        scrollView.setBackgroundColor(color(R.color.termuxplus_surface));

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

        setContentView(root);
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
        row.setMinimumHeight(dp(58));
        row.setPadding(dp(10), dp(7), dp(6), dp(7));
        row.setBackground(ContextCompat.getDrawable(this, R.drawable.termuxplus_snippet_row_bg));
        row.setOnClickListener(v -> showSnippetEditor(snippet));
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(dp(8), dp(2), dp(8), dp(6));
        row.setLayoutParams(rowParams);

        LinearLayout textColumn = new LinearLayout(this);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.addView(createText(snippet.getTitle(), R.color.termuxplus_text_primary, 13, Typeface.BOLD, true));
        textColumn.addView(createText(snippet.getCommand(), R.color.termuxplus_text_secondary, 11, Typeface.NORMAL, true));
        if (!TextUtils.isEmpty(snippet.getDescription()))
            textColumn.addView(createText(snippet.getDescription(), R.color.termuxplus_text_secondary, 10, Typeface.NORMAL, true));
        String metaText = buildSnippetMetaText(snippet);
        if (!TextUtils.isEmpty(metaText))
            textColumn.addView(createText(metaText, R.color.termuxplus_text_muted, 10, Typeface.NORMAL, true));
        row.addView(textColumn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button edit = createActionButton(getString(R.string.termuxplus_edit));
        edit.setOnClickListener(v -> showSnippetEditor(snippet));
        row.addView(edit, createActionLayoutParams(54));

        Button delete = createActionButton(getString(R.string.termuxplus_delete));
        delete.setOnClickListener(v -> showDeleteConfirmation(snippet, true));
        row.addView(delete, createActionLayoutParams(68));

        return row;
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
        runByDefault.setTypeface(Typeface.MONOSPACE);
        runByDefault.setIncludeFontPadding(false);
        runByDefault.setChecked(snippet != null && snippet.shouldRunByDefault());
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        checkParams.setMargins(0, dp(2), 0, dp(8));
        form.addView(runByDefault, checkParams);

        if (snippet != null) {
            Button delete = createActionButton(getString(R.string.termuxplus_delete));
            delete.setTextColor(color(R.color.termuxplus_text_error));
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

        setContentView(root);

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
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(color(R.color.termuxplus_surface));
        root.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    private View createTopBar(String titleText, String backText, View.OnClickListener backListener,
                              String actionText, @Nullable View.OnClickListener actionListener) {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(6), dp(5), dp(6), dp(5));
        topBar.setBackgroundColor(color(R.color.termuxplus_surface));
        topBar.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        Button back = createActionButton(backText);
        back.setOnClickListener(backListener);
        topBar.addView(back, new LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.MATCH_PARENT));

        TextView title = createText(titleText, R.color.termuxplus_text_primary, 14, Typeface.BOLD, true);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        titleParams.setMargins(dp(8), 0, dp(8), 0);
        topBar.addView(title, titleParams);

        Button action = createActionButton(actionText);
        action.setTag("top_bar_action");
        if (actionListener != null)
            action.setOnClickListener(actionListener);
        topBar.addView(action, new LinearLayout.LayoutParams(dp(62), LinearLayout.LayoutParams.MATCH_PARENT));

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
        Button button = new Button(this);
        button.setText(text);
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
        button.setBackground(ContextCompat.getDrawable(this, R.drawable.termuxplus_toolbar_action_button));
        return button;
    }

    private TextView createText(String text, int colorResId, int textSize, int typefaceStyle, boolean singleLine) {
        TextView textView = new TextView(this);
        textView.setText(text);
        textView.setTextColor(color(colorResId));
        textView.setTextSize(textSize);
        textView.setIncludeFontPadding(false);
        textView.setTypeface(Typeface.MONOSPACE, typefaceStyle);
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
        editText.setBackground(ContextCompat.getDrawable(this, R.drawable.termuxplus_toolbar_input_bg));
        editText.setPadding(dp(10), 0, dp(10), 0);
    }

    private LinearLayout.LayoutParams createActionLayoutParams(int widthDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(widthDp), dp(32));
        params.setMargins(dp(5), 0, 0, 0);
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
