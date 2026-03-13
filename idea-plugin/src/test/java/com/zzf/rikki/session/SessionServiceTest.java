package com.zzf.rikki.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.session.model.MessageV2;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class SessionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void toLlmMessages_shouldIncludeAssistantToolCallsAndToolResults() {
        SessionService service = new SessionService(mapper);
        SessionInfo session = service.getOrCreate("session-1", "D:/Projects/Rikki");
        service.addUserMessage(session.id, "run tests");
        MessageV2.WithParts assistant = service.startAssistantMessage(session.id, "OPENAI", "gpt-4o");

        MessageV2.ToolPart part = new MessageV2.ToolPart();
        part.id = "part-tool";
        part.sessionID = session.id;
        part.messageID = assistant.info.id;
        part.callID = "call-1";
        part.tool = "bash";
        part.args = new LinkedHashMap<>(Map.of("command", "npm test"));
        part.state = new MessageV2.ToolState();
        part.state.status = "completed";
        part.state.input = new LinkedHashMap<>(Map.of("command", "npm test"));
        part.state.output = "tests passed";
        part.state.title = "bash";
        part.state.time = new MessageV2.ToolState.TimeInfo();
        part.state.time.start = 1L;
        part.state.time.end = 2L;
        service.updatePart(part);

        assistant.info.finish = Boolean.TRUE;
        assistant.info.finishReason = "tool-calls";
        service.updateMessage(assistant);

        List<Map<String, Object>> messages = service.toLlmMessages(session.id, "system prompt", "system");

        assertEquals("system", messages.get(0).get("role"));
        assertEquals("user", messages.get(1).get("role"));
        assertEquals("assistant", messages.get(2).get("role"));
        assertEquals("tool", messages.get(3).get("role"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) messages.get(2).get("tool_calls");
        assertEquals("bash", ((Map<?, ?>) toolCalls.get(0).get("function")).get("name"));
        assertEquals("tests passed", messages.get(3).get("content"));
    }

    @Test
    void toLlmMessages_shouldIncludeReasoningContentForReasonerModels() {
        SessionService service = new SessionService(mapper);
        SessionInfo session = service.getOrCreate("session-reasoner", "D:/Projects/Rikki");
        service.addUserMessage(session.id, "run tests");
        MessageV2.WithParts assistant = service.startAssistantMessage(session.id, "DEEPSEEK", "deepseek-reasoner");

        MessageV2.ReasoningPart reasoning = new MessageV2.ReasoningPart();
        reasoning.id = "part-reasoning";
        reasoning.sessionID = session.id;
        reasoning.messageID = assistant.info.id;
        reasoning.text = "Need to inspect test output before calling bash.";
        reasoning.delta = reasoning.text;
        reasoning.time.start = 1L;
        reasoning.time.end = 2L;
        service.updatePart(reasoning);

        MessageV2.ToolPart part = new MessageV2.ToolPart();
        part.id = "part-tool";
        part.sessionID = session.id;
        part.messageID = assistant.info.id;
        part.callID = "call-1";
        part.tool = "bash";
        part.args = new LinkedHashMap<>(Map.of("command", "npm test"));
        part.state = new MessageV2.ToolState();
        part.state.status = "completed";
        part.state.input = new LinkedHashMap<>(Map.of("command", "npm test"));
        part.state.output = "tests passed";
        part.state.title = "bash";
        part.state.time = new MessageV2.ToolState.TimeInfo();
        part.state.time.start = 1L;
        part.state.time.end = 2L;
        service.updatePart(part);

        assistant.info.finish = Boolean.TRUE;
        assistant.info.finishReason = "tool-calls";
        service.updateMessage(assistant);

        List<Map<String, Object>> messages = service.toLlmMessages(
                session.id,
                "system prompt",
                "system",
                true
        );

        assertEquals("assistant", messages.get(2).get("role"));
        assertEquals("Need to inspect test output before calling bash.", messages.get(2).get("reasoning_content"));
    }

    @Test
    void importHistory_shouldRestoreStructuredParts() throws Exception {
        SessionService service = new SessionService(mapper);
        SessionInfo session = service.getOrCreate("session-2", "D:/Projects/Rikki");
        service.importHistory(session.id, mapper.readTree("""
                [
                  {
                    "role": "assistant",
                    "messageID": "msg-structured",
                    "parts": [
                      {"type": "text", "id": "part-text", "text": "hello"},
                      {"type": "reasoning", "id": "part-reasoning", "text": "thinking"},
                      {
                        "type": "tool",
                        "id": "part-tool",
                        "callID": "call-1",
                        "tool": "bash",
                        "args": {"command": "npm test"},
                        "state": {"status": "completed", "output": "ok", "title": "bash"}
                      }
                    ]
                  }
                ]
                """));

        MessageV2.WithParts message = service.getMessages(session.id).get(0);
        assertEquals("msg-structured", message.info.id);
        assertEquals(3, message.parts.size());
        assertInstanceOf(MessageV2.TextPart.class, message.parts.get(0));
        assertInstanceOf(MessageV2.ReasoningPart.class, message.parts.get(1));
        assertInstanceOf(MessageV2.ToolPart.class, message.parts.get(2));
        assertEquals("ok", ((MessageV2.ToolPart) message.parts.get(2)).state.output);
    }
}
