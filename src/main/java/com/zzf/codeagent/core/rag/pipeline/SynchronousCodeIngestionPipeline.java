package com.zzf.codeagent.core.rag.pipeline;

import com.zzf.codeagent.core.rag.code.CodeChunk;
import com.zzf.codeagent.core.rag.code.CodeChunker;
import com.zzf.codeagent.core.rag.code.JavaParserCodeChunker;
import com.zzf.codeagent.core.rag.search.InMemoryCodeSearchService;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

public final class SynchronousCodeIngestionPipeline implements CodeIngestionPipeline {
    private static final Logger logger = LoggerFactory.getLogger(SynchronousCodeIngestionPipeline.class);
    private final CodeChunker chunker;
    private final InMemoryCodeSearchService index;

    public SynchronousCodeIngestionPipeline(InMemoryCodeSearchService index) {
        this(index, new JavaParserCodeChunker());
    }

    public SynchronousCodeIngestionPipeline(InMemoryCodeSearchService index, CodeChunker chunker) {
        this.index = index;
        this.chunker = chunker;
    }

    @Override
    public void ingest(Path repoRoot) {
        String traceId = MDC.get("traceId");
        long t0 = System.nanoTime();
        logger.info("sync.ingest.start traceId={} repoRoot={}", traceId, repoRoot == null ? "" : repoRoot.toAbsolutePath().normalize().toString());
        List<CodeChunk> all = new ArrayList<CodeChunk>();
        final int[] files = new int[]{0};
        final int[] skippedLarge = new int[]{0};
        final int[] skippedExt = new int[]{0};
        final int[] javaParseFailed = new int[]{0};
        try {
            Files.walkFileTree(repoRoot, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (FileSystemToolService.shouldSkipDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs != null && attrs.isRegularFile() && attrs.size() > 1024L * 1024L) {
                        skippedLarge[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    String ext = extension(file);
                    if (ext.isEmpty()) {
                        skippedExt[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (!FileSystemToolService.isIndexableExt(ext)) {
                        skippedExt[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    files[0]++;
                    Path rel = repoRoot.relativize(file);
                    String src = Files.readString(file, StandardCharsets.UTF_8);
                    if ("java".equals(ext)) {
                        try {
                            all.addAll(chunker.chunk(rel, src));
                        } catch (Exception ex) {
                            javaParseFailed[0]++;
                            logger.warn("sync.ingest javaParseFailed file={} err={}", rel.toString().replace('\\', '/'), ex.toString());
                            all.add(fileChunk(rel, languageForExt(ext), src));
                        }
                    } else {
                        all.add(fileChunk(rel, languageForExt(ext), src));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        index.upsertAll(all);
        logger.info("sync.ingest.ok traceId={} files={} chunks={} skippedLarge={} skippedExt={} javaParseFailed={} tookMs={}",
                traceId, files[0], all.size(), skippedLarge[0], skippedExt[0], javaParseFailed[0], (System.nanoTime() - t0) / 1_000_000L);
    }

    private static String extension(Path file) {
        if (file == null) {
            return "";
        }
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase();
    }

    private static String languageForExt(String ext) {
        if ("java".equals(ext)) {
            return "java";
        }
        if ("kt".equals(ext) || "kts".equals(ext)) {
            return "kotlin";
        }
        if ("xml".equals(ext)) {
            return "xml";
        }
        if ("yml".equals(ext) || "yaml".equals(ext)) {
            return "yaml";
        }
        if ("properties".equals(ext)) {
            return "properties";
        }
        if ("gradle".equals(ext)) {
            return "gradle";
        }
        if ("md".equals(ext)) {
            return "markdown";
        }
        if ("sql".equals(ext)) {
            return "sql";
        }
        if ("json".equals(ext)) {
            return "json";
        }
        return "text";
    }

    private static CodeChunk fileChunk(Path relativePath, String language, String content) {
        String rel = relativePath == null ? "" : relativePath.toString().replace('\\', '/');
        int end = countLines(content);
        String id = rel + "|file|1";
        return new CodeChunk(id, language, rel, "file", rel, rel, 1, end, content == null ? "" : content);
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }
}
