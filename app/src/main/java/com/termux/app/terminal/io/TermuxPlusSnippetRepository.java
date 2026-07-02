package com.termux.app.terminal.io;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TermuxPlusSnippetRepository {

    private static final String LOG_TAG = "TermuxPlusSnippetRepository";
    private static final String ASSET_SNIPPETS_PATH = "termuxplus/snippets.json";

    private static List<TermuxPlusSnippet> sCachedSnippets;

    private TermuxPlusSnippetRepository() {}

    public static synchronized List<TermuxPlusSnippet> getSnippets(Context context) {
        if (sCachedSnippets == null)
            sCachedSnippets = loadSnippets(context);

        return new ArrayList<>(sCachedSnippets);
    }

    public static synchronized void clearCache() {
        sCachedSnippets = null;
    }

    public static synchronized boolean saveUserSnippet(Context context, TermuxPlusSnippet snippet) {
        if (snippet == null) return false;

        try {
            LinkedHashMap<String, JSONObject> userSnippetObjects = loadUserSnippetObjects();
            userSnippetObjects.put(snippet.getId(), snippet.toJson());
            writeUserSnippetObjects(userSnippetObjects);
            clearCache();
            return true;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to save user snippet: " + e.getMessage());
            return false;
        }
    }

    public static synchronized boolean deleteSnippet(Context context, String snippetId) {
        if (snippetId == null || snippetId.isEmpty()) return false;

        try {
            LinkedHashMap<String, JSONObject> userSnippetObjects = loadUserSnippetObjects();
            JSONObject deletedSnippet = new JSONObject();
            deletedSnippet.put("id", snippetId);
            deletedSnippet.put("deleted", true);
            userSnippetObjects.put(snippetId, deletedSnippet);
            writeUserSnippetObjects(userSnippetObjects);
            clearCache();
            return true;
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "Failed to delete snippet \"" + snippetId + "\": " + e.getMessage());
            return false;
        }
    }

    private static List<TermuxPlusSnippet> loadSnippets(Context context) {
        LinkedHashMap<String, TermuxPlusSnippet> snippets = new LinkedHashMap<>();

        loadAssetSnippets(context, snippets);
        loadFileSnippets(new File(TermuxConstants.TERMUX_ETC_PREFIX_DIR_PATH + "/termuxplus/snippets.json"), snippets);
        loadFileSnippets(getUserSnippetsFile(), snippets);

        return new ArrayList<>(snippets.values());
    }

    private static void loadAssetSnippets(Context context, Map<String, TermuxPlusSnippet> snippets) {
        if (context == null) return;

        try (InputStream inputStream = context.getAssets().open(ASSET_SNIPPETS_PATH)) {
            loadJson(readText(inputStream), snippets);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to load built-in snippets: " + e.getMessage());
        }
    }

    private static void loadFileSnippets(File file, Map<String, TermuxPlusSnippet> snippets) {
        if (file == null || !file.isFile()) return;

        try (InputStream inputStream = new FileInputStream(file)) {
            loadJson(readText(inputStream), snippets);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to load snippets from \"" + file + "\": " + e.getMessage());
        }
    }

    private static void loadJson(String json, Map<String, TermuxPlusSnippet> snippets) throws Exception {
        if (json == null || snippets == null) return;

        JSONArray array;
        String trimmed = json.trim();
        if (trimmed.startsWith("{")) {
            JSONObject root = new JSONObject(trimmed);
            array = root.optJSONArray("snippets");
        } else {
            array = new JSONArray(trimmed);
        }
        if (array == null) return;

        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;

            String id = object.optString("id", "");
            if (object.optBoolean("deleted", false)) {
                if (!id.isEmpty())
                    snippets.remove(id);
                continue;
            }

            TermuxPlusSnippet snippet = TermuxPlusSnippet.fromJson(object);
            if (snippet != null)
                snippets.put(snippet.getId(), snippet);
        }
    }

    private static LinkedHashMap<String, JSONObject> loadUserSnippetObjects() throws Exception {
        LinkedHashMap<String, JSONObject> userSnippetObjects = new LinkedHashMap<>();
        File file = getUserSnippetsFile();
        if (!file.isFile()) return userSnippetObjects;

        JSONArray array;
        try (InputStream inputStream = new FileInputStream(file)) {
            array = parseSnippetArray(readText(inputStream));
        }
        if (array == null) return userSnippetObjects;

        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;

            String id = object.optString("id", "");
            if (!id.isEmpty())
                userSnippetObjects.put(id, object);
        }
        return userSnippetObjects;
    }

    private static JSONArray parseSnippetArray(String json) throws Exception {
        if (json == null) return null;

        String trimmed = json.trim();
        if (trimmed.isEmpty()) return null;
        if (trimmed.startsWith("{")) {
            JSONObject root = new JSONObject(trimmed);
            return root.optJSONArray("snippets");
        }
        return new JSONArray(trimmed);
    }

    private static void writeUserSnippetObjects(LinkedHashMap<String, JSONObject> userSnippetObjects) throws Exception {
        File file = getUserSnippetsFile();
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Failed to create \"" + parent + "\"");

        JSONArray array = new JSONArray();
        for (JSONObject object : userSnippetObjects.values())
            array.put(object);

        JSONObject root = new JSONObject();
        root.put("snippets", array);
        writeText(file, root.toString(2));
    }

    private static File getUserSnippetsFile() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.termuxplus/snippets.json");
    }

    private static String readText(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) >= 0)
            outputStream.write(buffer, 0, bytesRead);
        return outputStream.toString(StandardCharsets.UTF_8.name());
    }

    private static void writeText(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Failed to create \"" + parent + "\"");

        // Write to a sibling temp file on the same filesystem then atomically rename
        // over the target, so a mid-write failure (ENOSPC, process kill) can never
        // leave snippets.json truncated/corrupt. Mirrors TermuxPlusHomeInstaller.copyAssetFile.
        File tempFile = new File(parent, "." + file.getName() + ".tmp");
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tempFile, false), StandardCharsets.UTF_8)) {
                writer.write(text);
                writer.write('\n');
            }
            Os.rename(tempFile.getAbsolutePath(), file.getAbsolutePath());
        } catch (ErrnoException e) {
            tempFile.delete();
            throw new IOException("Failed to rename \"" + tempFile + "\" to \"" + file + "\": " + e.getMessage(), e);
        } catch (IOException e) {
            tempFile.delete();
            throw e;
        }
    }
}
