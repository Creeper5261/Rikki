package com.zzf.codeagent.core.rag.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A lightweight index for file paths.
 * Allows fuzzy searching for files by name (e.g. "config", "User", "README").
 * Complements Symbol Search and Vector Search.
 */
@Service
public class FileNameIndexService {
    private static final Logger logger = LoggerFactory.getLogger(FileNameIndexService.class);
    
    // Map<WorkspaceRoot, List<String>> (Relative Paths)
    private final Map<String, List<String>> workspaceFileIndex = new ConcurrentHashMap<>();
    
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".idea", ".gradle", "build", "target", "node_modules", "dist", "out", "coverage"
    );

    public void ensureIndexed(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isEmpty()) return;
        if (workspaceFileIndex.containsKey(workspaceRoot)) return;

        synchronized (this) {
            if (workspaceFileIndex.containsKey(workspaceRoot)) return;
            
            long t0 = System.nanoTime();
            List<String> files = new ArrayList<>();
            try (Stream<Path> paths = Files.walk(Paths.get(workspaceRoot))) {
                paths.filter(Files::isRegularFile)
                     .filter(p -> !isIgnored(p, workspaceRoot))
                     .forEach(p -> {
                         String rel = Paths.get(workspaceRoot).relativize(p).toString().replace('\\', '/');
                         files.add(rel);
                     });
            } catch (IOException e) {
                logger.error("filename.index.fail err={}", e.toString());
            }
            
            workspaceFileIndex.put(workspaceRoot, files);
            long tookMs = (System.nanoTime() - t0) / 1_000_000L;
            logger.info("filename.index.done root={} files={} tookMs={}", workspaceRoot, files.size(), tookMs);
        }
    }

    public List<String> search(String workspaceRoot, String query) {
        ensureIndexed(workspaceRoot);
        List<String> allFiles = workspaceFileIndex.get(workspaceRoot);
        if (allFiles == null || query == null || query.trim().isEmpty()) return Collections.emptyList();
        
        String q = query.toLowerCase().trim();
        // Heuristic: If query is short (<3 chars), strict match; otherwise fuzzy
        boolean strict = q.length() < 3;
        
        // Simple fuzzy score: 
        // - Exact filename match: Priority 1
        // - Filename contains query: Priority 2
        // - Path contains query: Priority 3
        
        return allFiles.stream()
                .filter(path -> {
                    String name = getFileName(path).toLowerCase();
                    boolean match = name.contains(q) || (!strict && path.toLowerCase().contains(q));
                    if (!match) return false;
                    // Verify existence to handle external deletions
                    return Files.exists(Paths.get(workspaceRoot, path));
                })
                .sorted((p1, p2) -> {
                    // Sort by relevance
                    String n1 = getFileName(p1).toLowerCase();
                    String n2 = getFileName(p2).toLowerCase();
                    boolean eq1 = n1.equals(q);
                    boolean eq2 = n2.equals(q);
                    if (eq1 && !eq2) return -1;
                    if (!eq1 && eq2) return 1;
                    
                    boolean c1 = n1.contains(q);
                    boolean c2 = n2.contains(q);
                    if (c1 && !c2) return -1;
                    if (!c1 && c2) return 1;
                    
                    return Integer.compare(p1.length(), p2.length()); // Shorter paths preferred
                })
                .limit(20)
                .collect(Collectors.toList());
    }

    private String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash == -1 ? path : path.substring(lastSlash + 1);
    }

    private boolean isIgnored(Path path, String root) {
        String rel = Paths.get(root).relativize(path).toString().replace('\\', '/');
        for (String ignored : IGNORED_DIRS) {
            if (rel.startsWith(ignored + "/") || rel.equals(ignored)) {
                return true;
            }
        }
        return false;
    }
}
