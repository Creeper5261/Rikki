package com.zzf.rikki.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.rikki.idea.agent.compat.ModelCapabilities;
import com.zzf.rikki.idea.agent.tools.LiteIdeTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("runtime")
class SystemPromptTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void build_should_compose_session_and_runtime_templates() throws Exception {
        Path workspace = Files.createTempDirectory("rikki-system-prompt");
        Files.writeString(workspace.resolve("README.md"), "hello");

        ObjectNode ideContext = mapper.createObjectNode();
        ideContext.put("project", "Rikki");
        ideContext.put("sdk", "17");

        String prompt = new SystemPrompt(mapper).build(
                workspace.toString(),
                ideContext,
                new ModelCapabilities("system", null, "max_tokens", false, false),
                new LiteIdeTools.CapabilitySnapshot(true, List.of("run", "test")),
                "gpt-4o"
        );

        assertTrue(prompt.contains("<plugin-runtime>"));
        assertTrue(prompt.contains("Working directory: " + workspace));
        assertTrue(prompt.contains("IDE bridge available: true"));
        assertTrue(prompt.contains("IDE actions available: run, test"));
        assertTrue(prompt.contains("This model does not support tool calls; answer without executing tools."));
        assertTrue(prompt.contains("<ide_context>"));
        assertTrue(prompt.contains("\"project\" : \"Rikki\""));
        assertTrue(prompt.contains("<env>"));
        assertTrue(prompt.contains("<files>"));
        assertTrue(prompt.contains("README.md"));
        assertFalse(prompt.contains("{{workspaceRoot}}"));
        assertFalse(prompt.contains("{{ideActionsLine}}"));
        assertFalse(prompt.contains("{{toolSupportLine}}"));
    }
}
