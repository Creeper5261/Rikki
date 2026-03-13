package com.zzf.rikki.core.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> toolList) {
        if (toolList == null) {
            return;
        }
        for (Tool tool : toolList) {
            if (tool != null) {
                tools.put(tool.getId(), tool);
            }
        }
    }

    public Optional<Tool> get(String id) {
        return Optional.ofNullable(tools.get(id));
    }

    public List<Tool> getAll() {
        return new ArrayList<>(tools.values());
    }
}