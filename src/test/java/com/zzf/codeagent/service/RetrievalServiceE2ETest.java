package com.zzf.codeagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.rag.search.CodeSearchHit;
import com.zzf.codeagent.core.rag.search.CodeSearchResponse;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetrievalServiceE2ETest {

    @TempDir
    Path tempDir;

    @Test
    public void testSearchFallbackToMemory() throws Exception {
        Path src = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Path javaFile = src.resolve("UserService.java");
        Files.writeString(javaFile, "package com.example;\npublic class UserService { public void login() {} }\n");

        RetrievalService service = new RetrievalService(new ObjectMapper(), new NoopEmbeddingService(), new ContextService());
        ReflectionTestUtils.setField(service, "esScheme", "http");
        ReflectionTestUtils.setField(service, "esHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "esPort", 1);
        ReflectionTestUtils.setField(service, "embeddingDimension", 8);

        Map<String, Object> req = new HashMap<String, Object>();
        req.put("query", "UserService");
        req.put("workspaceRoot", tempDir.toString());
        req.put("topK", 5);

        ResponseEntity<Map<String, Object>> resp = service.search(req);

        assertEquals(200, resp.getStatusCodeValue());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("memory", body.get("engine"));
        assertTrue(((Number) body.get("hits")).intValue() > 0);
    }

    @Test
    public void testSearchFallbackReturnsHitDetails() throws Exception {
        Path src = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Path userFile = src.resolve("UserService.java");
        Files.writeString(userFile, "package com.example;\npublic class UserService { public void login() {} }\n");
        Path authFile = src.resolve("AuthService.java");
        Files.writeString(authFile, "package com.example;\npublic class AuthService { public boolean authenticate() { return true; } }\n");

        RetrievalService service = new RetrievalService(new ObjectMapper(), new NoopEmbeddingService(), new ContextService());
        ReflectionTestUtils.setField(service, "esScheme", "http");
        ReflectionTestUtils.setField(service, "esHost", "127.0.0.1");
        ReflectionTestUtils.setField(service, "esPort", 1);
        ReflectionTestUtils.setField(service, "embeddingDimension", 8);

        Map<String, Object> req = new HashMap<String, Object>();
        req.put("query", "UserService login");
        req.put("workspaceRoot", tempDir.toString());
        req.put("topK", 3);

        ResponseEntity<Map<String, Object>> resp = service.search(req);

        assertEquals(200, resp.getStatusCodeValue());
        Map<String, Object> body = resp.getBody();
        assertNotNull(body);
        assertEquals("memory", body.get("engine"));
        Object result = body.get("result");
        assertTrue(result instanceof CodeSearchResponse);
        CodeSearchResponse response = (CodeSearchResponse) result;
        assertTrue(response.getHits().size() > 0);
        Optional<CodeSearchHit> userHit = response.getHits().stream()
                .filter(hit -> hit.getFilePath() != null && hit.getFilePath().contains("UserService.java"))
                .findFirst();
        assertTrue(userHit.isPresent());
        assertTrue(userHit.get().getSnippet().contains("UserService"));
        assertTrue(userHit.get().getStartLine() >= 1);
        assertTrue(userHit.get().getEndLine() >= userHit.get().getStartLine());
    }

    private static final class NoopEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[]{0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f};
        }
    }
}
