package com.zzf.rikki.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.idea.agent.LiteSseWriter;
import com.zzf.rikki.idea.agent.compat.RuntimeEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiteSseWriterTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void emit_shouldPreserveLiteSseContractAndPendingCommandMeta() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        LiteSseWriter writer = new LiteSseWriter(output);

        writer.emit(new RuntimeEvent.SessionBound("session-1"));
        writer.emit(new RuntimeEvent.StatusChanged("busy", "Agent is thinking..."));
        writer.emit(new RuntimeEvent.MessageDelta("msg-1", "hello"));
        writer.emit(new RuntimeEvent.MessageSnapshot("msg-1", "hello world"));
        writer.emit(new RuntimeEvent.ThoughtDelta("msg-1", "reasoning"));
        writer.emit(new RuntimeEvent.ThoughtEnd("msg-1"));
        writer.emit(new RuntimeEvent.ToolCall(
                "part-1",
                "bash",
                "call-1",
                "msg-1",
                "pending",
                "bash",
                Map.of("command", "npm test"),
                Map.of(
                        "pending_command", Map.of("id", "pc-1", "command", "npm test"),
                        "approval_options", List.of("manual", "whitelist"),
                        "requires_explicit_user_consent", true
                )
        ));
        writer.emit(new RuntimeEvent.ToolPendingApproval("part-1", "call-1", "npm test", "bash"));
        writer.emit(new RuntimeEvent.ToolResult(
                "part-1",
                "bash",
                "call-1",
                "msg-1",
                "completed",
                "bash",
                "ok",
                null,
                null
        ));
        writer.emit(new RuntimeEvent.TodoUpdated("[{\"content\":\"task\",\"status\":\"pending\"}]", "session-1"));
        writer.emit(new RuntimeEvent.Finished("session-1", "msg-1", "done"));
        writer.emit(new RuntimeEvent.Errored("boom"));

        List<Map.Entry<String, String>> captured = new ArrayList<>();
        new ChatSseAdapter().consume(output.toString(StandardCharsets.UTF_8).lines().toList(), (event, data) ->
                captured.add(Map.entry(event, data))
        );

        assertEquals(
                List.of(
                        "session",
                        "status",
                        "message",
                        "message_part",
                        "thought",
                        "thought_end",
                        "tool_call",
                        "tool_confirm",
                        "tool_result",
                        "todo_updated",
                        "finish",
                        "error"
                ),
                captured.stream().map(Map.Entry::getKey).toList()
        );

        JsonNode toolCall = mapper.readTree(captured.get(6).getValue());
        assertEquals("pc-1", toolCall.path("meta").path("pending_command").path("id").asText());
        assertTrue(toolCall.path("meta").path("requires_explicit_user_consent").asBoolean());

        JsonNode finish = mapper.readTree(captured.get(10).getValue());
        assertEquals("session-1", finish.path("sessionID").asText());
        assertEquals("done", finish.path("answer").asText());
    }
}
