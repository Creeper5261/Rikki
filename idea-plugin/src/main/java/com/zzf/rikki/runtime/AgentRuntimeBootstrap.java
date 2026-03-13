package com.zzf.rikki.runtime;

import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.runtime.port.AgentRuntime;
import com.zzf.rikki.runtime.port.PendingApprovalPort;
import com.zzf.rikki.runtime.port.ToolExecutorPort;
import com.zzf.rikki.session.SessionService;
import com.zzf.rikki.session.SessionStatus;

public final class AgentRuntimeBootstrap {
    private final AgentRuntime runtime;
    private final PendingApprovalPort pendingApprovalPort;
    private final ToolExecutorPort toolExecutorPort;
    private final SessionService sessionService;
    private final SessionStatus sessionStatus;
    private final AgentService agentService;

    public AgentRuntimeBootstrap(
            AgentRuntime runtime,
            PendingApprovalPort pendingApprovalPort,
            ToolExecutorPort toolExecutorPort,
            SessionService sessionService,
            SessionStatus sessionStatus,
            AgentService agentService
    ) {
        this.runtime = runtime;
        this.pendingApprovalPort = pendingApprovalPort;
        this.toolExecutorPort = toolExecutorPort;
        this.sessionService = sessionService;
        this.sessionStatus = sessionStatus;
        this.agentService = agentService;
    }

    public AgentRuntime getRuntime() {
        return runtime;
    }

    public PendingApprovalPort getPendingApprovalPort() {
        return pendingApprovalPort;
    }

    public ToolExecutorPort getToolExecutorPort() {
        return toolExecutorPort;
    }

    public SessionService getSessionService() {
        return sessionService;
    }

    public SessionStatus getSessionStatus() {
        return sessionStatus;
    }

    public AgentService getAgentService() {
        return agentService;
    }
}
