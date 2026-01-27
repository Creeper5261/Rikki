package com.zzf.codeagent.core.rag.pipeline.scan;

import com.zzf.codeagent.core.rag.pipeline.events.FileChangeEvent;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class RepoScanner {
    private static final Logger logger = LoggerFactory.getLogger(RepoScanner.class);

    public List<FileChangeEvent> scan(String traceId, Path repoRoot) {
        long t0 = System.nanoTime();
        logger.info("scan.start traceId={} repoRoot={}", traceId, repoRoot);
        List<FileChangeEvent> events = new ArrayList<FileChangeEvent>();
        final int[] files = new int[]{0};
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
                    if (attrs == null || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String rel = repoRoot.relativize(file).toString().replace('\\', '/');
                    if (!FileSystemToolService.isIndexablePath(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String sha = sha256Hex(Files.readAllBytes(file));
                    events.add(new FileChangeEvent(traceId, repoRoot.toString(), rel, sha));
                    files[0]++;
                    if (files[0] % 200 == 0) {
                        logger.info("scan.progress traceId={} repoRoot={} files={}", traceId, repoRoot, files[0]);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.warn("scan.fail traceId={} repoRoot={} err={}", traceId, repoRoot, e.toString());
            throw new RuntimeException(e);
        }
        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        logger.info("scan.ok traceId={} repoRoot={} files={} tookMs={}", traceId, repoRoot, files[0], tookMs);
        return events;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
