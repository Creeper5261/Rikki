package com.zzf.rikki.idea.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.project.Project;
import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.bus.AgentBus;
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService;
import com.zzf.rikki.idea.agent.compat.JavaCompatRuntime;
import com.zzf.rikki.idea.agent.compat.LiteChatLlmStreamClient;
import com.zzf.rikki.idea.agent.compat.LiteModelSupport;
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor;
import com.zzf.rikki.llm.LLMService;
import com.zzf.rikki.runtime.port.RuntimeRequest;
import com.zzf.rikki.session.ContextCompactionService;
import com.zzf.rikki.session.InstructionPrompt;
import com.zzf.rikki.session.PromptReminderService;
import com.zzf.rikki.session.SessionLoop;
import com.zzf.rikki.session.SessionProcessor;
import com.zzf.rikki.session.SessionService;
import com.zzf.rikki.session.SessionStatus;
import com.zzf.rikki.session.SystemPrompt;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LiteAgentEngine {
    private final InMemoryPendingApprovalService pendingApprovalService;
    private final JavaCompatRuntime runtime;

    public LiteAgentEngine(Project project, ObjectMapper mapper) {
        this(project, mapper, new InMemoryPendingApprovalService(), null);
    }

    public LiteAgentEngine(Project project, ObjectMapper mapper, InMemoryPendingApprovalService pendingApprovalService, LiteToolExecutor toolExecutor) {
        this.pendingApprovalService = pendingApprovalService;
        LiteToolExecutor executor = toolExecutor == null ? new LiteToolExecutor(project, mapper, pendingApprovalService) : toolExecutor;
        AgentBus agentBus = new AgentBus();
        SessionService sessionService = new SessionService(mapper);
        SessionStatus sessionStatus = new SessionStatus();
        AgentService agentService = new AgentService();
        executor.bindServices(sessionService, agentService);
        LLMService llmService = new LLMService(new LiteChatLlmStreamClient(mapper));
        SessionProcessor processor = new SessionProcessor(mapper, sessionService, sessionStatus, pendingApprovalService, llmService, executor);
        SessionLoop sessionLoop = new SessionLoop(
                sessionService,
                sessionStatus,
                new SystemPrompt(mapper),
                new InstructionPrompt(),
                new PromptReminderService(),
                processor,
                executor,
                agentService,
                new ContextCompactionService(sessionService, llmService, agentService)
        );
        this.runtime = new JavaCompatRuntime(sessionLoop, agentBus);
    }

    public InMemoryPendingApprovalService approvalService() {
        return pendingApprovalService;
    }

    public void setSkipFlag(AtomicBoolean flag) {
        if (flag.get()) {
            pendingApprovalService.skipCurrentExecution();
        }
    }

    public void setConfirmFutureRef(AtomicReference<CompletableFuture<Boolean>> ref) {
        ref.set(null);
    }

    public void run(String goal, String workspaceRoot, JsonNode ideContext, JsonNode history, JsonNode settings, String sessionId, LiteSseWriter writer) {
        runtime.run(new RuntimeRequest(goal, workspaceRoot, ideContext, history, settings, sessionId), writer);
    }

    public static com.zzf.rikki.idea.agent.compat.ModelCapabilities detectCapabilities(String provider, String model) {
        return LiteModelSupport.INSTANCE.detectCapabilities(provider, model);
    }

    public static Map.Entry<String, String> parseHistoryLine(String text) {
        return LiteModelSupport.INSTANCE.parseHistoryLine(text);
    }
}
