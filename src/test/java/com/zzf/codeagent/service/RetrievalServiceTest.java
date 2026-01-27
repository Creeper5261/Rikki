package com.zzf.codeagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class RetrievalServiceTest {

    @Test
    public void testSearchRejectsNullBody() {
        RetrievalService service = new RetrievalService(new ObjectMapper(), new NoopEmbeddingService(), new ContextService());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.search(null));

        assertEquals(400, ex.getStatus().value());
        assertEquals("body is required", ex.getReason());
    }

    @Test
    public void testSearchRejectsBlankQuery() {
        RetrievalService service = new RetrievalService(new ObjectMapper(), new NoopEmbeddingService(), new ContextService());
        Map<String, Object> req = new HashMap<String, Object>();
        req.put("query", "   ");
        req.put("workspaceRoot", "C:\\tmp");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.search(req));

        assertEquals(400, ex.getStatus().value());
        assertEquals("query is blank", ex.getReason());
    }

    @Test
    public void testSearchRejectsBlankWorkspaceRoot() {
        RetrievalService service = new RetrievalService(new ObjectMapper(), new NoopEmbeddingService(), new ContextService());
        Map<String, Object> req = new HashMap<String, Object>();
        req.put("query", "find user");
        req.put("workspaceRoot", " ");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.search(req));

        assertEquals(400, ex.getStatus().value());
        assertEquals("workspaceRoot is blank", ex.getReason());
    }

    private static final class NoopEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[]{0.1f, 0.2f};
        }
    }
}
