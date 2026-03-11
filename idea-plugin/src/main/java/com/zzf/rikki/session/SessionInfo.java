package com.zzf.rikki.session;

public class SessionInfo {
    public final String id;
    public final String workspaceRoot;
    public final long createdAt;
    public volatile long updatedAt;
    public volatile boolean historyImported;

    public SessionInfo(String id, String workspaceRoot) {
        this.id = id;
        this.workspaceRoot = workspaceRoot;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
        this.historyImported = false;
    }
}
