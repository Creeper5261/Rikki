package com.zzf.codeagent.provider;

import java.util.Map;
import java.util.HashMap;
import java.util.List;

/**
 * Provider 转换工具 (对齐 OpenCode ProviderTransform)
 */
public class ProviderTransform {

    public static Double temperature(ModelInfo model) {
        String id = model.getId().toLowerCase();
        if (id.contains("qwen")) return 0.55;
        if (id.contains("claude")) return null;
        if (id.contains("gemini")) return 1.0;
        if (id.contains("glm-4.6") || id.contains("glm-4.7")) return 1.0;
        if (id.contains("minimax-m2")) return 1.0;
        if (id.contains("kimi-k2")) {
            if (id.contains("thinking") || id.contains("k2.")) {
                return 1.0;
            }
            return 0.6;
        }
        return null;
    }

    public static Double topP(ModelInfo model) {
        String id = model.getId().toLowerCase();
        if (id.contains("qwen")) return 1.0;
        if (id.contains("minimax-m2") || id.contains("kimi-k2.5") || id.contains("gemini")) {
            return 0.95;
        }
        return null;
    }

    public static Integer topK(ModelInfo model) {
        String id = model.getId().toLowerCase();
        if (id.contains("minimax-m2")) {
            if (id.contains("m2.1")) return 40;
            return 20;
        }
        if (id.contains("gemini")) return 64;
        return null;
    }

    public static Map<String, Object> options(ModelInfo model, String sessionID) {
        Map<String, Object> result = new HashMap<>();
        
        if ("openai".equals(model.getProviderID()) || 
            (model.getApi() != null && ("@ai-sdk/openai".equals(model.getApi().getNpm()) || "@ai-sdk/github-copilot".equals(model.getApi().getNpm())))) {
            result.put("store", false);
        }

        if (model.getApi() != null && "@openrouter/ai-sdk-provider".equals(model.getApi().getNpm())) {
            result.put("usage", Map.of("include", true));
            if (model.getApi().getId().contains("gemini-3")) {
                result.put("reasoning", Map.of("effort", "high"));
            }
        }

        if ("baseten".equals(model.getProviderID()) || 
            ("opencode".equals(model.getProviderID()) && model.getApi() != null && 
             List.of("kimi-k2-thinking", "glm-4.6").contains(model.getApi().getId()))) {
            result.put("chat_template_args", Map.of("enable_thinking", true));
        }

        if (List.of("zai", "zhipuai").contains(model.getProviderID()) && 
            model.getApi() != null && "@ai-sdk/openai-compatible".equals(model.getApi().getNpm())) {
            result.put("thinking", Map.of("type", "enabled", "clear_thinking", false));
        }

        if ("openai".equals(model.getProviderID())) {
            result.put("promptCacheKey", sessionID);
        }

        if (model.getApi() != null && ("@ai-sdk/google".equals(model.getApi().getNpm()) || "@ai-sdk/google-vertex".equals(model.getApi().getNpm()))) {
            Map<String, Object> thinking = new HashMap<>();
            thinking.put("includeThoughts", true);
            if (model.getApi().getId().contains("gemini-3")) {
                thinking.put("thinkingLevel", "high");
            }
            result.put("thinkingConfig", thinking);
        }

        if (model.getApi() != null && model.getApi().getId().contains("gpt-5") && !model.getApi().getId().contains("gpt-5-chat")) {
            if (!model.getApi().getId().contains("gpt-5-pro")) {
                result.put("reasoningEffort", "medium");
            }
            if (model.getApi().getId().contains("gpt-5.") && !model.getApi().getId().contains("codex") && !"azure".equals(model.getProviderID())) {
                result.put("textVerbosity", "low");
            }
        }

        return result;
    }

    public static Map<String, Object> providerOptions(ModelInfo model, Map<String, Object> options) {
        String npm = model.getApi() != null ? model.getApi().getNpm() : null;
        if (npm == null) return new HashMap<>();

        String key = sdkKey(npm);
        if (key == null) return new HashMap<>();

        Map<String, Object> result = new HashMap<>();
        if (options.containsKey(key)) {
            result.put(key, options.get(key));
        }
        return result;
    }

    private static String sdkKey(String npm) {
        switch (npm) {
            case "@ai-sdk/github-copilot":
            case "@ai-sdk/openai":
            case "@ai-sdk/azure":
                return "openai";
            case "@ai-sdk/amazon-bedrock":
                return "bedrock";
            case "@ai-sdk/anthropic":
            case "@ai-sdk/google-vertex/anthropic":
                return "anthropic";
            case "@ai-sdk/google-vertex":
            case "@ai-sdk/google":
                return "google";
            case "@ai-sdk/gateway":
                return "gateway";
            case "@openrouter/ai-sdk-provider":
                return "openrouter";
            default:
                return null;
        }
    }

    public static List<Map<String, Object>> message(List<Map<String, Object>> msgs, ModelInfo model, Map<String, Object> options) {
        // Simple implementation of normalization
        // In a real scenario, this would handle Anthropic empty messages, Mistral tool call ID formatting, etc.
        return msgs;
    }

    public static Map<String, Object> smallOptions(ModelInfo model) {
        if ("openai".equals(model.getProviderID()) || model.getId().contains("gpt-5")) {
            if (model.getId().contains("5.")) {
                return Map.of("reasoningEffort", "low");
            }
            return Map.of("reasoningEffort", "minimal");
        }
        return new HashMap<>();
    }

    public static int maxOutputTokens(ModelInfo model, Map<String, Object> options, int globalLimit) {
        int modelCap = (model.getLimit() != null && model.getLimit().getOutput() != null) ? model.getLimit().getOutput() : globalLimit;
        return Math.min(modelCap, globalLimit);
    }
}
