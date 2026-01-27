package com.zzf.codeagent.core.pipeline;

import com.zzf.codeagent.core.rag.search.CodeSearchHit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContextRerankerTest {

    @Test
    public void testRerankPromotesSymbolAndSnippetMatches() {
        CodeSearchHit userHit = new CodeSearchHit("src/UserService.java", "class", "UserService", 1, 5, "public void login() {}", false);
        CodeSearchHit utilHit = new CodeSearchHit("src/Utils.java", "", "", 1, 3, "public void helper() {}", false);
        CodeSearchHit loginHit = new CodeSearchHit("src/AuthService.java", "class", "AuthService", 1, 5, "public boolean login() { return true; }", false);
        userHit.setScore(0.0);
        utilHit.setScore(1.0);
        loginHit.setScore(0.5);

        List<CodeSearchHit> candidates = new ArrayList<CodeSearchHit>();
        candidates.add(utilHit);
        candidates.add(loginHit);
        candidates.add(userHit);

        ContextReranker reranker = new ContextReranker();
        List<CodeSearchHit> sorted = reranker.rerank("UserService login", candidates);

        assertEquals(3, sorted.size());
        assertEquals("src/UserService.java", sorted.get(0).getFilePath());
        assertTrue(sorted.get(0).getScore() > sorted.get(1).getScore());
    }

    @Test
    public void testRerankHandlesEmptyCandidates() {
        ContextReranker reranker = new ContextReranker();
        List<CodeSearchHit> empty = new ArrayList<CodeSearchHit>();
        List<CodeSearchHit> out = reranker.rerank("anything", empty);
        assertSame(empty, out);
    }
}
