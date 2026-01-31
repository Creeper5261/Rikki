package com.zzf.codeagent.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.core.rag.index.RepoStructureService;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages long-term memory compression for the Agent.
 * Summarizes old history turns to free up context window while retaining key facts.
 */
public class MemoryManager {
    private static final Logger logger = LoggerFactory.getLogger(MemoryManager.class);
    private final OpenAiChatModel model;
    private final RepoStructureService repoStructureService;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final int CHARS_PER_TOKEN_EST = 4;

    public MemoryManager(OpenAiChatModel model, RepoStructureService repoStructureService) {
        this.model = model;
        this.repoStructureService = repoStructureService;
    }

    public String summarize(String content) {
        if (content == null || content.isEmpty()) return "";

        String prompt = "You are a Memory Manager for a coding agent. " +
                "Summarize the following interaction history into a concise text. " +
                "Focus on:\n" +
                "1. Verified facts (e.g., 'File X exists', 'Method Y does Z').\n" +
                "2. Completed steps and their outcomes.\n" +
                "3. Key errors encountered (briefly).\n" +
                "Ignore internal thought process unless critical. " +
                "Keep it under 500 characters. " +
                "Do NOT include the original text, only the summary.\n\n" +
                "HISTORY:\n" + content;

        try {
            // Use a low temperature for factual summary
            // Note: Model configuration is usually handled at instantiation, 
            // but we rely on the passed model instance.
            return model.generate(prompt);
        } catch (Exception e) {
            logger.warn("memory.summarize.fail", e);
            return "";
        }
    }

    /**
     * Prunes stale tool outputs from history to save tokens.
     * Inspired by OpenCode's compaction logic.
     * Keeps the most recent 'keepTokens' (approx) intact.
     * For older entries, replaces bulky tool outputs (read_file, ls) with concise placeholders.
     */
    public void pruneToolOutputs(List<String> history, int keepTokens) {
        if (history == null || history.isEmpty()) return;

        int totalChars = 0;
        int prunedCount = 0;
        int keepChars = keepTokens * CHARS_PER_TOKEN_EST;

        // Traverse backwards
        for (int i = history.size() - 1; i >= 0; i--) {
            String line = history.get(i);
            totalChars += line.length();

            // If we are within the safe zone, skip pruning
            if (totalChars <= keepChars) {
                continue;
            }

            // Outside safe zone: Prune heavy tool outputs
            if (line.startsWith("OBS ")) {
                String content = line.substring(4); // Remove "OBS " prefix
                if (content.length() > 500) { // Only prune if substantial
                    try {
                        JsonNode node = mapper.readTree(content);
                        String tool = node.path("tool").asText("");
                        
                        // Prune READ_FILE and LIST_FILES content
                        if ("READ_FILE".equals(tool) || "LIST_FILES".equals(tool) || "GREP".equals(tool)) {
                            if (node instanceof ObjectNode) {
                                ObjectNode on = (ObjectNode) node;
                                JsonNode result = on.path("result");
                                if (result instanceof ObjectNode) {
                                    ObjectNode resObj = (ObjectNode) result;
                                    // Replace content with placeholder or AST skeleton
                                    if (resObj.has("content")) {
                                        String originalContent = resObj.get("content").asText();
                                        String replacement = "... (Pruned history: File content removed to save tokens) ...";
                                        
                                        // Hybrid Context Strategy: Degrade to AST Skeleton for Java files (Aider style)
                                        // Only attempt if content is substantial and looks like Java
                                        if (repoStructureService != null && originalContent.length() > 500 && (originalContent.contains("class ") || originalContent.contains("interface "))) {
                                             String skeleton = repoStructureService.generateSkeleton(originalContent);
                                             // Only use skeleton if it's significantly smaller than original (compression ratio > 2)
                                             if (!skeleton.isEmpty() && skeleton.length() < originalContent.length() / 2) {
                                                 replacement = "(Pruned to AST Skeleton):\n" + skeleton;
                                             }
                                        }
                                        
                                        resObj.put("content", replacement);
                                    }
                                    if (resObj.has("files")) {
                                        resObj.put("files", "... (Pruned history: File list removed) ...");
                                    }
                                    if (resObj.has("matches")) {
                                        resObj.put("matches", "... (Pruned history: Grep matches removed) ...");
                                    }
                                    
                                    // Update the history line
                                    String prunedJson = mapper.writeValueAsString(on);
                                    history.set(i, "OBS " + prunedJson);
                                    prunedCount++;
                                }
                            }
                        }
                    } catch (Exception e) {
                        // If parsing fails, ignore
                    }
                }
            }
        }
        
        if (prunedCount > 0) {
            logger.info("memory.prune.done prunedCount={} historySize={}", prunedCount, history.size());
        }
    }
}
