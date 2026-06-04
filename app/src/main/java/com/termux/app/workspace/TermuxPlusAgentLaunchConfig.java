package com.termux.app.workspace;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public final class TermuxPlusAgentLaunchConfig {

    private static final String LOG_TAG = "TermuxPlusAgentLaunchConfig";
    private static final String CONFIG_FILE_PATH = TermuxConstants.TERMUX_HOME_DIR_PATH
        + "/.termuxplus/agent-launch.json";
    private static final String RESUME_ID_PLACEHOLDER = "{resume_id}";

    private TermuxPlusAgentLaunchConfig() {}

    public static File getConfigFile() {
        return new File(CONFIG_FILE_PATH);
    }

    public static void ensureDefaultConfig() throws IOException {
        File file = getConfigFile();
        if (file.isFile()) return;

        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Failed to create \"" + parent + "\"");

        writeText(file, defaultConfigJson());
    }

    public static String getNewCommand(String agent) {
        return getCommand(agent, "new", defaultNewCommand(agent));
    }

    public static String getResumeCommand(String agent, String resumeId) {
        String template = getCommand(agent, "resume", defaultResumeCommand(agent));
        if (resumeId == null || resumeId.isEmpty())
            return getNewCommand(agent);
        return template.replace(RESUME_ID_PLACEHOLDER, shellQuote(resumeId));
    }

    private static String getCommand(String agent, String key, String fallback) {
        if (agent == null || agent.isEmpty()) return fallback;

        File file = getConfigFile();
        if (!file.isFile()) return fallback;

        try (InputStream inputStream = new FileInputStream(file)) {
            JSONObject root = new JSONObject(readText(inputStream));
            JSONObject agentObject = root.optJSONObject(agent);
            if (agentObject == null) return fallback;

            String command = agentObject.optString(key, null);
            if (command == null || command.trim().isEmpty()) return fallback;
            return command.trim();
        } catch (Exception e) {
            Logger.logWarn(LOG_TAG, "Failed to read agent launch config: " + e.getMessage());
            return fallback;
        }
    }

    private static String defaultNewCommand(String agent) {
        if (TermuxPlusAgentSession.AGENT_CLAUDE.equals(agent))
            return "claude";
        return "codex";
    }

    private static String defaultResumeCommand(String agent) {
        if (TermuxPlusAgentSession.AGENT_CLAUDE.equals(agent))
            return "claude --resume " + RESUME_ID_PLACEHOLDER;
        return "codex resume " + RESUME_ID_PLACEHOLDER;
    }

    private static String defaultConfigJson() {
        return "{\n"
            + "  \"version\": 1,\n"
            + "  \"hint\": \"Edit new/resume commands. Use {resume_id} in resume templates; it is shell-quoted at launch time.\",\n"
            + "  \"codex\": {\n"
            + "    \"new\": \"codex\",\n"
            + "    \"resume\": \"codex resume {resume_id}\",\n"
            + "    \"max_new_example\": \"codex --dangerously-bypass-approvals-and-sandbox\",\n"
            + "    \"max_resume_example\": \"codex --dangerously-bypass-approvals-and-sandbox resume {resume_id}\"\n"
            + "  },\n"
            + "  \"claude\": {\n"
            + "    \"new\": \"claude\",\n"
            + "    \"resume\": \"claude --resume {resume_id}\",\n"
            + "    \"max_new_example\": \"claude --permission-mode bypassPermissions\",\n"
            + "    \"max_resume_example\": \"claude --permission-mode bypassPermissions --resume {resume_id}\"\n"
            + "  }\n"
            + "}";
    }

    private static String shellQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "'\"'\"'") + "'";
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
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(text);
            writer.write('\n');
        }
    }
}
