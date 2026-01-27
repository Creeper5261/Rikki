package com.zzf.codeagent.core.rag.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HybridCodeSearchServiceTest {

    @Test
    public void testComputeFetchKForShortQuery() {
        HybridCodeSearchService service = new HybridCodeSearchService(null, null, new ObjectMapper(), new NoopEmbeddingService(), HttpClient.newHttpClient());

        int fetchK = service.computeFetchK("User", 5);

        assertEquals(10, fetchK);
    }

    @Test
    public void testApplyQualityFilterDropsLowScoreHits() {
        HybridCodeSearchService service = new HybridCodeSearchService(null, null, new ObjectMapper(), new NoopEmbeddingService(), HttpClient.newHttpClient());
        List<CodeSearchHit> hits = new ArrayList<CodeSearchHit>();
        hits.add(new CodeSearchHit("src/Other.java", "", "", 1, 2, "class Other {}", false));
        hits.add(new CodeSearchHit("src/UserService.java", "class", "UserService", 1, 2, "public void login() {}", false));

        List<CodeSearchHit> scored = service.scoreAndSort("UserService login", hits);
        List<CodeSearchHit> filtered = service.applyQualityFilter("UserService login", scored, 5);

        assertEquals(1, filtered.size());
        assertEquals("src/UserService.java", filtered.get(0).getFilePath());
    }

    private static final class NoopEmbeddingService implements EmbeddingService {
        @Override
        public float[] embed(String text) {
            return new float[]{0.1f, 0.2f};
        }
    }
}
