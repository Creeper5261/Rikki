package com.zzf.rikki.idea.agent.compat;

public class PendingCommandRegistration {
    private final PendingCommandRecord pendingCommand;
    private final boolean awaitApproval;
    private final ToolExecutionResult immediateResult;

    public PendingCommandRegistration(PendingCommandRecord pendingCommand, boolean awaitApproval, ToolExecutionResult immediateResult) {
        this.pendingCommand = pendingCommand;
        this.awaitApproval = awaitApproval;
        this.immediateResult = immediateResult;
    }

    public PendingCommandRecord getPendingCommand() {
        return pendingCommand;
    }

    public boolean getAwaitApproval() {
        return awaitApproval;
    }

    public ToolExecutionResult getImmediateResult() {
        return immediateResult;
    }
}
