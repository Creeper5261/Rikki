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
    
    // Map<WorkspaceRoot, Map<FilePath, Set<DependentFilePath>>>
    private final Map<String, Map<String, Set<String>>> workspaceGraphs = new ConcurrentHashMap<>();

    // Map<WorkspaceRoot, Map<FilePath, Double>>
    private final Map<String, Map<String, Double>> workspacePageRanks = new ConcurrentHashMap<>();

    private final JavaParserCodeChunker chunker = new JavaParserCodeChunker();
    private final RepoStructureService repoStructureService;
    
    // Ignore patterns for indexing
    private static final Set<String> IGNORED_DIRS = Set.of(
        ".git", ".idea", ".gradle", "build", "target", "node_modules", "dist", "out"
    );

    public SymbolGraphService(RepoStructureService repoStructureService) {
        this.repoStructureService = repoStructureService;
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
            Map<String, Set<String>> rawDeps = new ConcurrentHashMap<>();

            try (Stream<Path> paths = Files.walk(Paths.get(workspaceRoot))) {
                paths.filter(p -> p.toString().endsWith(".java"))
                     .filter(p -> !isIgnored(p, workspaceRoot))
                     .forEach(p -> indexFile(p, index, rawDeps, workspaceRoot));
            } catch (IOException e) {
                logger.error("symbol_graph.index.fail err={}", e.toString());
            }
            
            workspaceIndexes.put(workspaceRoot, index);
            
            // Build Graph
            Map<String, Set<String>> graph = buildGraph(index, rawDeps);
            workspaceGraphs.put(workspaceRoot, graph);
            
            // Compute PageRank (Static)
            Map<String, Double> pageRank = computePageRank(graph, Collections.emptySet());
            workspacePageRanks.put(workspaceRoot, pageRank);

            long tookMs = (System.nanoTime() - t0) / 1_000_000L;
            logger.info("symbol_graph.index.done root={} symbols={} edges={} ranks={} tookMs={}", workspaceRoot, index.size(), graph.size(), pageRank.size(), tookMs);
        }
    }

    private Map<String, Set<String>> buildGraph(Map<String, List<SymbolEntry>> index, Map<String, Set<String>> rawDeps) {
        Map<String, Set<String>> graph = new ConcurrentHashMap<>();
        
        rawDeps.forEach((filePath, deps) -> {
            Set<String> resolvedFiles = new HashSet<>();
            for (String dep : deps) {
                // Try exact match (Qualified Name)
                List<SymbolEntry> targets = index.get(dep.toLowerCase());
                if (targets != null) {
                    for (SymbolEntry target : targets) {
                        if (!target.filePath.equals(filePath)) {
                            resolvedFiles.add(target.filePath);
                        }
                    }
                }
            }
            if (!resolvedFiles.isEmpty()) {
                graph.put(filePath, resolvedFiles);
            }
        });
        return graph;
    }

    /**
     * Computes PageRank (Standard or Personalized).
     * @param graph The dependency graph
     * @param focusNodes If non-empty, implements Personalized PageRank (teleport to focus nodes).
     *                   If empty, implements Standard PageRank (teleport to all nodes uniformly).
     */
    private Map<String, Double> computePageRank(Map<String, Set<String>> graph, Set<String> focusNodes) {
        Map<String, Double> scores = new HashMap<>();
        Set<String> allNodes = new HashSet<>(graph.keySet());
        Map<String, Integer> inDegrees = new HashMap<>();

        for (Set<String> targets : graph.values()) {
            allNodes.addAll(targets);
            for (String target : targets) {
                inDegrees.put(target, inDegrees.getOrDefault(target, 0) + 1);
            }
        }

        int N = allNodes.size();
        if (N == 0) return scores;

        // Calculate Static Node Weights (Heuristics)
        Map<String, Double> nodeWeights = new HashMap<>();
        for (String node : allNodes) {
            double weight = 1.0;
            String name = extractSimpleName(node);

            // 1. Boost CamelCase (Class names)
            if (name.matches("^[A-Z][a-zA-Z0-9]*$")) {
                weight *= 1.5;
            }

            // 2. Penalize Common Utils (High In-Degree Hubs)
            int inDegree = inDegrees.getOrDefault(node, 0);
            if (N > 20 && (inDegree > N * 0.1 || inDegree > 20)) {
                weight *= 0.5;
            }

            // 3. Penalize Private/Internal
            if (node.contains("/internal/") || node.contains("/impl/") || name.startsWith("_")) {
                weight *= 0.8;
            }
            
            nodeWeights.put(node, weight);
        }

        // Validate focus nodes
        Set<String> validFocus = new HashSet<>();
        if (focusNodes != null) {
            for (String f : focusNodes) {
                if (allNodes.contains(f)) validFocus.add(f);
            }
        }
        boolean personalized = !validFocus.isEmpty();

        // Initialize scores
        double initialScore = 1.0 / N;
        if (personalized) {
            // Initialize focus nodes with higher mass? 
            // Standard P-PR initializes uniformly or concentrated. Iterations will converge.
            // Uniform is fine.
        }
        for (String node : allNodes) {
            scores.put(node, initialScore);
        }

        double damping = 0.85;
        int iterations = 10;

        for (int i = 0; i < iterations; i++) {
            Map<String, Double> newScores = new HashMap<>();
            
            // Random Surfer Teleportation Component
            // If personalized: Teleport to focus nodes
            // If standard: Teleport to all nodes (weighted by nodeWeights?) -> Usually uniform 1/N
            
            // We use nodeWeights for "Teleport Target Distribution" if standard?
            // Aider uses nodeWeights for *Graph Edge Weights* (which we do in distribution loop).
            // For Teleport, Aider splits probability among all nodes (or focus nodes).
            
            double teleportMass = (1.0 - damping);
            
            if (personalized) {
                // Teleport only to focus nodes
                double perFocus = teleportMass / validFocus.size();
                for (String node : allNodes) {
                    newScores.put(node, validFocus.contains(node) ? perFocus : 0.0);
                }
            } else {
                // Teleport to all nodes uniformly
                double perNode = teleportMass / N;
                for (String node : allNodes) {
                    newScores.put(node, perNode);
                }
            }

            // Distribute scores via edges
            for (String node : allNodes) {
                double currentScore = scores.get(node);
                Set<String> outgoing = graph.get(node);
                
                if (outgoing == null || outgoing.isEmpty()) {
                    // Dangling Node: Surfer jumps randomly
                    // In P-PR, jumps to Focus Nodes
                    if (personalized) {
                        double contribution = damping * currentScore / validFocus.size();
                        for (String target : validFocus) {
                            newScores.put(target, newScores.get(target) + contribution);
                        }
                    } else {
                        double contribution = damping * currentScore / N;
                        for (String target : allNodes) {
                            newScores.put(target, newScores.get(target) + contribution);
                        }
                    }
                } else {
                    // Follow Edges
                    double totalWeight = 0.0;
                    for (String target : outgoing) {
                        totalWeight += nodeWeights.getOrDefault(target, 1.0);
                    }
                    if (totalWeight == 0) totalWeight = 1.0;

                    for (String target : outgoing) {
                        double targetWeight = nodeWeights.getOrDefault(target, 1.0);
                        double share = targetWeight / totalWeight;
                        double contribution = damping * currentScore * share;
                        newScores.put(target, newScores.getOrDefault(target, 0.0) + contribution);
                    }
                }
            }
            scores = newScores;
        }
        return scores;
    }

    public Map<String, Double> getPersonalizedPageRank(String workspaceRoot, Set<String> focusFiles) {
        ensureIndexed(workspaceRoot);
        Map<String, Set<String>> graph = workspaceGraphs.get(workspaceRoot);
        if (graph == null) return Collections.emptyMap();
        
        // Normalize focus files
        Set<String> validFocus = new HashSet<>();
        if (focusFiles != null) {
            for (String f : focusFiles) {
                String rel = f.replace('\\', '/');
                // Try to relativize if absolute
                 try {
                    Path rootPath = Paths.get(workspaceRoot);
                    Path targetPath = Paths.get(f);
                    if (targetPath.isAbsolute() && f.startsWith(workspaceRoot)) {
                         rel = rootPath.relativize(targetPath).toString().replace('\\', '/');
                    }
                } catch (Exception ignore) {}
                validFocus.add(rel);
            }
        }
        
        return computePageRank(graph, validFocus);
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

    private void indexFile(Path path, Map<String, List<SymbolEntry>> index, Map<String, Set<String>> rawDeps, String root) {
        try {
            String content = Files.readString(path);
            Path relPathObj = Paths.get(root).relativize(path);
            String relPath = relPathObj.toString().replace('\\', '/');
            List<CodeChunk> chunks = chunker.chunk(path, content);
            
            for (CodeChunk chunk : chunks) {
                String simpleName = extractSimpleName(chunk.getSymbolName());
                SymbolEntry entry = new SymbolEntry(
                        simpleName,
                        chunk.getSymbolKind(), // Fixed: getKind -> getSymbolKind
                        chunk.getSignature(),
                        relPath,
                        chunk.getStartLine(),
                        chunk.getEndLine()
                );
                
                index.computeIfAbsent(simpleName.toLowerCase(), k -> new ArrayList<>()).add(entry);
                if (!simpleName.equals(chunk.getSymbolName())) {
                     index.computeIfAbsent(chunk.getSymbolName().toLowerCase(), k -> new ArrayList<>()).add(entry);
                }
            }

            // Extract Dependencies
            if (repoStructureService != null) {
                Set<String> deps = repoStructureService.extractDependencies(content);
                rawDeps.put(relPath, deps);
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

    public Set<String> getRelatedFiles(String workspaceRoot, String filePath) {
        ensureIndexed(workspaceRoot);
        Map<String, Set<String>> graph = workspaceGraphs.get(workspaceRoot);
        if (graph == null) return Collections.emptySet();
        
        String relPath = filePath.replace('\\', '/');
        try {
            Path rootPath = Paths.get(workspaceRoot);
            Path targetPath = Paths.get(filePath);
            if (targetPath.isAbsolute() && filePath.startsWith(workspaceRoot)) {
                 relPath = rootPath.relativize(targetPath).toString().replace('\\', '/');
            }
        } catch (Exception e) {
            // ignore
        }

        Set<String> related = graph.getOrDefault(relPath, Collections.emptySet());
        if (related.isEmpty()) return Collections.emptySet();

        // Sort by PageRank
        Map<String, Double> pageRanks = workspacePageRanks.get(workspaceRoot);
        if (pageRanks != null) {
            List<String> sorted = new ArrayList<>(related);
            sorted.sort((a, b) -> Double.compare(pageRanks.getOrDefault(b, 0.0), pageRanks.getOrDefault(a, 0.0)));
            return new LinkedHashSet<>(sorted);
        }
        
        return related;
    }
}
