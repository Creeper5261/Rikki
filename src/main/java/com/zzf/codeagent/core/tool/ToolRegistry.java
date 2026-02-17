package com.zzf.codeagent.core.tool;

import com.zzf.codeagent.agent.AgentInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;

/**
 * 工具注册表 (对齐 OpenCode ToolRegistry)
 */
@Component
public class ToolRegistry {
    private final Map<String, Tool> tools = new HashMap<>();

    @Autowired
    public ToolRegistry(List<Tool> toolList) {
        for (Tool tool : toolList) {
            tools.put(tool.getId(), tool);
        }
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Tool> getTools(String modelID, AgentInfo agent) {
        
        return new ArrayList<>(tools.values());
    }

    public Map<String, Tool> getAll() {
        return new HashMap<>(tools);
    }
}
