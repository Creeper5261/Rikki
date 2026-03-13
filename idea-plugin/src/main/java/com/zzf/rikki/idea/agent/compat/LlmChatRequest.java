package com.zzf.rikki.idea.agent.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class LlmChatRequest {
    private final String messageId;
    private final List<Map<String, Object>> messages;
    private final ModelCapabilities capabilities;
    private final List<Map<String, Object>> toolDefinitions;

    public LlmChatRequest(
            String messageId,
            List<Map<String, Object>> messages,
            ModelCapabilities capabilities,
            List<Map<String, Object>> toolDefinitions
    ) {
        this.messageId = messageId;
        this.messages = messages == null ? List.of() : new ArrayList<>(messages);
        this.capabilities = capabilities == null ? new ModelCapabilities() : capabilities;
        this.toolDefinitions = toolDefinitions == null ? List.of() : new ArrayList<>(toolDefinitions);
    }

    public String getMessageId() {
        return messageId;
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public ModelCapabilities getCapabilities() {
        return capabilities;
    }

    public List<Map<String, Object>> getToolDefinitions() {
        return toolDefinitions;
    }
}
