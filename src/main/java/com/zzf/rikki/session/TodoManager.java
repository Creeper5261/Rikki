package com.zzf.rikki.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.bus.AgentBus;
import com.zzf.rikki.session.model.TodoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Todo 管理服务（workspace 级别，跨对话持久化）
 * 存储路径：{workspaceRoot}/.code-agent/todos.json
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoManager {

    private final AgentBus agentBus;
    private final ObjectMapper objectMapper;

    /** 内存缓存：workspaceRoot → todos */
    private final Map<String, List<TodoInfo>> cache = new ConcurrentHashMap<>();

    // ── 公共 API ──────────────────────────────────────────────────────

    /** 写入 todos（agent 调用 todo_write 时触发） */
    public void update(String workspaceRoot, List<TodoInfo> todos) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) return;
        String key = normalize(workspaceRoot);
        List<TodoInfo> safe = todos != null ? new ArrayList<>(todos) : new ArrayList<>();
        cache.put(key, safe);
        persist(workspaceRoot, safe);
        agentBus.publish("todo.updated", Map.of(
                "workspaceRoot", workspaceRoot,
                "todos", safe
        ));
    }

    /** 读取 todos（先走缓存，缓存 miss 时从文件加载） */
    public List<TodoInfo> get(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) return new ArrayList<>();
        String key = normalize(workspaceRoot);
        return cache.computeIfAbsent(key, k -> load(workspaceRoot));
    }

    // ── 持久化 ────────────────────────────────────────────────────────

    private void persist(String workspaceRoot, List<TodoInfo> todos) {
        try {
            Path dir = todoDir(workspaceRoot);
            Files.createDirectories(dir);
            File file = dir.resolve("todos.json").toFile();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, todos);
        } catch (Exception e) {
            log.warn("Failed to persist todos for workspace {}: {}", workspaceRoot, e.getMessage());
        }
    }

    private List<TodoInfo> load(String workspaceRoot) {
        try {
            File file = todoDir(workspaceRoot).resolve("todos.json").toFile();
            if (!file.exists()) return new ArrayList<>();
            return objectMapper.readValue(file, new TypeReference<List<TodoInfo>>() {});
        } catch (Exception e) {
            log.warn("Failed to load todos for workspace {}: {}", workspaceRoot, e.getMessage());
            return new ArrayList<>();
        }
    }

    private Path todoDir(String workspaceRoot) {
        return Paths.get(workspaceRoot, ".code-agent");
    }

    private String normalize(String workspaceRoot) {
        return Paths.get(workspaceRoot).toAbsolutePath().normalize().toString();
    }
}
