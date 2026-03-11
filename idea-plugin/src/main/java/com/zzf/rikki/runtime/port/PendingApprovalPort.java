package com.zzf.rikki.runtime.port;

import com.zzf.rikki.idea.agent.compat.PendingApprovalService;
import com.zzf.rikki.idea.agent.compat.PendingCommandRecord;
import com.zzf.rikki.idea.agent.compat.PendingCommandRegistration;
import com.zzf.rikki.idea.agent.compat.PendingCommandResolutionResult;
import com.zzf.rikki.idea.agent.compat.ToolExecutionResult;

public interface PendingApprovalPort {
    PendingCommandRegistration registerPendingCommand(PendingCommandRecord pendingCommand);

    ToolExecutionResult awaitDecision(String commandId);

    PendingCommandResolutionResult resolve(String commandId, boolean reject, String decisionMode);

    void skipCurrentExecution();

    void clearSession(String sessionId);

    default String manualDecision() {
        return PendingApprovalService.DECISION_MANUAL;
    }
}
