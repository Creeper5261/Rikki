package com.zzf.rikki.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.idea.agent.compat.LlmChatRequest;
import com.zzf.rikki.idea.agent.compat.LlmStreamListener;
import com.zzf.rikki.idea.agent.compat.LlmStreamResult;
import com.zzf.rikki.idea.agent.compat.ModelCapabilities;
import com.zzf.rikki.llm.LLMService;
import com.zzf.rikki.runtime.RuntimeAgentConfig;
import com.zzf.rikki.runtime.port.LlmPort;
import com.zzf.rikki.session.model.MessageV2;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactionServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void compact_should_append_summary_message_and_synthetic_continue_prompt() {
        SessionService sessionService = new SessionService(mapper);
        SessionInfo session = sessionService.getOrCreate("session-compact", "D:/workspace");

        sessionService.addUserMessage(session.id, "Please investigate the failing integration tests.");
        MessageV2.WithParts assistant = sessionService.startAssistantMessage(session.id, "OPENAI", "gpt-4o");
        MessageV2.TextPart assistantText = new MessageV2.TextPart();
        assistantText.id = "part-assistant";
        assistantText.sessionID = session.id;
        assistantText.messageID = assistant.info.id;
        assistantText.text = "I inspected the Gradle configuration and found a missing repository.";
        assistantText.delta = assistantText.text;
        assistantText.time.start = System.currentTimeMillis();
        assistantText.time.end = assistantText.time.start;
        sessionService.updatePart(assistantText);
        assistant.info.finish = Boolean.TRUE;
        assistant.info.finishReason = "stop";
        sessionService.updateMessage(assistant);

        LLMService llmService = new LLMService(new LlmPort() {
            @Override
            public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
                return new LlmStreamResult("Compacted summary for the next step.", List.of(), "");
            }
        });
        ContextCompactionService compactionService = new ContextCompactionService(sessionService, llmService, new AgentService());

        boolean compacted = compactionService.compact(
                session.id,
                new ModelCapabilities(),
                new RuntimeAgentConfig("OPENAI", "gpt-4o", "https://api.openai.com/v1", "token", "", "", null, true)
        );

        assertTrue(compacted);
        List<MessageV2.WithParts> messages = sessionService.getMessages(session.id);
        assertEquals(4, messages.size());

        MessageV2.WithParts summary = messages.get(2);
        assertEquals("assistant", summary.info.role);
        assertTrue(Boolean.TRUE.equals(summary.info.summary));
        assertEquals("compaction", summary.info.finishReason);
        assertEquals("Compacted summary for the next step.", summary.textContent());

        MessageV2.WithParts syntheticContinue = messages.get(3);
        assertEquals("user", syntheticContinue.info.role);
        assertEquals("Continue if you have next steps", syntheticContinue.textContent());
        assertFalse(syntheticContinue.parts.isEmpty());
        assertNotNull(syntheticContinue.parts.get(0));
        assertTrue(syntheticContinue.parts.get(0) instanceof MessageV2.TextPart);
        assertTrue(((MessageV2.TextPart) syntheticContinue.parts.get(0)).synthetic);
    }
}
