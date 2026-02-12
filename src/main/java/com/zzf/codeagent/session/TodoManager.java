package com.zzf.codeagent.session;

import com.zzf.codeagent.bus.AgentBus;
import com.zzf.codeagent.session.model.TodoInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Todo 管理服务 (对齐 OpenCode Todo namespace)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TodoManager {

    private final AgentBus agentBus;
    private final Map<String, List<TodoInfo>> storage = new ConcurrentHashMap<>();

    public void update(String sessionID, List<TodoInfo> todos) {
        storage.put(sessionID, todos);
        agentBus.publish("todo.updated", Map.of("sessionID", sessionID, "todos", todos));
    }

    public List<TodoInfo> get(String sessionID) {
        return storage.getOrDefault(sessionID, new ArrayList<>());
    }
}
