package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.session.TodoManager;
import com.zzf.codeagent.session.model.TodoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * TodoReadTool — 读取当前 workspace 的跨对话 Todo 列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoReadTool implements Tool {

    private final TodoManager todoManager;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    @Override
    public String getId() {
        return "todo_read";
    }

    @Override
    public String getDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/todoread.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load todo_read description", e);
        }
        return "Use this tool to read the current to-do list for the workspace.";
    }

    @Override
    public JsonNode getParametersSchema() {
        return objectMapper.createObjectNode().put("type", "object");
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            String workspaceRoot = ToolPathResolver.resolveWorkspaceRoot(null, ctx);
            List<TodoInfo> todos = todoManager.get(workspaceRoot);
            try {
                String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(todos);
                return Result.builder()
                        .output(todos.isEmpty() ? "No todos found." : json)
                        .build();
            } catch (Exception e) {
                log.error("Failed to serialize todos", e);
                return Result.builder().output("[]").build();
            }
        });
    }
}
