package com.zzf.rikki.idea.agent.compat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class PendingCommandRecord {
    private final String id;
    private final String command;
    private final String description;
    private final String workdir;
    private final String workspaceRoot;
    private final String sessionId;
    private final long timeoutMs;
    private final String tool;
    private final String callId;
    private final String messageId;
    private final String riskLevel;
    private final String riskCategory;
    private final String commandFamily;
    private final boolean strictApproval;
    private final List<String> reasons;
    private final String shell;
    private final Supplier<ToolExecutionResult> executor;

    public PendingCommandRecord(
            String id,
            String command,
            String description,
            String workdir,
            String workspaceRoot,
            String sessionId,
            long timeoutMs,
            String tool,
            String callId,
            String messageId,
            String riskLevel,
            String riskCategory,
            String commandFamily,
            boolean strictApproval,
            List<String> reasons,
            String shell,
            Supplier<ToolExecutionResult> executor
    ) {
        this.id = id;
        this.command = command;
        this.description = description;
        this.workdir = workdir;
        this.workspaceRoot = workspaceRoot;
        this.sessionId = sessionId;
        this.timeoutMs = timeoutMs;
        this.tool = tool;
        this.callId = callId;
        this.messageId = messageId;
        this.riskLevel = riskLevel;
        this.riskCategory = riskCategory;
        this.commandFamily = commandFamily;
        this.strictApproval = strictApproval;
        this.reasons = reasons == null ? List.of() : new ArrayList<>(reasons);
        this.shell = shell;
        this.executor = executor;
    }

    public String getId() { return id; }
    public String getCommand() { return command; }
    public String getDescription() { return description; }
    public String getWorkdir() { return workdir; }
    public String getWorkspaceRoot() { return workspaceRoot; }
    public String getSessionId() { return sessionId; }
    public long getTimeoutMs() { return timeoutMs; }
    public String getTool() { return tool; }
    public String getCallId() { return callId; }
    public String getMessageId() { return messageId; }
    public String getRiskLevel() { return riskLevel; }
    public String getRiskCategory() { return riskCategory; }
    public String getCommandFamily() { return commandFamily; }
    public boolean getStrictApproval() { return strictApproval; }
    public List<String> getReasons() { return reasons; }
    public String getShell() { return shell; }
    public Supplier<ToolExecutionResult> getExecutor() { return executor; }

    public Map<String, Object> toMetaMap() {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("command", command);
        map.put("description", description);
        map.put("workdir", workdir);
        map.put("workspaceRoot", workspaceRoot);
        map.put("sessionId", sessionId);
        map.put("timeoutMs", timeoutMs);
        map.put("riskLevel", riskLevel);
        map.put("riskCategory", riskCategory);
        map.put("commandFamily", commandFamily);
        map.put("strictApproval", strictApproval);
        map.put("reasons", reasons);
        return map;
    }
}
