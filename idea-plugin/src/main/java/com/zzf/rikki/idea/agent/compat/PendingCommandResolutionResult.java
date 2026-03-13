package com.zzf.rikki.idea.agent.compat;

public class PendingCommandResolutionResult {
    private final String status;
    private final String output;
    private final String error;
    private final Integer exitCode;
    private final String command;
    private final boolean timeout;
    private final String decisionMode;

    public PendingCommandResolutionResult(
            String status,
            String output,
            String error,
            Integer exitCode,
            String command,
            boolean timeout,
            String decisionMode
    ) {
        this.status = status;
        this.output = output;
        this.error = error == null ? "" : error;
        this.exitCode = exitCode;
        this.command = command == null ? "" : command;
        this.timeout = timeout;
        this.decisionMode = decisionMode == null ? PendingApprovalService.DECISION_MANUAL : decisionMode;
    }

    public String getStatus() { return status; }
    public String getOutput() { return output; }
    public String getError() { return error; }
    public Integer getExitCode() { return exitCode; }
    public String getCommand() { return command; }
    public boolean getTimeout() { return timeout; }
    public String getDecisionMode() { return decisionMode; }
}
