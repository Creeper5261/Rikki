package com.zzf.codeagent.core.rag.search;

import com.zzf.codeagent.core.rag.code.CodeChunk;
import com.zzf.codeagent.core.rag.code.CodeChunker;
import com.zzf.codeagent.core.rag.code.JavaParserCodeChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryCodeSearchService implements CodeSearchService {
    private static final Logger logger = LoggerFactory.getLogger(InMemoryCodeSearchService.class);

    private final Map<String, CodeChunk> store = new ConcurrentHashMap<String, CodeChunk>();
    private final CodeTokenizer tokenizer = new CodeTokenizer();
    private final CodeChunker chunker = new JavaParserCodeChunker();

    public void upsertAll(List<CodeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        String traceId = MDC.get("traceId");
        int before = store.size();
        for (int i = 0; i < chunks.size(); i++) {
            CodeChunk c = chunks.get(i);
            store.put(c.getId(), c);
        }
        logger.info("memory.upsert traceId={} chunks={} storeSizeBefore={} storeSizeAfter={}", traceId, chunks.size(), before, store.size());
    }

    public void ingest(String filePath, String source) {
        String traceId = MDC.get("traceId");
        logger.info("memory.ingest.start traceId={} filePath={} sourceChars={}", traceId, truncate(filePath, 300), source == null ? 0 : source.length());
        long t0 = System.nanoTime();
        List<CodeChunk> chunks = chunker.chunk(java.nio.file.Path.of(filePath), source);
        logger.info("memory.ingest.chunk traceId={} filePath={} chunks={} tookMs={}", traceId, truncate(filePath, 300), chunks.size(), (System.nanoTime() - t0) / 1_000_000L);
        upsertAll(chunks);
    }

    @Override
    public CodeSearchResponse search(CodeSearchQuery query) {
        String traceId = MDC.get("traceId");
        long t0 = System.nanoTime();
        int storeSize = store.size();
        List<String> tokens = tokenizer.tokenize(query.getQuery());
        logger.info("memory.search.start traceId={} storeSize={} tokens={} topK={} maxSnippetChars={} q={}",
                traceId, storeSize, tokens.size(), query.getTopK(), query.getMaxSnippetChars(), truncate(query.getQuery(), 200));
        List<Scored> scored = new ArrayList<Scored>();
        for (CodeChunk c : store.values()) {
            int score = score(tokens, c.getContent());
            if (score > 0) {
                scored.add(new Scored(c, score));
            }
        }
        logger.info("memory.search.candidates traceId={} candidates={}", traceId, scored.size());
        scored.sort(Comparator.comparingInt((Scored s) -> s.score).reversed());
        List<CodeSearchHit> hits = new ArrayList<CodeSearchHit>();
        int limit = Math.min(query.getTopK(), scored.size());
        int truncatedCount = 0;
        for (int i = 0; i < limit; i++) {
            CodeChunk c = scored.get(i).chunk;
            String snippet = c.getContent();
            boolean truncated = false;
            if (query.getMaxSnippetChars() > 0 && snippet.length() > query.getMaxSnippetChars()) {
                snippet = snippet.substring(0, query.getMaxSnippetChars());
                truncated = true;
            }
            if (truncated) {
                truncatedCount++;
            }
            hits.add(new CodeSearchHit(c.getFilePath(), c.getSymbolKind(), c.getSymbolName(), c.getStartLine(), c.getEndLine(), snippet, truncated));
        }
        logger.info("memory.search.ok traceId={} hits={} topK={} truncated={} tookMs={}", traceId, hits.size(), query.getTopK(), truncatedCount, (System.nanoTime() - t0) / 1_000_000L);
        return new CodeSearchResponse(hits);
    }

    private int score(List<String> tokens, String content) {
        if (tokens.isEmpty()) {
            return 0;
        }
        int score = 0;
        String lower = content == null ? "" : content.toLowerCase();
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i).toLowerCase();
            if (lower.contains(t)) {
                score++;
            }
        }
        return score;
    }

    private static final class Scored {
        private final CodeChunk chunk;
        private final int score;

        private Scored(CodeChunk chunk, int score) {
            this.chunk = chunk;
            this.score = score;
        }
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        return s.length() <= maxChars ? s : s.substring(0, maxChars);
    }
}
