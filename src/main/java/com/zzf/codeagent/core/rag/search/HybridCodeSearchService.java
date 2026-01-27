package com.zzf.codeagent.core.rag.search;

import com.zzf.codeagent.core.rag.index.FileNameIndexService;
import com.zzf.codeagent.core.rag.index.SymbolGraphService;
import com.zzf.codeagent.core.rag.index.ElasticsearchIndexNames;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;
import com.zzf.codeagent.core.rag.vector.Sha256EmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Coordinates search across multiple indices (Vector, Symbol, File Name) to provide
 * a unified, high-recall retrieval service.
 * <p>
 * This ensures that both the fast-path Pipeline and the slow-path Agent see the same
 * rich context, preventing "blindness" during fallback.
 */
@Service
public class HybridCodeSearchService {
    private static final Logger logger = LoggerFactory.getLogger(HybridCodeSearchService.class);
    private static final int SEARCH_TIMEOUT_MS = 5000;
    private static final int PER_SOURCE_TIMEOUT_MS = 3500;
    private static final int RETRY_ATTEMPTS = 1;
    private static final int RETRY_BACKOFF_MS = 120;
    private static final int SEARCH_CACHE_SIZE = 256;
    private static final double SCORE_HAS_SNIPPET = 1.0;
    private static final double SCORE_SYMBOL_PRESENT = 2.0;
    private static final double SCORE_SYMBOL_MATCH = 5.0;
    private static final double SCORE_KEYWORD_MATCH = 2.0;
    private static final double SCORE_FILENAME_MATCH = 2.0;
    private static final ExecutorService executor = Executors.newCachedThreadPool();
    private static final LruCache<String, List<CodeSearchHit>> SEARCH_CACHE = new LruCache<>(SEARCH_CACHE_SIZE);

    private final SymbolGraphService symbolSearch;
    private final FileNameIndexService fileSearch;
    private final ObjectMapper mapper;
    private final EmbeddingService embeddingService;
    private final HttpClient http;

    @Value("${elasticsearch.scheme:http}")
    private String esScheme;

    @Value("${elasticsearch.host:localhost}")
    private String esHost;

    @Value("${elasticsearch.port:9200}")
    private int esPort;

    @Autowired
    public HybridCodeSearchService(SymbolGraphService symbolSearch, FileNameIndexService fileSearch, ObjectMapper mapper, EmbeddingService embeddingService) {
        this(symbolSearch, fileSearch, mapper, embeddingService, HttpClient.newHttpClient());
    }

    public HybridCodeSearchService() {
        this(null, null, new ObjectMapper().findAndRegisterModules(), new Sha256EmbeddingService(8), HttpClient.newHttpClient());
    }

    HybridCodeSearchService(SymbolGraphService symbolSearch, FileNameIndexService fileSearch, ObjectMapper mapper, EmbeddingService embeddingService, HttpClient http) {
        this.symbolSearch = symbolSearch;
        this.fileSearch = fileSearch;
        this.mapper = mapper;
        this.embeddingService = embeddingService;
        this.http = http;
    }

