package com.termux.app.terminal.io;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class TermuxPlusSnippet {

    private final String mId;
    private final String mTitle;
    private final String mDescription;
    private final String mCommand;
    private final String mCategory;
    private final List<String> mTags;
    private final String mMode;
    private final String mSearchText;

    public TermuxPlusSnippet(String id, String title, String description, String command,
                             String category, List<String> tags, String mode) {
        mId = id;
        mTitle = title;
        mDescription = description;
        mCommand = command;
        mCategory = category;
        mTags = tags == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(tags));
        mMode = TextUtils.isEmpty(mode) ? "insert" : mode;
        mSearchText = buildSearchText().toLowerCase(Locale.ROOT);
    }

    public static TermuxPlusSnippet fromJson(JSONObject object) {
        if (object == null) return null;

        String id = object.optString("id", "");
        String title = object.optString("title", "");
        String command = object.optString("command", "");
        if (TextUtils.isEmpty(id) || TextUtils.isEmpty(title) || TextUtils.isEmpty(command))
            return null;

        List<String> tags = new ArrayList<>();
        JSONArray tagArray = object.optJSONArray("tags");
        if (tagArray != null) {
            for (int i = 0; i < tagArray.length(); i++) {
                String tag = tagArray.optString(i, "");
                if (!TextUtils.isEmpty(tag))
                    tags.add(tag);
            }
        }

        return new TermuxPlusSnippet(id, title, object.optString("description", ""), command,
            object.optString("category", ""), tags, object.optString("mode", "insert"));
    }

    public JSONObject toJson() throws Exception {
        JSONObject object = new JSONObject();
        object.put("id", mId);
        object.put("title", mTitle);
        object.put("description", mDescription);
        object.put("command", mCommand);
        object.put("category", mCategory);
        object.put("mode", mMode);

        JSONArray tags = new JSONArray();
        for (String tag : mTags)
            tags.put(tag);
        object.put("tags", tags);
        return object;
    }

    public String getId() {
        return mId;
    }

    public String getTitle() {
        return mTitle;
    }

    public String getDescription() {
        return mDescription;
    }

    public String getCommand() {
        return mCommand;
    }

    public String getCategory() {
        return mCategory;
    }

    public List<String> getTags() {
        return mTags;
    }

    public String getMode() {
        return mMode;
    }

    public boolean shouldRunByDefault() {
        return "run".equalsIgnoreCase(mMode);
    }

    public boolean matches(String query) {
        if (TextUtils.isEmpty(query)) return true;
        return mSearchText.contains(query.toLowerCase(Locale.ROOT));
    }

    private String buildSearchText() {
        StringBuilder builder = new StringBuilder();
        builder.append(mId).append(' ')
            .append(mTitle).append(' ')
            .append(mDescription).append(' ')
            .append(mCommand).append(' ')
            .append(mCategory);
        for (String tag : mTags)
            builder.append(' ').append(tag);
        return builder.toString();
    }
}
