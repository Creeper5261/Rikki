package com.zzf.rikki.session;

public class SessionInfo {
    public final String id;
    public final String workspaceRoot;
    public final long createdAt;
    public volatile long updatedAt;
    public volatile boolean historyImported;
    public volatile String parentSessionId;
    public volatile String title;
    public volatile String agentName;

    public SessionInfo(String id, String workspaceRoot) {
        this(id, workspaceRoot, null, null, null);
    }

    public SessionInfo(String id, String workspaceRoot, String parentSessionId, String title, String agentName) {
        this.id = id;
        this.workspaceRoot = workspaceRoot;
        this.parentSessionId = parentSessionId;
        this.title = title;
        this.agentName = agentName;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.historyImported = false;
    }
}