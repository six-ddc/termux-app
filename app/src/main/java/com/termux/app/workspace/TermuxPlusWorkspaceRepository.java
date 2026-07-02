package com.termux.app.workspace;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class TermuxPlusWorkspaceRepository {

    private static final String LOG_TAG = "TermuxPlusWorkspaceRepository";

    private static final int MAX_AGENT_FILES_TO_SCAN = 240;
    private static final int MAX_CODEX_LINES_TO_SCAN = 120;
    private static final int MAX_CLAUDE_LINES_TO_SCAN = 120;
    private static final int MAX_WORKSPACES = 24;
    private static final int MAX_SESSIONS_PER_WORKSPACE = 4;

    private TermuxPlusWorkspaceRepository() {}

    public static List<TermuxPlusWorkspace> loadWorkspaces() {
        Map<String, TermuxPlusWorkspace> workspaceMap = new HashMap<>();
        File homeDir = TermuxConstants.TERMUX_HOME_DIR;

        scanCodexSessions(homeDir, workspaceMap);
        scanClaudeSessions(homeDir, workspaceMap);

        List<TermuxPlusWorkspace> workspaces = new ArrayList<>(workspaceMap.values());
        for (TermuxPlusWorkspace workspace : workspaces) {
            workspace.sortSessions();
            workspace.trimSessions(MAX_SESSIONS_PER_WORKSPACE);
        }

        Collections.sort(workspaces, TermuxPlusWorkspace.UPDATED_DESC);
        while (workspaces.size() > MAX_WORKSPACES)
            workspaces.remove(workspaces.size() - 1);
        return workspaces;
    }

    private static void scanCodexSessions(File homeDir, Map<String, TermuxPlusWorkspace> workspaceMap) {
        File codexDir = new File(homeDir, ".codex");
        File sessionsDir = new File(codexDir, "sessions");
        if (!sessionsDir.isDirectory()) return;

        Map<String, CodexIndexEntry> index = loadCodexSessionIndex(new File(codexDir, "session_index.jsonl"));
        List<File> files = collectJsonlFiles(sessionsDir, true);
        trimFileList(files, MAX_AGENT_FILES_TO_SCAN);

        for (File file : files) {
            try {
                CodexSessionMeta meta = readCodexSessionMeta(file, index);
                if (meta == null || meta.cwd == null || meta.cwd.isEmpty()) continue;
                if (!isUsableWorkspace(meta.cwd)) continue;

                TermuxPlusWorkspace workspace = getOrCreateWorkspace(workspaceMap, meta.cwd);
                workspace.addSession(new TermuxPlusAgentSession(TermuxPlusAgentSession.AGENT_CODEX,
                    meta.sessionId, cleanTitle(meta.title, "Codex session"), meta.updatedAt));
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed to read Codex session \"" + file + "\": " + e.getMessage());
            }
        }
    }

    private static Map<String, CodexIndexEntry> loadCodexSessionIndex(File file) {
        Map<String, CodexIndexEntry> index = new HashMap<>();
        if (!file.isFile()) return index;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                JSONObject object = new JSONObject(line);
                String id = object.optString("id", null);
                if (id == null || id.isEmpty()) continue;
                String title = firstNonEmpty(object, "thread_name", "title", "name");
                long updatedAt = parseIsoMillis(object.optString("updated_at", null));
                index.put(id, new CodexIndexEntry(title, updatedAt));
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to read Codex session index: " + e.getMessage());
        }

        return index;
    }

    private static CodexSessionMeta readCodexSessionMeta(File file, Map<String, CodexIndexEntry> index) throws Exception {
        String sessionId = null;
        String cwd = null;
        String title = null;
        long updatedAt = file.lastModified();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount++ < MAX_CODEX_LINES_TO_SCAN) {
                if (line.trim().isEmpty()) continue;
                JSONObject object = new JSONObject(line);

                long lineUpdatedAt = parseIsoMillis(object.optString("timestamp", null));
                if (lineUpdatedAt > 0)
                    updatedAt = Math.max(updatedAt, lineUpdatedAt);

                if ("session_meta".equals(object.optString("type"))) {
                    JSONObject payload = object.optJSONObject("payload");
                    if (payload != null) {
                        sessionId = payload.optString("id", sessionId);
                        cwd = payload.optString("cwd", cwd);
                        long metaUpdatedAt = parseIsoMillis(payload.optString("timestamp", null));
                        if (metaUpdatedAt > 0)
                            updatedAt = Math.max(updatedAt, metaUpdatedAt);

                        CodexIndexEntry entry = sessionId == null ? null : index.get(sessionId);
                        if (entry != null) {
                            title = entry.title;
                            updatedAt = Math.max(updatedAt, entry.updatedAt);
                        }
                    }
                }

                if (title == null)
                    title = extractCodexUserTitle(object);

                if (cwd != null && title != null && sessionId != null)
                    break;
            }
        }

        if (cwd == null || cwd.isEmpty()) return null;
        return new CodexSessionMeta(sessionId, cwd, title, updatedAt);
    }

    private static void scanClaudeSessions(File homeDir, Map<String, TermuxPlusWorkspace> workspaceMap) {
        File projectsDir = new File(new File(homeDir, ".claude"), "projects");
        if (!projectsDir.isDirectory()) return;

        List<File> files = collectClaudeProjectFiles(projectsDir);
        trimFileList(files, MAX_AGENT_FILES_TO_SCAN);

        for (File file : files) {
            try {
                ClaudeSessionMeta meta = readClaudeSessionMeta(file);
                if (meta == null || meta.cwd == null || meta.cwd.isEmpty()) continue;
                if (!isUsableWorkspace(meta.cwd)) continue;

                TermuxPlusWorkspace workspace = getOrCreateWorkspace(workspaceMap, meta.cwd);
                workspace.addSession(new TermuxPlusAgentSession(TermuxPlusAgentSession.AGENT_CLAUDE,
                    meta.sessionId, cleanTitle(meta.title, "Claude session"), meta.updatedAt));
            } catch (Exception e) {
                Logger.logWarn(LOG_TAG, "Failed to read Claude session \"" + file + "\": " + e.getMessage());
            }
        }
    }

    private static ClaudeSessionMeta readClaudeSessionMeta(File file) throws Exception {
        String sessionId = null;
        String cwd = null;
        String title = null;
        long updatedAt = file.lastModified();

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount++ < MAX_CLAUDE_LINES_TO_SCAN) {
                if (line.trim().isEmpty()) continue;
                JSONObject object = new JSONObject(line);

                if (sessionId == null)
                    sessionId = object.optString("sessionId", null);
                if (cwd == null)
                    cwd = object.optString("cwd", null);

                long lineUpdatedAt = parseIsoMillis(object.optString("timestamp", null));
                if (lineUpdatedAt > 0)
                    updatedAt = Math.max(updatedAt, lineUpdatedAt);

                String type = object.optString("type", null);
                if (title == null && "ai-title".equals(type))
                    title = firstNonEmpty(object, "aiTitle", "title", "summary");
                if (title == null && "summary".equals(type))
                    title = firstNonEmpty(object, "summary", "title");
                if (title == null)
                    title = extractClaudeUserTitle(object);

                if (cwd != null && title != null && sessionId != null)
                    break;
            }
        }

        if (cwd == null || cwd.isEmpty()) return null;
        return new ClaudeSessionMeta(sessionId, cwd, title, updatedAt);
    }

    private static String extractCodexUserTitle(JSONObject object) {
        if (!"event_msg".equals(object.optString("type"))) return null;

        JSONObject payload = object.optJSONObject("payload");
        if (payload == null || !"user_message".equals(payload.optString("type"))) return null;

        String title = extractJsonText(payload.opt("message"));
        if (title != null) return title;
        return extractJsonText(payload.opt("text_elements"));
    }

    private static String extractClaudeUserTitle(JSONObject object) {
        if (object.has("attachment")) return null;

        String display = object.optString("display", null);
        if (display != null && !display.trim().isEmpty())
            return display;

        JSONObject message = object.optJSONObject("message");
        if (message == null || !"user".equals(message.optString("role"))) return null;

        Object content = message.opt("content");
        return extractJsonText(content);
    }

    private static TermuxPlusWorkspace getOrCreateWorkspace(Map<String, TermuxPlusWorkspace> workspaceMap, String path) {
        TermuxPlusWorkspace workspace = workspaceMap.get(path);
        if (workspace == null) {
            workspace = new TermuxPlusWorkspace(path);
            workspaceMap.put(path, workspace);
        }
        return workspace;
    }

    private static boolean isUsableWorkspace(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        File file = new File(path);
        if (!file.isDirectory()) return false;

        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        String prefix = TermuxConstants.TERMUX_PREFIX_DIR_PATH;
        return path.equals(home) || path.startsWith(home + "/") || !path.startsWith(prefix + "/");
    }

    private static List<File> collectJsonlFiles(File root, boolean recursive) {
        List<File> files = new ArrayList<>();
        if (root == null || !root.isDirectory()) return files;

        ArrayDeque<File> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            File dir = pending.removeFirst();
            File[] children = dir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isDirectory()) {
                    if (recursive)
                        pending.add(child);
                } else if (child.isFile() && child.getName().endsWith(".jsonl")) {
                    files.add(child);
                }
            }
        }

        sortFilesByMtime(files);
        return files;
    }

    private static List<File> collectClaudeProjectFiles(File projectsDir) {
        List<File> files = new ArrayList<>();
        File[] projectDirs = projectsDir.listFiles();
        if (projectDirs == null) return files;

        for (File projectDir : projectDirs) {
            if (!projectDir.isDirectory()) continue;
            File[] children = projectDir.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (child.isFile() && child.getName().endsWith(".jsonl"))
                    files.add(child);
            }
        }

        sortFilesByMtime(files);
        return files;
    }

    private static void sortFilesByMtime(List<File> files) {
        Collections.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
    }

    private static void trimFileList(List<File> files, int maxFiles) {
        while (files.size() > maxFiles)
            files.remove(files.size() - 1);
    }

    private static void loadGitMetadata(TermuxPlusWorkspace workspace) {
        if (workspace == null || workspace.getPath() == null) return;
        GitInfo info = loadGitInfoWithGit(workspace.getPath());
        if (info == null)
            info = loadGitInfoFromHead(workspace.getPath());
        if (info == null) return;

        workspace.setGitBranch(info.branch);
        workspace.setGitDirty(info.dirty);
    }

    private static GitInfo loadGitInfoWithGit(String path) {
        String git = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/git";
        if (!new File(git).canExecute())
            git = "git";

        String branch = runGitOutput(path, git, "branch", "--show-current");
        if (branch == null || branch.trim().isEmpty())
            branch = runGitOutput(path, git, "rev-parse", "--short", "HEAD");
        if (branch == null || branch.trim().isEmpty()) return null;

        boolean dirty = runGitExit(path, git, "diff-index", "--quiet", "HEAD", "--") == 1
            || runGitExit(path, git, "diff-files", "--quiet") == 1;
        return new GitInfo(branch.trim(), dirty);
    }

    private static GitInfo loadGitInfoFromHead(String path) {
        File gitDir = resolveGitDir(path);
        if (gitDir == null) return null;
        File headFile = new File(gitDir, "HEAD");
        if (!headFile.isFile()) return null;

        try {
            String head = readFirstLine(headFile);
            if (head == null) return null;
            if (head.startsWith("ref: refs/heads/"))
                return new GitInfo(head.substring("ref: refs/heads/".length()).trim(), false);
            if (head.length() >= 7)
                return new GitInfo(head.substring(0, 7), false);
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to read git HEAD for \"" + path + "\": " + e.getMessage());
        }
        return null;
    }

    private static File resolveGitDir(String path) {
        File dotGit = new File(path, ".git");
        if (dotGit.isDirectory()) return dotGit;
        if (!dotGit.isFile()) return null;

        try {
            String line = readFirstLine(dotGit);
            if (line != null && line.startsWith("gitdir:")) {
                String gitDirPath = line.substring("gitdir:".length()).trim();
                File gitDir = new File(gitDirPath);
                if (!gitDir.isAbsolute())
                    gitDir = new File(new File(path), gitDirPath);
                return gitDir;
            }
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to resolve gitdir for \"" + path + "\": " + e.getMessage());
        }

        return null;
    }

    private static String runGitOutput(String cwd, String git, String... args) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(git);
            command.add("-C");
            command.add(cwd);
            Collections.addAll(command, args);

            ProcessBuilder builder = new ProcessBuilder(command);
            applyTermuxEnvironment(builder);
            builder.redirectErrorStream(true);
            process = builder.start();
            Integer exitCode = waitForProcess(process, 1200);
            if (exitCode == null) {
                process.destroy();
                return null;
            }
            if (exitCode != 0) return null;
            return readFirstLine(process);
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null)
                process.destroy();
        }
    }

    private static int runGitExit(String cwd, String git, String... args) {
        Process process = null;
        try {
            List<String> command = new ArrayList<>();
            command.add(git);
            command.add("-C");
            command.add(cwd);
            Collections.addAll(command, args);

            ProcessBuilder builder = new ProcessBuilder(command);
            applyTermuxEnvironment(builder);
            builder.redirectErrorStream(true);
            process = builder.start();
            Integer exitCode = waitForProcess(process, 1200);
            if (exitCode == null) {
                process.destroy();
                return -2;
            }
            return exitCode;
        } catch (Exception e) {
            return -2;
        } finally {
            if (process != null)
                process.destroy();
        }
    }

    private static Integer waitForProcess(Process process, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException e) {
                if (System.currentTimeMillis() >= deadline)
                    return null;
                Thread.sleep(25);
            }
        }
    }

    private static void applyTermuxEnvironment(ProcessBuilder builder) {
        Map<String, String> env = builder.environment();
        env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
        env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
        String currentPath = env.get("PATH");
        env.put("PATH", currentPath == null || currentPath.isEmpty()
            ? TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH
            : TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":" + currentPath);
        // Do NOT set LD_LIBRARY_PATH: Termux binaries on Android 7+ (the apt-android-7
        // variant this app ships) rely on DT_RUNPATH, and TermuxShellEnvironment
        // deliberately leaves LD_LIBRARY_PATH unset for real sessions. Forcing
        // $PREFIX/lib here diverged from that and could make a spawned binary load
        // the wrong shared libraries.
    }

    private static String readFirstLine(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return reader.readLine();
        }
    }

    private static String readFirstLine(Process process) throws IOException {
        try (BufferedReader reader = new BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            return reader.readLine();
        }
    }

    private static String cleanTitle(String title, String fallback) {
        if (title == null || title.trim().isEmpty())
            return fallback;
        String clean = title.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        while (clean.contains("  "))
            clean = clean.replace("  ", " ");
        if (clean.length() > 56)
            return clean.substring(0, 55) + "...";
        return clean;
    }

    private static String firstNonEmpty(JSONObject object, String... keys) {
        if (object == null) return null;
        for (String key : keys) {
            String value = object.optString(key, null);
            if (value != null && !value.trim().isEmpty())
                return value;
        }
        return null;
    }

    private static String extractJsonText(Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }

        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            String text = firstNonEmpty(object, "text", "content", "message", "summary", "title");
            if (text != null) return text;
        }

        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) {
                String text = extractJsonText(array.opt(i));
                if (text != null) return text;
            }
        }

        return null;
    }

    private static long parseIsoMillis(String value) {
        if (value == null || value.trim().isEmpty()) return 0;

        String normalized = value.trim();
        int dotIndex = normalized.indexOf('.');
        int zoneIndex = normalized.endsWith("Z") ? normalized.length() - 1 : -1;
        String pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'";

        if (dotIndex >= 0 && zoneIndex > dotIndex) {
            String fraction = normalized.substring(dotIndex + 1, zoneIndex);
            if (fraction.length() > 3)
                fraction = fraction.substring(0, 3);
            while (fraction.length() < 3)
                fraction += "0";
            normalized = normalized.substring(0, dotIndex + 1) + fraction + "Z";
            pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";
        }

        try {
            SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(normalized).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static final class CodexIndexEntry {
        final String title;
        final long updatedAt;

        CodexIndexEntry(String title, long updatedAt) {
            this.title = title;
            this.updatedAt = updatedAt;
        }
    }

    private static final class CodexSessionMeta {
        final String sessionId;
        final String cwd;
        final String title;
        final long updatedAt;

        CodexSessionMeta(String sessionId, String cwd, String title, long updatedAt) {
            this.sessionId = sessionId;
            this.cwd = cwd;
            this.title = title;
            this.updatedAt = updatedAt;
        }
    }

    private static final class ClaudeSessionMeta {
        final String sessionId;
        final String cwd;
        final String title;
        final long updatedAt;

        ClaudeSessionMeta(String sessionId, String cwd, String title, long updatedAt) {
            this.sessionId = sessionId;
            this.cwd = cwd;
            this.title = title;
            this.updatedAt = updatedAt;
        }
    }

    private static final class GitInfo {
        final String branch;
        final boolean dirty;

        GitInfo(String branch, boolean dirty) {
            this.branch = branch;
            this.dirty = dirty;
        }
    }
}
