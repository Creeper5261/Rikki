package com.zzf.codeagent.session;

import com.zzf.codeagent.llm.LLMService;
import com.zzf.codeagent.provider.ModelInfo;
import com.zzf.codeagent.session.model.MessageV2;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.tool.ToolRegistry;
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
