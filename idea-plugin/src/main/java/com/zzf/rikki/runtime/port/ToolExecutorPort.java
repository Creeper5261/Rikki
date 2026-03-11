package com.zzf.rikki.runtime.port;

import com.fasterxml.jackson.databind.JsonNode;
import com.zzf.rikki.idea.agent.compat.ToolExecutionResult;
import com.zzf.rikki.idea.agent.tools.LiteIdeTools;

import java.util.List;
import java.util.Map;

public interface ToolExecutorPort {
    boolean isHighRisk(String name, JsonNode args);

    ToolExecutionResult execute(String name, JsonNode args, String workspaceRoot, String sessionId, String callId, String messageId);

    List<Map<String, Object>> toolDefinitions(String workspaceRoot, LiteIdeTools.CapabilitySnapshot snapshot);

    LiteIdeTools.CapabilitySnapshot refreshIdeCapabilities();

    void setIdeContext(JsonNode ideContext);

    String readTodosJson(String workspaceRoot, String sessionId);

    String todosAsListJson(String workspaceRoot);
}
