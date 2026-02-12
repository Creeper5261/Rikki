package com.zzf.codeagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.config.ConfigManager;
import com.zzf.codeagent.provider.ProviderManager;
import com.zzf.codeagent.session.model.MessageV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class LLMServiceMessageConversionTest {

    private LLMService service;

    @BeforeEach
    void setUp() {
        ProviderManager providerManager = mock(ProviderManager.class);
        ConfigManager configManager = mock(ConfigManager.class);
        service = new LLMService(providerManager, configManager, new ObjectMapper());
    }

    @Test
    void toolResultMustFollowAssistantToolCall() throws Exception {
        MessageV2.MessageInfo assistantInfo = new MessageV2.MessageInfo();
        assistantInfo.setId("message_1");
        assistantInfo.setSessionID("session_1");
        assistantInfo.setRole("assistant");

        MessageV2.ToolState state = new MessageV2.ToolState();
        state.setStatus("completed");
        state.setInput(Map.of("filePath", "src/Main.java"));
        state.setOutput("ok");

        MessageV2.ToolPart toolPart = new MessageV2.ToolPart();
        toolPart.setId("part_1");
        toolPart.setSessionID("session_1");
        toolPart.setMessageID("message_1");
        toolPart.setType("tool");
        toolPart.setCallID("call_1");
        toolPart.setTool("write");
        toolPart.setState(state);

        MessageV2.WithParts assistantMsg = new MessageV2.WithParts(assistantInfo, List.of(toolPart));

        List<Map<String, Object>> payload = invokeConvertMessages(List.of(assistantMsg), List.of("sys"));

        assertEquals("system", payload.get(0).get("role"));
        assertEquals("assistant", payload.get(1).get("role"));
        assertTrue(payload.get(1).containsKey("tool_calls"));
        assertEquals("tool", payload.get(2).get("role"));
        assertEquals("call_1", payload.get(2).get("tool_call_id"));
        assertEquals("ok", payload.get(2).get("content"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> invokeConvertMessages(
            List<MessageV2.WithParts> messages,
            List<String> systemInstructions
    ) throws Exception {
        Method method = LLMService.class.getDeclaredMethod("convertMessages", List.class, List.class);
        method.setAccessible(true);
        return (List<Map<String, Object>>) method.invoke(service, messages, systemInstructions);
    }
}