    public List<CodeSearchHit> search(String workspaceRoot, String query, int topK) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }

        int safeTopK = topK <= 0 ? 5 : topK;
        String cacheKey = buildCacheKey(workspaceRoot, query, safeTopK);
        List<CodeSearchHit> cached = SEARCH_CACHE.get(cacheKey);
        if (cached != null) {
            logger.info("search.hybrid.cache_hit root={} q={} hits={}", workspaceRoot, truncate(query, 50), cached.size());
            return copyHits(cached);
        }

        int fetchK = computeFetchK(query, safeTopK);
        String indexName = ElasticsearchIndexNames.codeAgentV2IndexForWorkspaceRoot(workspaceRoot);
        URI baseUri = URI.create(esScheme + "://" + esHost + ":" + esPort);
        ElasticsearchCodeSearchService vectorSearch = new ElasticsearchCodeSearchService(http, baseUri, indexName, mapper, embeddingService);

        long t0 = System.nanoTime();
        
        CompletableFuture<List<CodeSearchHit>> vectorFuture = CompletableFuture.supplyAsync(() ->
                runWithRetry(() -> vectorSearch.search(new CodeSearchQuery(query, fetchK, 600)).getHits(), "vector"), executor)
                .orTimeout(PER_SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    logger.warn("search.vector.fail err={}", ex.toString());
                    return new ArrayList<CodeSearchHit>();
                });

        CompletableFuture<List<CodeSearchHit>> symbolFuture = CompletableFuture.supplyAsync(() -> {
            if (symbolSearch == null) return new ArrayList<CodeSearchHit>();
            return runWithRetry(() -> symbolSearch.findSymbol(workspaceRoot, query).stream()
                    .limit(fetchK)
                    .map(this::convertSymbolToHit)
                    .collect(Collectors.toList()), "symbol");
        }, executor).orTimeout(PER_SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    logger.warn("search.symbol.fail err={}", ex.toString());
                    return new ArrayList<CodeSearchHit>();
                });

        CompletableFuture<List<CodeSearchHit>> fileFuture = CompletableFuture.supplyAsync(() -> {
            if (fileSearch == null) return new ArrayList<CodeSearchHit>();
            return runWithRetry(() -> fileSearch.search(workspaceRoot, query).stream()
                    .limit(fetchK)
                    .map(f -> new CodeSearchHit(f, "file", getFileName(f), 0, 0, null, false))
                    .collect(Collectors.toList()), "file");
        }, executor).orTimeout(PER_SOURCE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    logger.warn("search.file.fail err={}", ex.toString());
                    return new ArrayList<CodeSearchHit>();
                });

        List<CodeSearchHit> allHits = new ArrayList<>();
        try {
            CompletableFuture.allOf(vectorFuture, symbolFuture, fileFuture).get(SEARCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            allHits.addAll(symbolFuture.get()); // High precision first
            allHits.addAll(fileFuture.get());
            allHits.addAll(vectorFuture.get());
        } catch (Exception e) {
            logger.error("search.timeout_or_fail err={}", e.toString());
            if (vectorFuture.isDone()) try { allHits.addAll(vectorFuture.get()); } catch (Exception ignore) {}
            if (symbolFuture.isDone()) try { allHits.addAll(symbolFuture.get()); } catch (Exception ignore) {}
            if (fileFuture.isDone()) try { allHits.addAll(fileFuture.get()); } catch (Exception ignore) {}
        }

        List<CodeSearchHit> deduped = dedupAndHydrate(allHits, workspaceRoot, fetchK);
        List<CodeSearchHit> scored = scoreAndSort(query, deduped);
        List<CodeSearchHit> finalHits = applyQualityFilter(query, scored, safeTopK);
        
        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        logger.info("search.hybrid.done root={} q={} hits={} tookMs={}", workspaceRoot, truncate(query, 50), finalHits.size(), tookMs);
        SEARCH_CACHE.put(cacheKey, copyHits(finalHits));

        return finalHits;
    }

    private List<CodeSearchHit> dedupAndHydrate(List<CodeSearchHit> hits, String root, int limit) {
        Set<String> seen = new HashSet<>();
        List<CodeSearchHit> result = new ArrayList<>();
        
        for (CodeSearchHit hit : hits) {
            String key = hit.getFilePath() + ":" + hit.getStartLine();
            if (seen.add(key)) {
                result.add(hydrate(hit, root));
                if (result.size() >= limit * 2) break;
            }
        }
        
        result.sort((a, b) -> {
            boolean aHasContent = a.getSnippet() != null && !a.getSnippet().isEmpty();
            boolean bHasContent = b.getSnippet() != null && !b.getSnippet().isEmpty();
            if (aHasContent && !bHasContent) return -1;
            if (!aHasContent && bHasContent) return 1;
            return 0;
        });

        return result;
    }

    private CodeSearchHit hydrate(CodeSearchHit hit, String root) {
        if (hit.getSnippet() != null && !hit.getSnippet().isEmpty() && !hit.getSnippet().contains("Content not loaded")) {
            return hit;
        }
        
        try {
            Path path = Paths.get(root, hit.getFilePath());
            if (!Files.exists(path)) return hit;
            
            List<String> lines = Files.readAllLines(path);
            int start = Math.max(1, hit.getStartLine());
            if (start == 0) start = 1;
            
            int end = hit.getEndLine();
            if (end == 0) end = Math.min(lines.size(), start + 50);
            else end = Math.min(lines.size(), end);
            
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (int i = start - 1; i < end; i++) {
                if (i >= lines.size()) break;
                sb.append(lines.get(i)).append("\n");
                count++;
                if (count > 50 || sb.length() > 2000) {
                    sb.append("... (truncated)\n");
                    break;
                }
            }
            
            return new CodeSearchHit(
                hit.getFilePath(),
                hit.getSymbolKind(),
                hit.getSymbolName(),
                start,
                end,
                sb.toString(),
                hit.isTruncated()
            );
        } catch (Exception e) {
            return hit;
        }
    }

    private CodeSearchHit convertSymbolToHit(SymbolGraphService.SymbolEntry entry) {
        return new CodeSearchHit(
                entry.filePath, 
                entry.kind, 
                entry.name, 
                entry.startLine, 
                entry.endLine, 
                null,
                false
        );
    }

    private String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash == -1 ? path : path.substring(lastSlash + 1);
    }
    
    private String truncate(String s, int len) {
        if (s == null) return "";
        return s.length() <= len ? s : s.substring(0, len);
    }

    int computeFetchK(String query, int topK) {
        int base = Math.max(1, topK);
        int tokenCount = extractKeywords(query).size();
        if (tokenCount <= 1) {
            return Math.min(30, base * 2);
        }
        if (tokenCount == 2) {
            return Math.min(30, (int) Math.ceil(base * 1.5));
        }
        return base;
    }

    List<CodeSearchHit> scoreAndSort(String query, List<CodeSearchHit> hits) {
        Set<String> keywords = extractKeywords(query);
        for (CodeSearchHit hit : hits) {
            double score = 0.0;
            String snippet = hit.getSnippet();
            if (snippet != null && !snippet.isEmpty()) {
                score += SCORE_HAS_SNIPPET;
            }
            String symbolName = hit.getSymbolName();
            if (symbolName != null && !symbolName.isEmpty()) {
                if (containsIgnoreCase(query, symbolName)) {
                    score += SCORE_SYMBOL_MATCH;
                } else {
                    score += SCORE_SYMBOL_PRESENT;
                }
            }
            if (snippet != null && !snippet.isEmpty()) {
                for (String k : keywords) {
                    if (containsIgnoreCase(snippet, k)) {
                        score += SCORE_KEYWORD_MATCH;
                    }
                }
            }
            for (String k : keywords) {
                if (containsIgnoreCase(hit.getFilePath(), k)) {
                    score += SCORE_FILENAME_MATCH;
                }
            }
            hit.setScore(score);
        }
        return hits.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
    }

    List<CodeSearchHit> applyQualityFilter(String query, List<CodeSearchHit> hits, int topK) {
        if (hits == null || hits.isEmpty()) {
            return Collections.emptyList();
        }
        int limit = Math.max(1, topK);
        int tokenCount = extractKeywords(query).size();
        double minScore = tokenCount <= 1 ? 2.5 : 5.0;
        List<CodeSearchHit> filtered = hits.stream()
                .filter(h -> h.getScore() >= minScore)
                .collect(Collectors.toList());
        if (!filtered.isEmpty()) {
            return filtered.stream().limit(limit).collect(Collectors.toList());
        }
        return hits.stream().limit(limit).collect(Collectors.toList());
    }

    private List<CodeSearchHit> runWithRetry(Supplier<List<CodeSearchHit>> supplier, String name) {
        int attempt = 0;
        while (attempt <= RETRY_ATTEMPTS) {
            attempt++;
            try {
                List<CodeSearchHit> hits = supplier.get();
                if (hits != null) {
                    return hits;
                }
            } catch (Exception e) {
                if (attempt > RETRY_ATTEMPTS) {
                    logger.warn("search.retry.fail name={} err={}", name, e.toString());
                } else {
                    logger.warn("search.retry name={} attempt={} err={}", name, attempt, e.toString());
                }
            }
            sleepBackoff(attempt);
        }
        return new ArrayList<>();
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep((long) RETRY_BACKOFF_MS * attempt);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Set<String> extractKeywords(String query) {
        Set<String> keywords = new HashSet<>();
        if (query == null) return keywords;
        String[] tokens = query.split("[^a-zA-Z0-9]+");
        for (String t : tokens) {
            if (t.length() > 2) {
                keywords.add(t.toLowerCase());
            }
        }
        return keywords;
    }

    private static boolean containsIgnoreCase(String text, String token) {
        if (text == null || token == null) return false;
        return text.toLowerCase().contains(token.toLowerCase());
    }

    private static String buildCacheKey(String workspaceRoot, String query, int topK) {
        String root = workspaceRoot == null ? "" : workspaceRoot.trim().toLowerCase();
        String q = query == null ? "" : query.trim().toLowerCase();
        return root + "|" + q + "|" + topK;
    }

    private static List<CodeSearchHit> copyHits(List<CodeSearchHit> hits) {
        List<CodeSearchHit> copy = new ArrayList<>();
        for (CodeSearchHit hit : hits) {
            CodeSearchHit cloned = new CodeSearchHit(
                    hit.getFilePath(),
                    hit.getSymbolKind(),
                    hit.getSymbolName(),
                    hit.getStartLine(),
                    hit.getEndLine(),
                    hit.getSnippet(),
                    hit.isTruncated()
            );
            cloned.setScore(hit.getScore());
            copy.add(cloned);
        }
        return copy;
    }

    private static final class LruCache<K, V> {
        private final int maxSize;
        private final Map<K, V> map;

        private LruCache(int maxSize) {
            this.maxSize = Math.max(1, maxSize);
            this.map = Collections.synchronizedMap(new LinkedHashMap<K, V>(16, 0.75f, true) {
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
