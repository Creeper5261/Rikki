package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.rikki.agent.AgentInfo;
import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.id.Identifier;
import com.zzf.rikki.session.SessionInfo;
import com.zzf.rikki.session.SessionService;
import com.zzf.rikki.session.model.MessageV2;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * TaskTool 实现 (对齐 opencode/src/tool/task.ts)
 * 用于启动子智能体任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskTool implements Tool {

    private final AgentService agentService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Override
    public String getId() {
        return "task";
    }

    @Override
    public String getDescription() {
        return loadDescription();
    }

    private String loadDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/task.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load task tool description", e);
        }
        return "A tool to delegate a task to a specialized subagent.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("description").put("type", "string").put("description", "A short (3-5 words) description of the task");
        properties.putObject("prompt").put("type", "string").put("description", "The task for the agent to perform");
        properties.putObject("subagent_type").put("type", "string").put("description", "The type of specialized agent to use for this task");
        properties.putObject("session_id").put("type", "string").put("description", "Existing Task session to continue");

        schema.putArray("required").add("description").add("prompt").add("subagent_type");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        String description = args.get("description").asText();
        String prompt = args.get("prompt").asText();
        String subagentType = args.get("subagent_type").asText();
        String existingSessionId = args.has("session_id") ? args.get("session_id").asText() : null;

        return CompletableFuture.supplyAsync(() -> {
            
            Map<String, Object> permissionRequest = new HashMap<>();
            permissionRequest.put("permission", "task");
            permissionRequest.put("patterns", new String[]{subagentType});
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("description", description);
            metadata.put("subagent_type", subagentType);
            permissionRequest.put("metadata", metadata);

            try {
                ctx.ask(permissionRequest).get();
            } catch (Exception e) {
                throw new RuntimeException("Permission denied for subtask", e);
            }

            
            AgentInfo agent = agentService.get(subagentType)
                    .orElseThrow(() -> new RuntimeException("Unknown agent type: " + subagentType));

            
            String parentDirectory = sessionService.get(ctx.getSessionID())
                    .map(SessionInfo::getDirectory)
                    .orElse(null);

            SessionInfo session;
            if (existingSessionId != null) {
                session = sessionService.get(existingSessionId)
                        .orElseGet(() -> sessionService.create(ctx.getSessionID(), description + " (@" + agent.getName() + " subagent)", parentDirectory));
            } else {
                session = sessionService.create(ctx.getSessionID(), description + " (@" + agent.getName() + " subagent)", parentDirectory);
            }

            
            Map<String, Object> meta = new HashMap<>();
            meta.put("sessionId", session.getId());
            ctx.metadata(description, meta);

            
            
            
            
            MessageV2.SubtaskPart subtaskPart = new MessageV2.SubtaskPart();
            subtaskPart.setId(Identifier.ascending("part"));
            subtaskPart.setSessionID(session.getId());
            subtaskPart.setMessageID(Identifier.ascending("message")); 
            subtaskPart.setType("subtask");
            subtaskPart.setPrompt(prompt);
            subtaskPart.setDescription(description);
            subtaskPart.setAgent(agent.getName());
            
            
            
            
            
            
            
            
            String output = "Task delegated to @" + agent.getName() + ".\n\n<task_metadata>\nsession_id: " + session.getId() + "\n</task_metadata>";

            return Result.builder()
                    .title(description)
                    .output(output)
                    .metadata(meta)
                    .build();
        });
    }
}
