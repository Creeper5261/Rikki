package com.zzf.codeagent.core.pipeline;

import com.zzf.codeagent.core.rag.search.CodeSearchHit;
import com.zzf.codeagent.core.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import java.util.HashSet;

/**
 * Reranks retrieval results from multiple sources (Vector, Symbol, Keyword).
 * Uses a hybrid scoring strategy to surface the most relevant code snippets.
 */
public class ContextReranker {
    private static final Logger logger = LoggerFactory.getLogger(ContextReranker.class);
    
    // Configurable weights
    private static final double WEIGHT_SYMBOL_HIT = 5.0;
    private static final double WEIGHT_KEYWORD_MATCH = 10.0;
    private static final double WEIGHT_VECTOR_SCORE = 1.0; // Assuming vector score is 0-1
    private static final double WEIGHT_RECENCY = 0.5; // Placeholder

    public List<CodeSearchHit> rerank(String query, List<CodeSearchHit> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return candidates;
        }

        Set<String> queryKeywords = extractKeywords(query);
        logger.debug("rerank.keywords q={} keys={}", StringUtils.truncate(query, 50), queryKeywords);

        for (CodeSearchHit hit : candidates) {
            double score = hit.getScore(); // Start with existing score (e.g. from ES)

            // 1. Symbol Bonus (if it came from SymbolGraph, it usually has kind/name set)
            if (hit.getSymbolName() != null && !hit.getSymbolName().isEmpty()) {
                // If query contains the symbol name explicitly, massive boost
                if (containsIgnoreCase(query, hit.getSymbolName())) {
                    score += WEIGHT_SYMBOL_HIT * 2;
                } else {
                    score += WEIGHT_SYMBOL_HIT;
                }
            }

            // 2. Keyword Matching in Snippet
            // Count how many query keywords appear in the snippet
            String snippet = hit.getSnippet();
            if (snippet != null && !snippet.isEmpty()) {
                int matchCount = 0;
                for (String k : queryKeywords) {
                    if (containsIgnoreCase(snippet, k)) {
                        matchCount++;
                    }
                }
                score += (matchCount * WEIGHT_KEYWORD_MATCH);
            }
            
            // 3. Filename Matching
            // If file path matches query keywords
            for (String k : queryKeywords) {
                if (containsIgnoreCase(hit.getFilePath(), k)) {
                    score += WEIGHT_KEYWORD_MATCH;
                }
            }

            hit.setScore(score);
        }

        // Sort descending
        List<CodeSearchHit> sorted = candidates.stream()
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .collect(Collectors.toList());
        
        if (logger.isDebugEnabled() && !sorted.isEmpty()) {
            logger.debug("rerank.top1 score={} file={}", sorted.get(0).getScore(), sorted.get(0).getFilePath());
        }
        
        return sorted;
    }

    private Set<String> extractKeywords(String query) {
        Set<String> keywords = new HashSet<>();
        if (query == null) return keywords;
        // Simple tokenization: split by non-alphanumeric, filter small words
        String[] tokens = query.split("[^a-zA-Z0-9]+");
        for (String t : tokens) {
            if (t.length() > 2) {
                keywords.add(t.toLowerCase());
            }
        }
        return keywords;
    }

    private boolean containsIgnoreCase(String text, String token) {
        if (text == null || token == null) return false;
        return text.toLowerCase().contains(token.toLowerCase()); // Naive but fast
    }
}
