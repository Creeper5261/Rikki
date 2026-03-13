package com.zzf.rikki.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.project.Project;
import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.bus.AgentBus;
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService;
import com.zzf.rikki.idea.agent.compat.JavaCompatRuntime;
import com.zzf.rikki.idea.agent.compat.LiteChatLlmStreamClient;
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor;
import com.zzf.rikki.llm.LLMService;
import com.zzf.rikki.runtime.port.AgentRuntime;
import com.zzf.rikki.runtime.port.LlmPort;
import com.zzf.rikki.runtime.port.PendingApprovalPort;
import com.zzf.rikki.runtime.port.ToolExecutorPort;
import com.zzf.rikki.session.ContextCompactionService;
import com.zzf.rikki.session.InstructionPrompt;
import com.zzf.rikki.session.PromptReminderService;
import com.zzf.rikki.session.SessionLoop;
import com.zzf.rikki.session.SessionProcessor;
import com.zzf.rikki.session.SessionService;
import com.zzf.rikki.session.SessionStatus;
import com.zzf.rikki.session.SystemPrompt;

public final class AgentRuntimeFactory {
    private AgentRuntimeFactory() {
    }

    public static AgentRuntimeBootstrap create(Project project, ObjectMapper mapper) {
        InMemoryPendingApprovalService pendingApprovalService = new InMemoryPendingApprovalService();
        LiteToolExecutor toolExecutor = new LiteToolExecutor(project, mapper, pendingApprovalService);
        return create(mapper, new LiteChatLlmStreamClient(mapper), pendingApprovalService, toolExecutor);
    }

    public static AgentRuntimeBootstrap create(
            ObjectMapper mapper,
            LlmPort llmPort,
            PendingApprovalPort pendingApprovalPort,
            ToolExecutorPort toolExecutorPort
    ) {
        AgentBus agentBus = new AgentBus();
        SessionService sessionService = new SessionService(mapper);
        SessionStatus sessionStatus = new SessionStatus();
        AgentService agentService = new AgentService();
        if (toolExecutorPort instanceof RuntimeServicesAware servicesAware) {
            servicesAware.bindRuntimeServices(sessionService, agentService);
        }
        LLMService llmService = new LLMService(llmPort);
        SessionProcessor processor = new SessionProcessor(mapper, sessionService, sessionStatus, pendingApprovalPort, llmService, toolExecutorPort);
        SessionLoop sessionLoop = new SessionLoop(
                sessionService,
                sessionStatus,
                new SystemPrompt(mapper),
                new InstructionPrompt(),
                new PromptReminderService(),
                processor,
                toolExecutorPort,
                agentService,
                new ContextCompactionService(sessionService, llmService, agentService)
        );
        AgentRuntime runtime = new JavaCompatRuntime(sessionLoop, agentBus);
        return new AgentRuntimeBootstrap(runtime, pendingApprovalPort, toolExecutorPort, sessionService, sessionStatus, agentService);
    }
}
