package com.zzf.rikki.runtime.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService;
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor;
import com.zzf.rikki.idea.agent.compat.ToolExecutionResult;
import com.zzf.rikki.idea.agent.tools.LiteIdeTools;
import com.zzf.rikki.runtime.RuntimeServicesAware;
import com.zzf.rikki.runtime.port.ToolExecutorPort;
import com.zzf.rikki.session.SessionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScenarioToolExecutor implements ToolExecutorPort, RuntimeServicesAware {
    private final LiteToolExecutor delegate;
    private final Map<String, String> stubOutputs;

    public ScenarioToolExecutor(LiteToolExecutor delegate, Map<String, String> stubOutputs) {
        this.delegate = delegate;
        this.stubOutputs = stubOutputs == null ? Map.of() : new LinkedHashMap<>(stubOutputs);
    }

    @Override
    public void bindRuntimeServices(SessionService sessionService, AgentService agentService) {
        delegate.bindRuntimeServices(sessionService, agentService);
    }

    @Override
    public boolean isHighRisk(String name, JsonNode args) {
        return delegate.isHighRisk(name, args);
    }

    @Override
    public ToolExecutionResult execute(String name, JsonNode args, String workspaceRoot, String sessionId, String callId, String messageId) {
        if (stubOutputs.containsKey(name)) {
            LinkedHashMap<String, Object> meta = new LinkedHashMap<>();
            meta.put("stubbed", Boolean.TRUE);
            return new ToolExecutionResult("completed", stubOutputs.get(name), null, meta, null, null, null, null, false);
        }
        return delegate.execute(name, args, workspaceRoot, sessionId, callId, messageId);
    }

    @Override
    public List<Map<String, Object>> toolDefinitions(String workspaceRoot, LiteIdeTools.CapabilitySnapshot snapshot) {
        return delegate.toolDefinitions(workspaceRoot, snapshot);
    }

    @Override
    public LiteIdeTools.CapabilitySnapshot refreshIdeCapabilities() {
        return delegate.refreshIdeCapabilities();
    }

    @Override
    public void setIdeContext(JsonNode ideContext) {
        delegate.setIdeContext(ideContext);
    }

    @Override
    public String readTodosJson(String workspaceRoot, String sessionId) {
        return delegate.readTodosJson(workspaceRoot, sessionId);
    }

    @Override
    public String todosAsListJson(String workspaceRoot) {
        return delegate.todosAsListJson(workspaceRoot);
    }
}
