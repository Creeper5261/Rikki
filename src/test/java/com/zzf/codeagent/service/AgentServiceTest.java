package com.zzf.codeagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.api.AgentChatController.ChatRequest;
import com.zzf.codeagent.api.AgentChatController.CompressHistoryRequest;
import com.zzf.codeagent.core.rag.pipeline.IndexingWorker;
import com.zzf.codeagent.core.rag.search.HybridCodeSearchService;
import com.zzf.codeagent.core.runtime.RuntimeService;
import com.zzf.codeagent.core.tool.ToolExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AgentServiceTest {

    @Test
    public void testChatRejectsBlankGoal() {
        AgentService service = new AgentService(
                new ObjectMapper(),
                mock(HybridCodeSearchService.class),
                mock(IndexingWorker.class),
                mock(ToolExecutionService.class),
                mock(RuntimeService.class),
                mock(RetrievalService.class),
                mock(ContextService.class)
        );
        ChatRequest req = new ChatRequest();
        req.goal = "   ";

        ResponseEntity<?> resp = service.chat(req);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        Object body = resp.getBody();
        assertNotNull(body);
        assertEquals("goal is blank", ((com.zzf.codeagent.api.AgentChatController.ChatResponse) body).answer);
    }

    @Test
    public void testChatRejectsMissingApiKey() {
        ContextService contextService = mock(ContextService.class);
        when(contextService.resolveDeepSeekApiKey()).thenReturn("");
        AgentService service = new AgentService(
                new ObjectMapper(),
                mock(HybridCodeSearchService.class),
                mock(IndexingWorker.class),
                mock(ToolExecutionService.class),
                mock(RuntimeService.class),
                mock(RetrievalService.class),
                contextService
        );
        ChatRequest req = new ChatRequest();
        req.goal = "explain architecture";
        req.workspaceRoot = "C:\\tmp";
        req.history = List.of("hi");

        ResponseEntity<?> resp = service.chat(req);

        assertEquals(HttpStatus.NOT_IMPLEMENTED, resp.getStatusCode());
        Object body = resp.getBody();
        assertNotNull(body);
        assertEquals("deepseek api-key not set", ((com.zzf.codeagent.api.AgentChatController.ChatResponse) body).answer);
    }

    @Test
    public void testCompressHistoryRejectsMissingApiKey() {
        ContextService contextService = mock(ContextService.class);
        when(contextService.resolveDeepSeekApiKey()).thenReturn(null);
        AgentService service = new AgentService(
                new ObjectMapper(),
                mock(HybridCodeSearchService.class),
                mock(IndexingWorker.class),
                mock(ToolExecutionService.class),
                mock(RuntimeService.class),
                mock(RetrievalService.class),
                contextService
        );
        CompressHistoryRequest req = new CompressHistoryRequest();
        req.history = List.of("hello");
        req.goalHint = "summary";

        ResponseEntity<Map<String, Object>> resp = service.compressHistory(req);

        assertEquals(HttpStatus.NOT_IMPLEMENTED, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("deepseek api-key not set", body.get("error"));
    }
}
