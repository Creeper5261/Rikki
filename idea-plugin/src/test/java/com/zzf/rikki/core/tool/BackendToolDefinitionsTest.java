package com.zzf.rikki.core.tool;

import com.zzf.rikki.idea.agent.tools.LiteIdeTools;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendToolDefinitionsTest {

    @Test
    void includesWebSearchAndCodeSearchDefinitions() {
        LiteIdeTools.CapabilitySnapshot snapshot = new LiteIdeTools.CapabilitySnapshot(false, List.of());

        List<Map<String, Object>> defs = BackendToolDefinitions.build("D:/workspace", snapshot);

        assertTrue(hasTool(defs, "web_search"));
        assertTrue(hasTool(defs, "search_codebase"));
    }

    private boolean hasTool(List<Map<String, Object>> defs, String toolName) {
        return defs.stream().anyMatch(def -> {
            Object fn = def.get("function");
            if (!(fn instanceof Map<?, ?> function)) {
                return false;
            }
            return toolName.equals(function.get("name"));
        });
    }
}