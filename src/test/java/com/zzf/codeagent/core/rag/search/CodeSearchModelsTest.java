package com.zzf.codeagent.core.rag.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CodeSearchModelsTest {

    @Test
    public void testCodeSearchQueryFields() {
        CodeSearchQuery query = new CodeSearchQuery("find user", 7, 512);

        assertEquals("find user", query.getQuery());
        assertEquals(7, query.getTopK());
        assertEquals(512, query.getMaxSnippetChars());
    }

    @Test
    public void testCodeSearchResponseHandlesNullHits() {
        CodeSearchResponse response = new CodeSearchResponse(null, "error");

        assertNotNull(response.getHits());
        assertTrue(response.getHits().isEmpty());
        assertEquals("error", response.getError());
    }

    @Test
    public void testCodeSearchResponseIsUnmodifiable() {
        List<CodeSearchHit> hits = new ArrayList<CodeSearchHit>();
        hits.add(new CodeSearchHit("src/A.java", "class", "A", 1, 2, "class A {}", false));
        CodeSearchResponse response = new CodeSearchResponse(hits);

        assertEquals(1, response.getHits().size());
        assertThrows(UnsupportedOperationException.class, () -> response.getHits().add(hits.get(0)));
    }

    @Test
    public void testCodeSearchHitFieldsAndScore() {
        CodeSearchHit hit = new CodeSearchHit("src/Auth.java", "class", "Auth", 3, 9, "class Auth {}", true);

        assertEquals("src/Auth.java", hit.getFilePath());
        assertEquals("class", hit.getSymbolKind());
        assertEquals("Auth", hit.getSymbolName());
        assertEquals(3, hit.getStartLine());
        assertEquals(9, hit.getEndLine());
        assertEquals("class Auth {}", hit.getSnippet());
        assertTrue(hit.isTruncated());
        assertEquals(0.0, hit.getScore());

        hit.setScore(12.5);
        assertEquals(12.5, hit.getScore());
    }

    @Test
    public void testCodeSearchHitSupportsEmptySnippet() {
        CodeSearchHit hit = new CodeSearchHit("src/Empty.java", "file", "", 0, 0, null, false);

        assertEquals("src/Empty.java", hit.getFilePath());
        assertEquals("file", hit.getSymbolKind());
        assertEquals("", hit.getSymbolName());
        assertEquals(0, hit.getStartLine());
        assertEquals(0, hit.getEndLine());
        assertFalse(hit.isTruncated());
        assertEquals(0.0, hit.getScore());
    }
}
