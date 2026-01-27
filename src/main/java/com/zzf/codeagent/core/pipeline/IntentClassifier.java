package com.zzf.codeagent.core.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.codeagent.core.util.JsonUtils;
import com.zzf.codeagent.core.util.StringUtils;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Classifies user intent into distinct categories to route requests to the appropriate pipeline.
 * <p>
 * Categories:
 * - EXPLAIN: User wants an explanation of concepts, code, or architecture.
 * - SEARCH: User wants to find specific files, symbols, or code snippets.
 * - CODE_GEN: User wants to generate new code or refactor existing code.
 * - COMPLEX_TASK: Complex requests requiring multi-step reasoning (Default fallback).
 * - GENERAL: Chit-chat or simple questions not requiring codebase context.
 */
public class IntentClassifier {
    private static final Logger logger = LoggerFactory.getLogger(IntentClassifier.class);

    // Level 1: Rule Engine (Regex)
    private static final Pattern PATTERN_SEARCH = Pattern.compile("(?i)^(find|search|lookup|where is|locate|grep)\\b.*");
    private static final Pattern PATTERN_EXPLAIN = Pattern.compile("(?i)^(explain|what is|how does|describe|analyze)\\b.*");
    private static final Pattern PATTERN_CODE_GEN = Pattern.compile("(?i)^(generate|create|write|implement|refactor|fix|optimize)\\b.*");

    private static final String CLASSIFY_PROMPT = "Classify the following user query into exactly one of these categories:\n" +
            "- SEARCH: User wants to find specific files, symbols, or code snippets.\n" +
            "- EXPLAIN: User wants an explanation of concepts, code, or architecture.\n" +
            "- CODE_GEN: User wants to generate new code or refactor existing code.\n" +
            "- GENERAL: Chit-chat or simple questions not requiring codebase context.\n" +
            "- COMPLEX_TASK: Complex requests requiring multi-step reasoning or if you are unsure.\n" +
            "\n" +
            "Output strictly in JSON format: {\"intent\": \"CATEGORY\"}\n" +
            "User Query: ";

    private final OpenAiChatModel model;
    private final ObjectMapper mapper;

    public IntentClassifier(OpenAiChatModel model, ObjectMapper mapper) {
        this.model = model;
        this.mapper = mapper;
    }

    public enum Intent {
        SEARCH,
        EXPLAIN,
        CODE_GEN,
        GENERAL,
        COMPLEX_TASK
    }

    public Intent classify(String query) {
        if (query == null || query.trim().isEmpty()) {
            return Intent.GENERAL;
        }

        String q = query.trim();

        // Level 1: Fast Path (Rules)
        if (PATTERN_SEARCH.matcher(q).matches()) {
            logger.info("intent.classify.rule match=SEARCH q={}", StringUtils.truncate(q, 50));
            return Intent.SEARCH;
        }
        if (PATTERN_EXPLAIN.matcher(q).matches()) {
            logger.info("intent.classify.rule match=EXPLAIN q={}", StringUtils.truncate(q, 50));
            return Intent.EXPLAIN;
        }
        // CODE_GEN rules are risky as they might be complex tasks, so we default to COMPLEX_TASK or let LLM decide
        // But for simple "create a file" it might be okay. Let's skip rule for CODE_GEN to be safe.

        // Level 2: LLM Classification
        try {
            long t0 = System.nanoTime();
            String prompt = CLASSIFY_PROMPT + StringUtils.truncate(q, 1000);
            String raw = model.chat(prompt);
            String json = JsonUtils.extractFirstJsonObject(raw);
            JsonNode node = mapper.readTree(json);
            String intentStr = node.path("intent").asText("COMPLEX_TASK").toUpperCase();
            
            Intent intent;
            try {
                intent = Intent.valueOf(intentStr);
            } catch (IllegalArgumentException e) {
                intent = Intent.COMPLEX_TASK;
            }
            
            long tookMs = (System.nanoTime() - t0) / 1_000_000L;
            logger.info("intent.classify.llm intent={} tookMs={} q={}", intent, tookMs, StringUtils.truncate(q, 50));
            return intent;
        } catch (Exception e) {
            logger.warn("intent.classify.fail err={}", e.toString());
            return Intent.COMPLEX_TASK; // Fallback to safe mode
        }
    }
}
