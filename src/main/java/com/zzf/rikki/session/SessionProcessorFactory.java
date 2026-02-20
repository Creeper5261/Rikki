package com.zzf.rikki.session;

import com.zzf.rikki.llm.LLMService;
import com.zzf.rikki.provider.ModelInfo;
import com.zzf.rikki.session.model.MessageV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.core.tool.ToolRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 会话处理器工厂
 */
@Component
@RequiredArgsConstructor
public class SessionProcessorFactory {

    private final SessionService sessionService;
    private final SessionStatus sessionStatus;
    private final LLMService llmService;
    private final ToolRegistry toolRegistry;
    private final ContextCompactionService compactionService;
    private final ObjectMapper objectMapper;

    public SessionProcessor create(MessageV2.Assistant assistantMessage, String sessionID, ModelInfo model) {
        return new SessionProcessor(
                assistantMessage,
                sessionID,
                model,
                sessionService,
                sessionStatus,
                llmService,
                toolRegistry,
                compactionService,
                objectMapper
        );
    }
}
