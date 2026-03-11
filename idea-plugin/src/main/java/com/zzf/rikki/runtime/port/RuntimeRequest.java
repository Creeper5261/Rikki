package com.zzf.rikki.runtime.port;

import com.fasterxml.jackson.databind.JsonNode;

public class RuntimeRequest {
    private final String goal;
    private final String workspaceRoot;
    private final JsonNode ideContext;
    private final JsonNode history;
    private final JsonNode settings;
    private final String sessionId;

    public RuntimeRequest(String goal, String workspaceRoot, JsonNode ideContext, JsonNode history, JsonNode settings, String sessionId) {
        this.goal = goal;
        this.workspaceRoot = workspaceRoot;
        this.ideContext = ideContext;
        this.history = history;
        this.settings = settings;
        this.sessionId = sessionId;
    }

    public String getGoal() {
        return goal;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public JsonNode getIdeContext() {
        return ideContext;
    }

    public JsonNode getHistory() {
        return history;
    }

    public JsonNode getSettings() {
        return settings;
    }

    public String getSessionId() {
        return sessionId;
    }
}
