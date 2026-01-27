package com.zzf.codeagent.core.tool;

import com.zzf.codeagent.core.tool.ToolProtocol.ToolSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolRegistry {
    private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
    private final Map<String, String> defaultVersions = new LinkedHashMap<>();
    private final Map<String, List<ToolSpec>> specs = new LinkedHashMap<>();

    public void register(ToolHandler handler) {
        if (handler == null) {
            return;
        }
        ToolSpec spec = handler.spec();
        if (spec == null || spec.getName().isEmpty()) {
            return;
        }
        String key = key(spec.getName(), spec.getVersion());
        handlers.put(key, handler);
        defaultVersions.putIfAbsent(spec.getName(), spec.getVersion());
        List<ToolSpec> list = specs.getOrDefault(spec.getName(), new ArrayList<>());
        list.add(spec);
        specs.put(spec.getName(), list);
    }

    public ToolHandler get(String name, String version) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String v = resolveVersion(name, version);
        return handlers.get(key(name, v));
    }

    public String resolveVersion(String name, String version) {
        if (version != null && !version.isBlank()) {
            return version.trim();
        }
        return defaultVersions.getOrDefault(name, ToolProtocol.DEFAULT_VERSION);
    }

    public List<ToolSpec> listSpecs() {
        if (specs.isEmpty()) {
            return Collections.emptyList();
        }
        List<ToolSpec> out = new ArrayList<>();
        for (List<ToolSpec> list : specs.values()) {
            out.addAll(list);
        }
        return out;
    }

    private static String key(String name, String version) {
        String n = name == null ? "" : name.trim();
        String v = version == null ? ToolProtocol.DEFAULT_VERSION : version.trim();
        return n + "@" + v;
    }
}
