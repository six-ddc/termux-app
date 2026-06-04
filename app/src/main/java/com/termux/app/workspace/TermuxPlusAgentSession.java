package com.termux.app.workspace;

public class TermuxPlusAgentSession {

    public static final String AGENT_CODEX = "codex";
    public static final String AGENT_CLAUDE = "claude";

    private final String mAgent;
    private final String mSessionId;
    private final String mTitle;
    private final long mUpdatedAt;

    public TermuxPlusAgentSession(String agent, String sessionId, String title, long updatedAt) {
        mAgent = agent;
        mSessionId = sessionId;
        mTitle = title;
        mUpdatedAt = updatedAt;
    }

    public String getAgent() {
        return mAgent;
    }

    public String getSessionId() {
        return mSessionId;
    }

    public String getTitle() {
        return mTitle;
    }

    public long getUpdatedAt() {
        return mUpdatedAt;
    }
}
