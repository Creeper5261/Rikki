package com.zzf.rikki.idea.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.project.Project;
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService;
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor;
import com.zzf.rikki.idea.agent.compat.ToolExecutionResult;
import com.zzf.rikki.idea.agent.tools.LiteIdeTools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class LiteToolRegistry {
    public static class ToolResult {
        private final String output;
        private final String error;
        private final String pendingChangePath;
        private final String pendingChangeOld;
        private final String pendingChangeNew;
        private final String pendingChangeType;

        public ToolResult(String output, String error, String pendingChangePath, String pendingChangeOld, String pendingChangeNew, String pendingChangeType) {
            this.output = output;
            this.error = error;
            this.pendingChangePath = pendingChangePath;
            this.pendingChangeOld = pendingChangeOld;
            this.pendingChangeNew = pendingChangeNew;
            this.pendingChangeType = pendingChangeType;
        }

        public String getOutput() { return output; }
        public String getError() { return error; }
        public String getPendingChangePath() { return pendingChangePath; }
        public String getPendingChangeOld() { return pendingChangeOld; }
        public String getPendingChangeNew() { return pendingChangeNew; }
        public String getPendingChangeType() { return pendingChangeType; }
    }

    private final InMemoryPendingApprovalService pendingApprovalService;
    private final LiteToolExecutor executor;

    public LiteToolRegistry(Project project, ObjectMapper mapper) {
        this(project, mapper, new InMemoryPendingApprovalService());
    }

    public LiteToolRegistry(Project project, ObjectMapper mapper, InMemoryPendingApprovalService pendingApprovalService) {
        this.pendingApprovalService = pendingApprovalService;
        this.executor = new LiteToolExecutor(project, mapper, pendingApprovalService);
    }

    public void setSkipFlag(AtomicBoolean flag) {
        if (flag.get()) {
            pendingApprovalService.skipCurrentExecution();
        }
    }

    public boolean isHighRisk(String name, JsonNode args) {
        return executor.isHighRisk(name, args);
    }

    public ToolResult execute(String name, JsonNode args, String workspaceRoot, String sessionId, String callId) {
        ToolExecutionResult result = executor.execute(name, args, workspaceRoot, sessionId, callId, "");
        return new ToolResult(
                result.getOutput(),
                result.getError(),
                result.getPendingChange() == null ? null : result.getPendingChange().path,
                result.getPendingChange() == null ? null : result.getPendingChange().oldContent,
                result.getPendingChange() == null ? null : result.getPendingChange().newContent,
                result.getPendingChange() == null ? null : result.getPendingChange().type
        );
    }

    public List<Map<String, Object>> toolDefinitions(String workspaceRoot, LiteIdeTools.CapabilitySnapshot snapshot) {
        return executor.toolDefinitions(workspaceRoot, snapshot);
    }

    public String readTodosJson(String workspaceRoot, String sessionId) {
        return executor.readTodosJson(workspaceRoot, sessionId);
    }
}
