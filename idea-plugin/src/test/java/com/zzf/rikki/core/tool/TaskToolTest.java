package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.session.SessionInfo;
import com.zzf.rikki.session.SessionService;
import com.zzf.rikki.session.model.MessageV2;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskToolTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void execute_should_create_subsession_and_emit_subtask_metadata() throws Exception {
        SessionService sessionService = new SessionService(mapper);
        AgentService agentService = new AgentService();
        SessionInfo parent = sessionService.getOrCreate("session-parent", "D:/workspace");
        TaskTool tool = new TaskTool(agentService, sessionService, mapper);

        Tool.Context context = Tool.Context.basic(parent.id, "message-parent", "call-task");
        context.setExtra(Map.of("workspaceRoot", parent.workspaceRoot));

        JsonNode args = mapper.readTree("""
                {
                  "description": "Inspect the build configuration",
                  "prompt": "Review gradle setup and report issues",
                  "subagent_type": "general"
                }
                """);

        Tool.Result result = tool.execute(args, context).get();

        assertTrue(result.getOutput().contains("Task delegated to @general"));
        Object sessionIdRaw = result.getMetadata().get("sessionId");
        assertNotNull(sessionIdRaw);
        String subSessionId = String.valueOf(sessionIdRaw);

        SessionInfo subSession = sessionService.get(subSessionId);
        assertNotNull(subSession);
        assertEquals(parent.id, subSession.parentSessionId);
        assertEquals("Inspect the build configuration", subSession.title);
        assertEquals("general", subSession.agentName);

        List<MessageV2.WithParts> subMessages = sessionService.getMessages(subSessionId);
        assertFalse(subMessages.isEmpty());
        assertEquals("user", subMessages.get(0).info.role);
        assertEquals("Review gradle setup and report issues", subMessages.get(0).textContent());

        Object emittedParts = result.getMetadata().get("emitted_parts");
        assertTrue(emittedParts instanceof List<?>);
        List<?> rawParts = (List<?>) emittedParts;
        assertEquals(1, rawParts.size());
        assertTrue(rawParts.get(0) instanceof Map<?, ?>);
        Map<?, ?> rawPart = (Map<?, ?>) rawParts.get(0);
        assertEquals("subtask", rawPart.get("type"));
        assertEquals("general", rawPart.get("agent"));
    }
}
