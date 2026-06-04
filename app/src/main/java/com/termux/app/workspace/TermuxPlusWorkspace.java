package com.termux.app.workspace;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class TermuxPlusWorkspace {

    private final String mPath;
    private final List<TermuxPlusAgentSession> mSessions = new ArrayList<>();
    private String mGitBranch;
    private boolean mGitDirty;
    private long mUpdatedAt;

    public TermuxPlusWorkspace(String path) {
        mPath = path;
    }

    public String getPath() {
        return mPath;
    }

    public String getName() {
        if (mPath == null || mPath.isEmpty()) return "";
        String name = new File(mPath).getName();
        return name == null || name.isEmpty() ? mPath : name;
    }

    public List<TermuxPlusAgentSession> getSessions() {
        return mSessions;
    }

    public void addSession(TermuxPlusAgentSession session) {
        if (session == null) return;
        mSessions.add(session);
        mUpdatedAt = Math.max(mUpdatedAt, session.getUpdatedAt());
    }

    public void sortSessions() {
        Collections.sort(mSessions, (a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt()));
    }

    public void trimSessions(int maxSessions) {
        if (maxSessions < 0) return;
        while (mSessions.size() > maxSessions)
            mSessions.remove(mSessions.size() - 1);
    }

    public String getGitBranch() {
        return mGitBranch;
    }

    public void setGitBranch(String gitBranch) {
        mGitBranch = gitBranch;
    }

    public boolean isGitDirty() {
        return mGitDirty;
    }

    public void setGitDirty(boolean gitDirty) {
        mGitDirty = gitDirty;
    }

    public long getUpdatedAt() {
        return mUpdatedAt;
    }

    public static final Comparator<TermuxPlusWorkspace> UPDATED_DESC =
        (a, b) -> Long.compare(b.getUpdatedAt(), a.getUpdatedAt());
}
