package com.zzf.codeagent.core.rag.index;

import com.zzf.codeagent.core.rag.code.CodeChunk;
import com.zzf.codeagent.core.rag.code.JavaParserCodeChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * A lightweight, in-memory symbol graph index for the codebase.
 * Provides millisecond-level lookups for classes, methods, and symbols.
 * <p>
 * Architecture:
 * - Storage: ConcurrentHashMap<String, List<SymbolEntry>> (Symbol Name -> Entries)
 * - Indexing: On-demand full scan or incremental updates (TODO).
 */
@Service
public class SymbolGraphService {
    private static final Logger logger = LoggerFactory.getLogger(SymbolGraphService.class);
    
    // Map<WorkspaceRoot, Map<SymbolName, List<SymbolEntry>>>
    private final Map<String, Map<String, List<SymbolEntry>>> workspaceIndexes = new ConcurrentHashMap<>();
    private final JavaParserCodeChunker chunker = new JavaParserCodeChunker();
    
    // Ignore patterns for indexing
    private static final Set<String> IGNORED_DIRS = Set.of(
        ".git", ".idea", ".gradle", "build", "target", "node_modules", "dist", "out"
    );

    public SymbolGraphService() {
    }

    public static class SymbolEntry {
        public final String name;
        public final String kind; // class, method, interface, etc.
        public final String signature;
        public final String filePath;
        public final int startLine;
        public final int endLine;

        public SymbolEntry(String name, String kind, String signature, String filePath, int startLine, int endLine) {
            this.name = name;
            this.kind = kind;
            this.signature = signature;
            this.filePath = filePath;
            this.startLine = startLine;
            this.endLine = endLine;
        }

        @Override
        public String toString() {
            return String.format("[%s] %s (%s:%d)", kind, signature, filePath, startLine);
        }
    }

    public void ensureIndexed(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isEmpty()) return;
        
        // Check if already indexed
        if (workspaceIndexes.containsKey(workspaceRoot)) return;

        synchronized (this) {
            if (workspaceIndexes.containsKey(workspaceRoot)) return;
            
            long t0 = System.nanoTime();
            logger.info("symbol_graph.index.start root={}", workspaceRoot);
            
            Map<String, List<SymbolEntry>> index = new ConcurrentHashMap<>();
            try (Stream<Path> paths = Files.walk(Paths.get(workspaceRoot))) {
                paths.filter(p -> p.toString().endsWith(".java"))
                     .filter(p -> !isIgnored(p, workspaceRoot))
                     .forEach(p -> indexFile(p, index, workspaceRoot));
            } catch (IOException e) {
                logger.error("symbol_graph.index.fail err={}", e.toString());
            }
            
            workspaceIndexes.put(workspaceRoot, index);
            long tookMs = (System.nanoTime() - t0) / 1_000_000L;
            logger.info("symbol_graph.index.done root={} symbols={} tookMs={}", workspaceRoot, index.size(), tookMs);
        }
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

    private void indexFile(Path path, Map<String, List<SymbolEntry>> index, String root) {
        try {
            String content = Files.readString(path);
            Path relPath = Paths.get(root).relativize(path);
            List<CodeChunk> chunks = chunker.chunk(path, content);
            
            for (CodeChunk chunk : chunks) {
                String simpleName = extractSimpleName(chunk.getSymbolName());
                SymbolEntry entry = new SymbolEntry(
                        simpleName,
                        chunk.getSymbolKind(), // Fixed: getKind -> getSymbolKind
                        chunk.getSignature(),
                        relPath.toString().replace('\\', '/'),
                        chunk.getStartLine(),
                        chunk.getEndLine()
                );
                
                index.computeIfAbsent(simpleName.toLowerCase(), k -> new ArrayList<>()).add(entry);
                if (!simpleName.equals(chunk.getSymbolName())) {
                     index.computeIfAbsent(chunk.getSymbolName().toLowerCase(), k -> new ArrayList<>()).add(entry);
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private String extractSimpleName(String qualifiedName) {
        if (qualifiedName == null) return "";
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot == -1 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    public List<SymbolEntry> findSymbol(String workspaceRoot, String query) {
        ensureIndexed(workspaceRoot);
        Map<String, List<SymbolEntry>> index = workspaceIndexes.get(workspaceRoot);
        if (index == null || query == null || query.trim().isEmpty()) return Collections.emptyList();
        
        String key = query.trim().toLowerCase();
        
        List<SymbolEntry> exact = index.get(key);
        if (exact != null && !exact.isEmpty()) {
            return exact;
        }

        if (key.length() > 3) {
            List<SymbolEntry> results = new ArrayList<>();
            for (Map.Entry<String, List<SymbolEntry>> entry : index.entrySet()) {
                if (entry.getKey().contains(key)) {
                    results.addAll(entry.getValue());
                }
            }
            return results;
        }

        return Collections.emptyList();
    }
}
