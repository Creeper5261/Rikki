package com.zzf.codeagent.core.rag.search;

import com.zzf.codeagent.core.rag.code.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InMemoryCodeSearchServiceTest {

    @Test
    public void testSearchRanksByTokenMatchesAndTruncates() {
        InMemoryCodeSearchService service = new InMemoryCodeSearchService();
        CodeChunk best = new CodeChunk("1", "java", "src/AuthService.java", "class", "AuthService", "AuthService", 1, 3, "public void loginUser() {}");
        CodeChunk second = new CodeChunk("2", "java", "src/UserService.java", "class", "UserService", "UserService", 1, 3, "class UserService {}");
        service.upsertAll(List.of(best, second));

        CodeSearchResponse resp = service.search(new CodeSearchQuery("login user", 2, 10));

        assertEquals(2, resp.getHits().size());
        assertEquals("src/AuthService.java", resp.getHits().get(0).getFilePath());
        assertEquals(10, resp.getHits().get(0).getSnippet().length());
        assertTrue(resp.getHits().get(0).isTruncated());
    }

    @Test
    public void testSearchReturnsEmptyWhenQueryBlank() {
        InMemoryCodeSearchService service = new InMemoryCodeSearchService();
        CodeChunk chunk = new CodeChunk("1", "java", "src/UserService.java", "class", "UserService", "UserService", 1, 3, "class UserService {}");
        service.upsertAll(List.of(chunk));

        CodeSearchResponse resp = service.search(new CodeSearchQuery("", 5, 100));

        assertTrue(resp.getHits().isEmpty());
    }
}
