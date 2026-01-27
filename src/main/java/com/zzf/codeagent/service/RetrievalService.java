package com.zzf.codeagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.rag.index.CodeAgentV2IndexInitializer;
import com.zzf.codeagent.core.rag.index.ElasticsearchIndexNames;
import com.zzf.codeagent.core.rag.pipeline.SynchronousCodeIngestionPipeline;
import com.zzf.codeagent.core.rag.search.CodeSearchQuery;
import com.zzf.codeagent.core.rag.search.CodeSearchResponse;
import com.zzf.codeagent.core.rag.search.ElasticsearchCodeSearchService;
import com.zzf.codeagent.core.rag.search.InMemoryCodeSearchService;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
public final class RetrievalService {
    private static final Logger logger = LoggerFactory.getLogger(RetrievalService.class);
    private static final String JSON_UTF8 = "application/json;charset=UTF-8";

    private final ObjectMapper mapper;
    private final EmbeddingService embeddingService;
    private final ContextService contextService;

    @Value("${elasticsearch.scheme:http}")
    private String esScheme;

    @Value("${elasticsearch.host:localhost}")
    private String esHost;

    @Value("${elasticsearch.port:9200}")
    private int esPort;

    @Value("${embedding.api.dimension:2048}")
    private int embeddingDimension;

    public RetrievalService(ObjectMapper mapper, EmbeddingService embeddingService, ContextService contextService) {
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.contextService = contextService;
    }

    public ResponseEntity<Map<String, Object>> search(Map<String, Object> req) {
        String traceId = "trace-search-" + UUID.randomUUID();
        MDC.put("traceId", traceId);
        try {
            if (req == null) {
                throw new ResponseStatusException(BAD_REQUEST, "body is required");
            }
            String q = req.get("query") instanceof String ? ((String) req.get("query")).trim() : "";
            if (q.isEmpty()) {
                throw new ResponseStatusException(BAD_REQUEST, "query is blank");
            }
            int topK = req.get("topK") instanceof Number ? ((Number) req.get("topK")).intValue() : 5;
            if (topK <= 0) {
                topK = 5;
            }
            String workspaceRoot = req.get("workspaceRoot") instanceof String ? ((String) req.get("workspaceRoot")).trim() : "";
            if (workspaceRoot.isEmpty()) {
                throw new ResponseStatusException(BAD_REQUEST, "workspaceRoot is blank");
            }
            String indexName = ElasticsearchIndexNames.codeAgentV2IndexForWorkspaceRoot(workspaceRoot);
            logger.info("search.in traceId={} workspaceRoot={} index={} topK={} q={}", traceId, workspaceRoot, indexName, topK, contextService.truncate(q, 200));
            HttpClient http = HttpClient.newHttpClient();
            ensureIndexExists(http, indexName);
            ElasticsearchCodeSearchService search = new ElasticsearchCodeSearchService(http, esBaseUri(), indexName, mapper, embeddingService);

            String engine = "elasticsearch";
            CodeSearchResponse resp = search.search(new CodeSearchQuery(q, topK, 1200));
            boolean hitsEmpty = resp.getHits().isEmpty();
            boolean hasError = resp.getError() != null && !resp.getError().isEmpty();
            boolean shouldFallback = hitsEmpty || hasError;
            logger.info("search.es traceId={} hits={} error={} shouldFallback={} topK={}", traceId, resp.getHits().size(), resp.getError(), shouldFallback, topK);
            if (shouldFallback) {
                try {
                    Path root = Paths.get(workspaceRoot);
                    if (Files.exists(root) && Files.isDirectory(root)) {
                        InMemoryCodeSearchService memory = new InMemoryCodeSearchService();
                        try {
                            new SynchronousCodeIngestionPipeline(memory).ingest(root);
                            logger.info("search.fallback.indexed traceId={} workspaceRoot={}", traceId, workspaceRoot);
                            CodeSearchResponse memResp = memory.search(new CodeSearchQuery(q, topK, 1200));
                            engine = "memory";
                            resp = memResp;
                        } catch (Exception ex) {
                            logger.warn("search.fallback failed traceId={} err={}", traceId, ex.toString());
                        }
                    }
                } catch (Exception ex) {
                    logger.warn("search.fallback failed traceId={} err={}", traceId, ex.toString());
                }
            }
            Map<String, Object> out = new HashMap<String, Object>();
            out.put("traceId", traceId);
            out.put("workspaceRoot", workspaceRoot);
            out.put("index", indexName);
            out.put("engine", engine);
            out.put("hits", resp.getHits().size());
            out.put("error", resp.getError());
            out.put("result", resp);
            logger.info("search.out traceId={} engine={} hits={} error={}", traceId, engine, resp.getHits().size(), resp.getError());
            return jsonOk(out);
        } finally {
            MDC.remove("traceId");
        }
    }

    public Map<String, Object> esHealth() {
        Map<String, Object> e = new HashMap<String, Object>();
        try {
            HttpClient http = HttpClient.newHttpClient();
            URI base = esBaseUri();
            HttpRequest root = HttpRequest.newBuilder(base.resolve("/"))
                    .GET()
                    .build();
            HttpResponse<String> rootResp = http.send(root, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            e.put("reachable", rootResp.statusCode() >= 200 && rootResp.statusCode() < 500);

            HttpRequest head = HttpRequest.newBuilder(base.resolve("/code_agent_v2"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> headResp = http.send(head, HttpResponse.BodyHandlers.discarding());
            e.put("code_agent_v2_exists", headResp.statusCode() == 200);
            e.put("code_agent_v2_status", headResp.statusCode());
        } catch (Exception ex) {
            e.put("reachable", false);
            e.put("error", ex.getMessage());
        }
        return e;
    }

    public ElasticsearchCodeSearchService createSearchService(HttpClient http, String indexName) {
        return new ElasticsearchCodeSearchService(http, esBaseUri(), indexName, mapper, embeddingService);
    }

    public void ensureIndexExists(HttpClient http, String indexName) {
        CodeAgentV2IndexInitializer initializer = new CodeAgentV2IndexInitializer(http, esBaseUri(), embeddingDimension);
        try {
            initializer.ensureIndexExists(indexName);
        } catch (Exception e) {
            logger.warn("index.ensure failed index={} err={}", indexName, e.toString());
        }
    }

    public URI esBaseUri() {
        return URI.create(esScheme + "://" + esHost + ":" + esPort);
    }

    private ResponseEntity<Map<String, Object>> jsonOk(Map<String, Object> body) {
        return ResponseEntity.ok().header("Content-Type", JSON_UTF8).body(body);
    }

    private ResponseEntity<Map<String, Object>> jsonStatus(HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status).header("Content-Type", JSON_UTF8).body(body);
    }
}
