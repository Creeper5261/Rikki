package com.zzf.rikki.idea.agent.compat;

import com.zzf.rikki.core.tool.PendingChangesManager;

import java.util.LinkedHashMap;
import java.util.Map;

public class ToolExecutionResult {
    private final String status;
    private final String output;
    private final String error;
    private final Map<String, Object> meta;
    private final PendingChangesManager.PendingChange pendingChange;
    private final PendingCommandRecord pendingCommand;
    private final String todoJson;
    private final Integer exitCode;
    private final boolean timeout;

    public ToolExecutionResult(
            String status,
            String output,
            String error,
            Map<String, ?> meta,
            PendingChangesManager.PendingChange pendingChange,
            PendingCommandRecord pendingCommand,
            String todoJson,
            Integer exitCode,
            boolean timeout
    ) {
        this.status = status == null ? "completed" : status;
        this.output = output == null ? "" : output;
        this.error = error;
        this.meta = copyMap(meta);
        this.pendingChange = pendingChange;
        this.pendingCommand = pendingCommand;
        this.todoJson = todoJson;
        this.exitCode = exitCode;
        this.timeout = timeout;
    }

    public String getStatus() { return status; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public Map<String, Object> getMeta() { return meta; }
    public PendingChangesManager.PendingChange getPendingChange() { return pendingChange; }
    public PendingCommandRecord getPendingCommand() { return pendingCommand; }
    public String getTodoJson() { return todoJson; }
    public Integer getExitCode() { return exitCode; }
    public boolean getTimeout() { return timeout; }

    public PendingCommandResolutionResult toResolutionResult(String command, String decisionMode) {
        return new PendingCommandResolutionResult(
                status,
                output,
                error == null ? "" : error,
                exitCode,
                command == null ? "" : command,
                timeout,
                decisionMode == null ? PendingApprovalService.DECISION_MANUAL : decisionMode
        );
    }

    private static Map<String, Object> copyMap(Map<String, ?> raw) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        if (raw == null) {
            return map;
        }
        map.putAll(raw);
        return map;
    }
}
