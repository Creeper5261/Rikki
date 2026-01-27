package com.zzf.codeagent.core.rag.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class ElasticsearchCodeSearchService implements CodeSearchService {
    private static final Logger logger = LoggerFactory.getLogger(ElasticsearchCodeSearchService.class);
    private static final int MAX_RETRIES = 1;
    private static final int RETRY_BACKOFF_MS = 120;
    private static final int HTTP_TIMEOUT_MS = 4000;
    private static final int EMBEDDING_CACHE_SIZE = 512;

    private final HttpClient http;
    private final URI baseUri;
    private final String indexName;
    private final ObjectMapper mapper;
    private final EmbeddingService embeddingService;
    private final LruCache<String, float[]> embeddingCache = new LruCache<>(EMBEDDING_CACHE_SIZE);

    public ElasticsearchCodeSearchService(HttpClient http, URI baseUri, String indexName, ObjectMapper mapper, EmbeddingService embeddingService) {
        this.http = http;
        this.baseUri = baseUri;
        this.indexName = indexName;
        this.mapper = mapper;
        this.embeddingService = embeddingService;
    }

    @Override
    public CodeSearchResponse search(CodeSearchQuery query) {
        long t0 = System.nanoTime();
        String q = query == null ? "" : query.getQuery();
        int attempt = 0;
        CodeSearchResponse last = null;
        while (attempt <= MAX_RETRIES) {
            attempt++;
            try {
                CodeSearchResponse resp = executeSearch(query, q, t0, attempt);
                if (resp.getError() == null || resp.getError().isEmpty() || attempt > MAX_RETRIES) {
                    return resp;
                }
                last = resp;
                sleepBackoff(attempt);
            } catch (Exception e) {
                last = new CodeSearchResponse(Collections.emptyList(), e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()));
                if (attempt > MAX_RETRIES) {
                    logger.warn("es.search error index={} tookMs={} q={} err={}", indexName, (System.nanoTime() - t0) / 1_000_000L, truncate(q, 200), e.toString());
                    return last;
                }
                sleepBackoff(attempt);
            }
        }
        return last == null ? new CodeSearchResponse(Collections.emptyList(), "empty") : last;
    }

    private CodeSearchResponse executeSearch(CodeSearchQuery query, String rawQuery, long t0, int attempt) throws Exception {
        logger.info("es.search.start index={} topK={} maxSnippetChars={} attempt={} q={}", indexName, query.getTopK(), query.getMaxSnippetChars(), attempt, truncate(rawQuery, 200));
        long embedT0 = System.nanoTime();
        float[] vec = embeddingWithCache(rawQuery);
        long embedMs = (System.nanoTime() - embedT0) / 1_000_000L;
        int vecDims = vec == null ? 0 : vec.length;
        logger.info("es.search.embed index={} vecDims={} embedMs={} attempt={}", indexName, vecDims, embedMs, attempt);
        String body = buildSearchBody(query, vec);
        logger.info("es.search.body index={} chars={} attempt={}", indexName, body.length(), attempt);
        HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("/" + indexName + "/_search"))
                .timeout(Duration.ofMillis(HTTP_TIMEOUT_MS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < HttpURLConnection.HTTP_OK || resp.statusCode() >= 300) {
            String err = "status=" + resp.statusCode();
            logger.warn("es.search failed index={} status={} attempt={} q={}", indexName, resp.statusCode(), attempt, truncate(rawQuery, 200));
            return new CodeSearchResponse(Collections.emptyList(), err);
        }

        JsonNode root = mapper.readTree(resp.body());
        JsonNode hitsNode = root.path("hits").path("hits");
        List<CodeSearchHit> hits = new ArrayList<CodeSearchHit>();
        int truncatedCount = 0;
        for (int i = 0; i < hitsNode.size(); i++) {
            JsonNode src = hitsNode.get(i).path("_source");
            String filePath = src.path("filePath").asText("");
            String symbolKind = src.path("symbolKind").asText("");
            String symbolName = src.path("symbolName").asText("");
            int startLine = src.path("startLine").asInt(0);
            int endLine = src.path("endLine").asInt(0);
            String snippet = src.path("content").asText("");
            boolean truncated = false;
            if (query.getMaxSnippetChars() > 0 && snippet.length() > query.getMaxSnippetChars()) {
                snippet = snippet.substring(0, query.getMaxSnippetChars());
                truncated = true;
            }
            if (truncated) {
                truncatedCount++;
            }
            hits.add(new CodeSearchHit(filePath, symbolKind, symbolName, startLine, endLine, snippet, truncated));
        }
        logger.info("es.search ok index={} hits={} truncated={} tookMs={} attempt={} q={}", indexName, hits.size(), truncatedCount, (System.nanoTime() - t0) / 1_000_000L, attempt, truncate(rawQuery, 200));
        return new CodeSearchResponse(hits);
    }

    private float[] embeddingWithCache(String query) {
        String key = normalizeKey(query);
        float[] cached = embeddingCache.get(key);
        if (cached != null) {
            return cached;
        }
        float[] vec = embeddingService.embed(query);
        if (vec != null) {
            embeddingCache.put(key, vec);
        }
        return vec;
    }

    private String buildSearchBody(CodeSearchQuery q, float[] vec) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"size\":").append(q.getTopK()).append(",");
        sb.append("\"query\":{");
        sb.append("\"script_score\":{");
        sb.append("\"query\":{");
        sb.append("\"match_all\":{}");
        sb.append("},");
        sb.append("\"script\":{");
        sb.append("\"source\":\"cosineSimilarity(params.queryVector, 'contentVector') + 1.0\",");
        sb.append("\"params\":{\"queryVector\":").append(vector(vec)).append("}");
        sb.append("}");
        sb.append("}");
        sb.append("}");
        sb.append("}");
        return sb.toString();
    }

    private String vector(float[] v) {
        if (v == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(v[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ");
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }

    private static String normalizeKey(String q) {
        if (q == null) {
            return "";
        }
        return q.trim().toLowerCase();
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep((long) RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class LruCache<K, V> {
        private final int maxSize;
        private final Map<K, V> map;

        private LruCache(int maxSize) {
            this.maxSize = Math.max(1, maxSize);
            this.map = Collections.synchronizedMap(new java.util.LinkedHashMap<K, V>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                    return size() > LruCache.this.maxSize;
                }
            });
        }

        private V get(K key) {
            return map.get(key);
        }

        private void put(K key, V value) {
            map.put(key, value);
        }
    }
}
