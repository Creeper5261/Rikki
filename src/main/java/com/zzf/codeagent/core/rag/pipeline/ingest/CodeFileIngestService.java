package com.zzf.codeagent.core.rag.pipeline.ingest;

import com.zzf.codeagent.core.rag.code.CodeChunk;
import com.zzf.codeagent.core.rag.code.CodeChunker;
import com.zzf.codeagent.core.rag.code.JavaParserCodeChunker;
import com.zzf.codeagent.core.rag.index.CodeAgentV2BulkIndexer;
import com.zzf.codeagent.core.rag.index.CodeAgentV2Document;
import com.zzf.codeagent.core.rag.index.ElasticsearchIndexNames;
import com.zzf.codeagent.core.rag.pipeline.redis.FileHashCache;
import com.zzf.codeagent.core.rag.vector.EmbeddingService;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CodeFileIngestService {
    private static final Logger logger = LoggerFactory.getLogger(CodeFileIngestService.class);

    private final FileHashCache hashCache;
    private final CodeChunker chunker;
    private final EmbeddingService embeddingService;
    private final CodeAgentV2BulkIndexer indexer;
    private final String repoName;

    public CodeFileIngestService(FileHashCache hashCache, EmbeddingService embeddingService, CodeAgentV2BulkIndexer indexer, String repoName) {
        this(hashCache, new JavaParserCodeChunker(), embeddingService, indexer, repoName);
    }

    public CodeFileIngestService(FileHashCache hashCache, CodeChunker chunker, EmbeddingService embeddingService, CodeAgentV2BulkIndexer indexer, String repoName) {
        this.hashCache = hashCache;
        this.chunker = chunker;
        this.embeddingService = embeddingService;
        this.indexer = indexer;
        this.repoName = repoName;
    }

    public IngestOutcome ingestOne(Path repoRoot, String relativePath, String sha256) {
        long t0 = System.nanoTime();
        if (hashCache.isUnchanged(repoRoot.toString(), relativePath, sha256)) {
            logger.info("ingest.skip unchanged path={}", relativePath);
            return new IngestOutcome(true, 0, 0L);
        }
        try {
            String indexName = ElasticsearchIndexNames.codeAgentV2IndexForWorkspaceRoot(repoRoot.toString());
            Path file = repoRoot.resolve(relativePath);
            logger.info("ingest.start index={} repo={} path={} sha256={}", indexName, repoName, relativePath, sha256);
            String src = Files.readString(file, StandardCharsets.UTF_8);
            logger.info("ingest.read ok path={} chars={}", relativePath, src.length());
            long chunkT0 = System.nanoTime();
            List<CodeChunk> chunks;
            if (relativePath != null && relativePath.toLowerCase().endsWith(".java")) {
                chunks = chunker.chunk(Path.of(relativePath), src);
            } else {
                chunks = plainTextChunks(relativePath, src);
            }
            long chunkMs = (System.nanoTime() - chunkT0) / 1_000_000L;
            logger.info("ingest.chunk ok path={} chunks={} tookMs={}", relativePath, chunks.size(), chunkMs);
            List<CodeAgentV2Document> docs = new ArrayList<CodeAgentV2Document>();
            long embedNanos = 0L;
            int vecDims = -1;
            for (int i = 0; i < chunks.size(); i++) {
                CodeChunk c = chunks.get(i);
                long embedT0 = System.nanoTime();
                float[] vec = embeddingService.embed(c.getContent());
                embedNanos += (System.nanoTime() - embedT0);
                if (vec != null) {
                    vecDims = vec.length;
                }
                if (i < 3) {
                    logger.info("ingest.embed.sample path={} chunkId={} symbolKind={} symbolName={} lines={}..{} vecDims={}",
                            relativePath, c.getId(), c.getSymbolKind(), c.getSymbolName(), c.getStartLine(), c.getEndLine(), vecDims);
                } else if (i == 3) {
                    logger.info("ingest.embed.sample.more path={} remainingChunks={}", relativePath, Math.max(0, chunks.size() - 3));
                }
                logger.debug("ingest.embed.chunk path={} chunkId={} chars={} vecDims={} tookMs={}",
                        relativePath, c.getId(), c.getContent() == null ? 0 : c.getContent().length(), vecDims, (System.nanoTime() - embedT0) / 1_000_000L);
                docs.add(new CodeAgentV2Document(
                        c.getId(),
                        repoName,
                        c.getLanguage(),
                        c.getFilePath(),
                        c.getSymbolKind(),
                        c.getSymbolName(),
                        c.getSignature(),
                        c.getStartLine(),
                        c.getEndLine(),
                        c.getContent(),
                        vec
                ));
            }
            logger.info("ingest.embed ok path={} chunks={} vecDims={} embedMs={}", relativePath, chunks.size(), vecDims, embedNanos / 1_000_000L);
            long bulkT0 = System.nanoTime();
            indexer.bulkIndex(indexName, docs);
            long bulkMs = (System.nanoTime() - bulkT0) / 1_000_000L;
            logger.info("ingest.bulk ok path={} docs={} tookMs={}", relativePath, docs.size(), bulkMs);
            hashCache.update(repoRoot.toString(), relativePath, sha256);
            long tookMs = (System.nanoTime() - t0) / 1_000_000L;
            logger.info("ingest.ok path={} chunks={} vecDims={} totalMs={}", relativePath, chunks.size(), vecDims, tookMs);
            return new IngestOutcome(false, chunks.size(), embedNanos);
        } catch (Exception e) {
            logger.warn("ingest.fail path={} err={}", relativePath, e.toString());
            throw new RuntimeException(e);
        }
    }

    private static List<CodeChunk> plainTextChunks(String relativePath, String src) {
        String rp = relativePath == null ? "" : relativePath.replace('\\', '/');
        if (rp.isEmpty()) {
            rp = "unknown";
        }
        String lang = "text";
        int dot = rp.lastIndexOf('.');
        if (dot >= 0 && dot < rp.length() - 1) {
            String ext = rp.substring(dot + 1).toLowerCase();
            if (FileSystemToolService.isIndexableExt(ext)) {
                lang = ext;
            }
        }
        String name = rp;
        int slash = rp.lastIndexOf('/');
        if (slash >= 0 && slash < rp.length() - 1) {
            name = rp.substring(slash + 1);
        }

        String text = src == null ? "" : src;
        String[] lines = text.split("\n", -1);
        List<CodeChunk> chunks = new ArrayList<CodeChunk>();

        int startLine = 1;
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (buf.length() > 0) {
                buf.append("\n");
            }
            buf.append(line);
            boolean lineLimitReached = (i + 1) - startLine + 1 >= 200;
            boolean charLimitReached = buf.length() >= 8000;
            boolean lastLine = i == lines.length - 1;
            if (lineLimitReached || charLimitReached || lastLine) {
                int endLine = i + 1;
                String id = rp + "|file|" + startLine;
                chunks.add(new CodeChunk(id, lang, rp, "file", name, name, startLine, endLine, buf.toString()));
                buf.setLength(0);
                startLine = endLine + 1;
            }
        }
        return chunks;
    }
}
