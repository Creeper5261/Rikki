package com.zzf.rikki.llm;

import com.zzf.rikki.idea.agent.compat.LlmChatRequest;
import com.zzf.rikki.idea.agent.compat.LlmStreamListener;
import com.zzf.rikki.idea.agent.compat.LlmStreamResult;
import com.zzf.rikki.idea.agent.compat.ModelCapabilities;
import com.zzf.rikki.runtime.port.LlmPort;

import java.util.List;
import java.util.Map;

public class LLMService {
    private final LlmPort llmPort;

    public LLMService(LlmPort llmPort) {
        this.llmPort = llmPort;
    }

    public LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener) {
        return llmPort.streamChat(request, listener);
    }

    public String completeText(String messageId, List<Map<String, Object>> messages, ModelCapabilities capabilities) {
        StringBuilder buffer = new StringBuilder();
        LlmStreamResult result = llmPort.streamChat(
                new LlmChatRequest(messageId, cast(messages), capabilities, List.of()),
                new LlmStreamListener() {
                    @Override
                    public void onMessageDelta(String streamedMessageId, String delta) {
                        if (delta != null) {
                            buffer.append(delta);
                        }
                    }

                    @Override
                    public void onThoughtDelta(String streamedMessageId, String delta) {
                    }

                    @Override
                    public void onThoughtEnd(String streamedMessageId) {
                    }
                }
        );
        if (buffer.length() > 0) {
            return buffer.toString();
        }
        return result == null || result.getText() == null ? "" : result.getText();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<Map<String, Object>> cast(List<Map<String, Object>> messages) {
        return (List) messages;
    }
}