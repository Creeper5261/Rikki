package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.rikki.session.TodoManager;
import com.zzf.rikki.session.model.TodoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * TodoWriteTool — 创建 / 更新当前 workspace 的跨对话 Todo 列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoWriteTool implements Tool {

    private final TodoManager todoManager;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Override
    public String getId() {
        return "todo_write";
    }

    @Override
    public String getDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/todowrite.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load todo_write description", e);
        }
        return "Use this tool to create and manage a structured task list that persists across conversations.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        ObjectNode todosArr = properties.putObject("todos");
        todosArr.put("type", "array");
        ObjectNode itemSchema = todosArr.putObject("items");
        itemSchema.put("type", "object");
        ObjectNode itemProps = itemSchema.putObject("properties");
        itemProps.putObject("id").put("type", "string");
        itemProps.putObject("content").put("type", "string");
        itemProps.putObject("status").put("type", "string");
        itemProps.putObject("priority").put("type", "string");
        itemSchema.putArray("required").add("content").add("status");

        schema.putArray("required").add("todos");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String workspaceRoot = ToolPathResolver.resolveWorkspaceRoot(null, ctx);

                List<TodoInfo> todos = new ArrayList<>();
                JsonNode todosNode = args.get("todos");
                if (todosNode != null && todosNode.isArray()) {
                    for (JsonNode item : todosNode) {
                        String id = item.has("id") && !item.get("id").asText("").isBlank()
                                ? item.get("id").asText()
                                : UUID.randomUUID().toString().substring(0, 8);
                        String content = item.path("content").asText("");
                        String status = item.path("status").asText("pending");
                        String priority = item.path("priority").asText("medium");
                        if (content.isBlank()) continue;
                        todos.add(TodoInfo.builder()
                                .id(id)
                                .content(content)
                                .status(status)
                                .priority(priority)
                                .build());
                    }
                }

                todoManager.update(workspaceRoot, todos);

                long pending = todos.stream().filter(t -> "pending".equals(t.getStatus())).count();
                long inProgress = todos.stream().filter(t -> "in_progress".equals(t.getStatus())).count();
                long completed = todos.stream().filter(t -> "completed".equals(t.getStatus())).count();

                return Result.builder()
                        .output(String.format("Todo list updated: %d total (%d pending, %d in_progress, %d completed)",
                                todos.size(), pending, inProgress, completed))
                        .build();
            } catch (Exception e) {
                log.error("Failed to write todos", e);
                throw new RuntimeException(e.getMessage());
            }
        });
    }
}
