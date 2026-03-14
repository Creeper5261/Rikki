package com.zzf.rikki.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.session.model.MessageV2;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("runtime")
class SessionAlignmentTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void instructionPrompt_should_resolve_workspace_specific_instructions_beyond_system_prompt() throws Exception {
        Path workspace = Files.createTempDirectory("rikki-instruction-root");
        Files.writeString(workspace.resolve("AGENTS.md"), "root instruction");
        Path moduleDir = Files.createDirectories(workspace.resolve("module"));
        Files.writeString(moduleDir.resolve("CONTEXT.md"), "module instruction");
        Path targetFile = Files.writeString(moduleDir.resolve("Demo.java"), "class Demo {}");

        InstructionPrompt prompt = new InstructionPrompt();
        List<InstructionPrompt.InstructionResult> resolved = prompt.resolve(List.of(), targetFile.toString(), workspace.toString());

        assertEquals(1, resolved.size());
        assertTrue(resolved.get(0).path.endsWith("CONTEXT.md"));
        assertTrue(resolved.get(0).content.contains("module instruction"));
    }

    @Test
    void promptReminderService_should_wrap_user_messages_after_last_finished_assistant() {
        SessionService service = new SessionService(mapper);
        SessionInfo session = service.getOrCreate("session-1", "D:/Projects/Rikki");
        MessageV2.WithParts firstUser = service.addUserMessage(session.id, "initial request");
        MessageV2.WithParts assistant = service.startAssistantMessage(session.id, "OPENAI", "gpt-4o");
        MessageV2.TextPart answerPart = new MessageV2.TextPart();
        answerPart.id = "part-answer";
        answerPart.sessionID = session.id;
        answerPart.messageID = assistant.info.id;
        answerPart.text = "done";
        answerPart.delta = "done";
        service.updatePart(answerPart);
        assistant.info.finish = Boolean.TRUE;
        assistant.info.finishReason = "stop";
        service.updateMessage(assistant);
        MessageV2.WithParts followUp = service.addUserMessage(session.id, "please also add tests");

        List<MessageV2.WithParts> history = service.copyMessages(service.getFilteredMessages(session.id));
        new PromptReminderService().wrapMidLoopUserMessages(history, assistant.info.id);

        assertEquals("initial request", history.get(0).textContent());
        assertTrue(history.get(2).textContent().contains("<system-reminder>"));
        assertTrue(history.get(2).textContent().contains("please also add tests"));
        assertNotNull(firstUser);
        assertNotNull(followUp);
    }

    @Test
    void importHistory_should_preserve_backend_style_parts_and_tool_results() throws Exception {
        SessionService service = new SessionService(mapper);
        SessionInfo session = service.getOrCreate("session-history", "D:/Projects/Rikki");

        JsonNode history = mapper.readTree("""
                [
                  {
                    "messageID": "msg-history-1",
                    "role": "assistant",
                    "created": 100,
                    "finish": true,
                    "finishReason": "tool-calls",
                    "parts": [
                      {
                        "id": "part-reasoning",
                        "type": "reasoning",
                        "text": "thinking",
                        "delta": "thinking",
                        "time": {"start": 100, "end": 101}
                      },
                      {
                        "id": "part-tool",
                        "type": "tool",
                        "callID": "call-1",
                        "tool": "read",
                        "args": {"filePath": "README.md"},
                        "state": {
                          "status": "completed",
                          "input": {"filePath": "README.md"},
                          "output": "file contents",
                          "title": "read README",
                          "metadata": {"loaded": ["README.md"]},
                          "time": {"start": 100, "end": 102}
                        }
                      }
                    ]
                  }
                ]
                """);

        service.importHistory(session.id, history);

        MessageV2.WithParts imported = service.getMessage("msg-history-1");
        assertNotNull(imported);
        assertEquals(2, imported.parts.size());
        assertEquals("reasoning", imported.parts.get(0).type);
        assertEquals("tool", imported.parts.get(1).type);

        List<Map<String, Object>> llmMessages = service.toLlmMessages(service.getFilteredMessages(session.id), "system prompt", "system");
        assertEquals("system", llmMessages.get(0).get("role"));
        assertEquals("assistant", llmMessages.get(1).get("role"));
        assertEquals("tool", llmMessages.get(2).get("role"));
        assertEquals("file contents", llmMessages.get(2).get("content"));

        InstructionPrompt prompt = new InstructionPrompt();
        MessageV2.ToolPart importedTool = (MessageV2.ToolPart) imported.parts.get(1);
        assertFalse(prompt.loaded(List.of(imported)).isEmpty());
        assertEquals("read", importedTool.tool);
    }
}
