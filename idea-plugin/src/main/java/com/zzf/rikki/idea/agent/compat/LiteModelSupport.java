package com.zzf.rikki.idea.agent.compat;

import java.util.AbstractMap;
import java.util.Map;

public final class LiteModelSupport {
    public static final LiteModelSupport INSTANCE = new LiteModelSupport();

    private LiteModelSupport() {
    }

    public ModelCapabilities detectCapabilities(String provider, String model) {
        String normalizedModel = model == null ? "" : model.trim().toLowerCase();
        String normalizedProvider = provider == null ? "" : provider.trim().toUpperCase();
        if ("deepseek-reasoner".equals(normalizedModel)) {
            return new ModelCapabilities("system", null, "max_tokens", true, true);
        }
        if ("OPENAI".equals(normalizedProvider) && (
                "o1".equals(normalizedModel)
                        || "o1-mini".equals(normalizedModel)
                        || "o1-preview".equals(normalizedModel)
        )) {
            return new ModelCapabilities("developer", 1.0, "max_completion_tokens", false, false);
        }
        if ("OPENAI".equals(normalizedProvider) && (
                normalizedModel.startsWith("o3")
                        || normalizedModel.startsWith("o4")
        )) {
            return new ModelCapabilities("system", null, "max_completion_tokens", true, false);
        }
        return new ModelCapabilities();
    }

    public Map.Entry<String, String> parseHistoryLine(String text) {
        if (text == null) {
            return null;
        }
        if (text.startsWith("You:")) {
            return new AbstractMap.SimpleImmutableEntry<>("user", text.substring("You:".length()).trim());
        }
        if (text.startsWith("Agent:")) {
            return new AbstractMap.SimpleImmutableEntry<>("assistant", text.substring("Agent:".length()).trim());
        }
        if (text.startsWith("Assistant:")) {
            return new AbstractMap.SimpleImmutableEntry<>("assistant", text.substring("Assistant:".length()).trim());
        }
        if (text.startsWith("System:")) {
            return new AbstractMap.SimpleImmutableEntry<>("system", text.substring("System:".length()).trim());
        }
        return null;
    }
}
