package com.zzf.codeagent.core.pipeline;

import com.zzf.codeagent.core.rag.search.CodeSearchHit;
import com.zzf.codeagent.core.rag.search.HybridCodeSearchService;
import com.zzf.codeagent.core.util.StringUtils;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/**
 * A deterministic pipeline for smart retrieval and question answering.
 * Bypasses the ReAct agent loop for lower latency and higher efficiency.
 * <p>
 * Workflow:
 * 1. Parallel Retrieval (Vector Search + Symbol Search)
 * 2. Aggregation & Reranking (Simple merge for now)
 * 3. RAG Generation (Direct LLM call)
 */
public class SmartRetrievalPipeline {
    private static final Logger logger = LoggerFactory.getLogger(SmartRetrievalPipeline.class);
    private static final Pattern SENSITIVE_KV = Pattern.compile("(?i)(password|passwd|secret|token|apikey|accesskey|secretkey)\\s*[:=]\\s*([\"']?)([^\"'\\\\\\r\\n\\s]{1,160})\\2");
    private static final Pattern SENSITIVE_JSON_KV = Pattern.compile("(?i)(\"(?:password|passwd|secret|token|apiKey|accessKey|secretKey)\"\\s*:\\s*\")([^\"]{1,160})(\")");

    private final HybridCodeSearchService hybridSearch;
    private final ContextReranker reranker;
    private final OpenAiChatModel model;
    private final String workspaceRoot;

    public SmartRetrievalPipeline(HybridCodeSearchService hybridSearch, OpenAiChatModel model, String workspaceRoot) {
        this.hybridSearch = hybridSearch;
        this.reranker = new ContextReranker();
        this.model = model;
        this.workspaceRoot = workspaceRoot;
    }

    public String run(String query) {
        long t0 = System.nanoTime();
        logger.info("pipeline.start queryChars={} workspaceRoot={}", query == null ? 0 : query.length(), workspaceRoot);
        
        // 1. Unified Hybrid Retrieval
        // Ask for more candidates (20) to allow Reranker to select the best 8
        List<CodeSearchHit> hits = hybridSearch.search(workspaceRoot, query, 20);

        // 2. Context Reranking
        // Rerank using ContextReranker
        List<CodeSearchHit> rankedHits = reranker.rerank(query, hits);
        
        // Top-K selection (Top 8)
        List<CodeSearchHit> finalHits = rankedHits.stream()
                .limit(8)
                .collect(Collectors.toList());

        long searchMs = (System.nanoTime() - t0) / 1_000_000L;
        logger.info("pipeline.retrieval stats hits={} searchMs={}", finalHits.size(), searchMs);

        // 3. Generation
        if (finalHits.isEmpty()) {
            return "I could not find any relevant code in the workspace to answer your question. (Search returned 0 hits)";
        }

        // Dynamic Context Builder (SOTA Phase 2)
        DynamicContextBuilder contextBuilder = new DynamicContextBuilder(workspaceRoot);
        List<String> activeFiles = finalHits.stream()
                .map(CodeSearchHit::getFilePath)
                .collect(Collectors.toList());
        String projectStructure = contextBuilder.build(activeFiles);

        String context = formatContext(finalHits);
        String prompt = buildPrompt(query, context, projectStructure);
        logger.info("pipeline.prompt chars={} activeFiles={} hits={}", prompt.length(), activeFiles.size(), finalHits.size());
        logger.info("pipeline.prompt.full chars={} prompt={}", prompt.length(), maskSensitive(prompt));
        
        long genT0 = System.nanoTime();
        String answer = model.chat(prompt);
        long genMs = (System.nanoTime() - genT0) / 1_000_000L;
        
        logger.info("pipeline.generation stats genMs={}", genMs);
        return answer;
    }
    
    private String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash == -1 ? path : path.substring(lastSlash + 1);
    }
    
    private String formatContext(List<CodeSearchHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (CodeSearchHit hit : hits) {
            sb.append("File: ").append(hit.getFilePath()).append("\n");
            sb.append("Lines: ").append(hit.getStartLine()).append("-").append(hit.getEndLine()).append("\n");
            sb.append("```\n").append(hit.getSnippet()).append("\n```\n\n");
        }
        return sb.toString();
    }

    private String buildPrompt(String query, String context, String projectStructure) {
        return "You are a helpful coding assistant. Answer the user's question based STRICTLY on the provided code context.\n" +
               "If the context doesn't contain the answer, admit it.\n\n" +
               "Do not use 'thought' or 'plan'. Directly output the answer.\n" +
               "Be concise and technical.\n\n" +
               "Project Structure (Focused):\n" +
               projectStructure +
               "\n\n" +
               "Code Context:\n" +
               context +
               "\n\n" +
               "User Question: " + query;
    }

    private String maskSensitive(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        out = SENSITIVE_JSON_KV.matcher(out).replaceAll("$1******$3");
        out = SENSITIVE_KV.matcher(out).replaceAll("$1:******");
        return out;
    }
}
