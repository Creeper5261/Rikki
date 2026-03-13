package com.zzf.rikki.idea.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.project.Project;
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService;
import com.zzf.rikki.idea.agent.compat.LiteChatLlmStreamClient;
import com.zzf.rikki.idea.agent.compat.LiteModelSupport;
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor;
import com.zzf.rikki.runtime.AgentRuntimeBootstrap;
import com.zzf.rikki.runtime.AgentRuntimeFactory;
import com.zzf.rikki.runtime.IdeRuntimeConfigResolver;
import com.zzf.rikki.runtime.RuntimeAgentConfig;
import com.zzf.rikki.runtime.RuntimeConfigResolver;
import com.zzf.rikki.runtime.port.RuntimeRequest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class LiteAgentEngine {
    private final ObjectMapper mapper;
    private final InMemoryPendingApprovalService pendingApprovalService;
    private final com.zzf.rikki.runtime.port.AgentRuntime runtime;
    private final RuntimeConfigResolver configResolver;

    public LiteAgentEngine(Project project, ObjectMapper mapper) {
        this(project, mapper, new InMemoryPendingApprovalService(), null, new IdeRuntimeConfigResolver());
    }

    public LiteAgentEngine(Project project, ObjectMapper mapper, InMemoryPendingApprovalService pendingApprovalService, LiteToolExecutor toolExecutor) {
        this(project, mapper, pendingApprovalService, toolExecutor, new IdeRuntimeConfigResolver());
    }

    public LiteAgentEngine(
            Project project,
            ObjectMapper mapper,
            InMemoryPendingApprovalService pendingApprovalService,
            LiteToolExecutor toolExecutor,
            RuntimeConfigResolver configResolver
    ) {
        this.mapper = mapper;
        this.pendingApprovalService = pendingApprovalService;
        LiteToolExecutor executor = toolExecutor == null ? new LiteToolExecutor(project, mapper, pendingApprovalService) : toolExecutor;
        AgentRuntimeBootstrap bootstrap = AgentRuntimeFactory.create(mapper, new LiteChatLlmStreamClient(mapper), pendingApprovalService, executor);
        this.runtime = bootstrap.getRuntime();
        this.configResolver = configResolver;
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
        runtime.run(
                new RuntimeRequest(
                        goal,
                        workspaceRoot,
                        ideContext,
                        history,
                        settings,
                        sessionId,
                        resolveConfig(settings)
                ),
                writer
        );
    }

    public static com.zzf.rikki.idea.agent.compat.ModelCapabilities detectCapabilities(String provider, String model) {
        return LiteModelSupport.INSTANCE.detectCapabilities(provider, model);
    }

    public static Map.Entry<String, String> parseHistoryLine(String text) {
        return LiteModelSupport.INSTANCE.parseHistoryLine(text);
    }

    private RuntimeAgentConfig resolveConfig(JsonNode settings) {
        return configResolver.resolve(toMap(settings));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(JsonNode settings) {
        if (settings == null || settings.isNull() || settings.isMissingNode()) {
            return Map.of();
        }
        try {
            return mapper.convertValue(settings, LinkedHashMap.class);
        } catch (IllegalArgumentException ignored) {
            return Map.of();
        }
    }
}
