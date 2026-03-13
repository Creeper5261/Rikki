package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.rikki.agent.AgentInfo;
import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.session.PromptTextLoader;
import com.zzf.rikki.session.SessionInfo;
import com.zzf.rikki.session.SessionService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class TaskTool implements Tool {
    private final AgentService agentService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public TaskTool(AgentService agentService, SessionService sessionService, ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() {
        return "task";
    }

    @Override
    public String getDescription() {
        return PromptTextLoader.loadToolPrompt("task");
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("description").put("type", "string").put("description", "A short description of the task.");
        properties.putObject("prompt").put("type", "string").put("description", "The task the subagent should perform.");
        properties.putObject("subagent_type").put("type", "string").put("description", "The subagent type to use.");
        properties.putObject("session_id").put("type", "string").put("description", "Optional existing sub-session id to continue.");
        schema.putArray("required").add("description").add("prompt").add("subagent_type");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            String description = args.path("description").asText("").trim();
            String prompt = args.path("prompt").asText("").trim();
            String subagentType = args.path("subagent_type").asText("").trim();
            String existingSessionId = args.path("session_id").asText("").trim();
            if (description.isBlank() || prompt.isBlank() || subagentType.isBlank()) {
                throw new IllegalArgumentException("description, prompt, and subagent_type are required");
            }

            AgentInfo agent = agentService.get(subagentType)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown agent type: " + subagentType));

            SessionInfo parent = sessionService.get(ctx.getSessionID());
            SessionInfo subSession = null;
            if (!existingSessionId.isBlank()) {
                subSession = sessionService.get(existingSessionId);
            }
            if (subSession == null) {
                String workspaceRoot = parent == null ? String.valueOf(ctx.getExtra().getOrDefault("workspaceRoot", "")) : parent.workspaceRoot;
                subSession = sessionService.createSubSession(ctx.getSessionID(), workspaceRoot, description, agent.getName());
            }
            if (sessionService.getMessages(subSession.id).isEmpty()) {
                sessionService.addUserMessage(subSession.id, prompt);
            }

            Map<String, Object> subtaskPart = new LinkedHashMap<>();
            subtaskPart.put("type", "subtask");
            subtaskPart.put("id", nextId("part"));
            subtaskPart.put("sessionID", ctx.getSessionID());
            subtaskPart.put("messageID", ctx.getMessageID());
            subtaskPart.put("prompt", prompt);
            subtaskPart.put("description", description);
            subtaskPart.put("agent", agent.getName());
            subtaskPart.put("metadata", Map.of("sessionId", subSession.id));

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sessionId", subSession.id);
            metadata.put("subagent", agent.getName());
            metadata.put("emitted_parts", List.of(subtaskPart));

            ctx.metadata(description, metadata);
            String output = "Task delegated to @" + agent.getName() + ".\n\n<task_metadata>\nsession_id: " + subSession.id + "\n</task_metadata>";
            return new Result(description, metadata, output, List.of());
        });
    }

    private String nextId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
