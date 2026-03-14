package com.zzf.rikki.core.tool;

import com.zzf.rikki.idea.agent.tools.LiteIdeTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("runtime")
class BackendToolDefinitionsTest {

    @Test
    void includesActiveToolDefinitionsWithDescriptions() {
        LiteIdeTools.CapabilitySnapshot snapshot = new LiteIdeTools.CapabilitySnapshot(true, List.of("run", "test", "status", "cancel"));

        List<Map<String, Object>> defs = BackendToolDefinitions.build("D:/workspace", snapshot);

        for (String toolName : List.of(
                "bash", "read", "write", "edit", "delete_file", "glob", "grep", "ls",
                "task", "web_search", "search_codebase", "todo_read", "todo_write",
                "ide_context", "ide_action", "ide_capabilities"
        )) {
            assertTrue(hasTool(defs, toolName));
            assertFalse(descriptionFor(defs, toolName).isBlank(), "Expected description for " + toolName);
        }
    }

    private boolean hasTool(List<Map<String, Object>> defs, String toolName) {
        return !descriptionFor(defs, toolName).isBlank();
    }

    private String descriptionFor(List<Map<String, Object>> defs, String toolName) {
        return defs.stream().anyMatch(def -> {
            Object fn = def.get("function");
            if (!(fn instanceof Map<?, ?> function)) {
                return false;
            }
            return toolName.equals(function.get("name"));
        }) ? defs.stream()
                .map(def -> def.get("function"))
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(function -> toolName.equals(function.get("name")))
                .map(function -> String.valueOf(function.get("description")))
                .findFirst()
                .orElse("") : "";
    }
}
