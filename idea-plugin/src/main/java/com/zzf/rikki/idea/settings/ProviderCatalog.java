package com.zzf.rikki.idea.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProviderCatalog {
    private static final ProviderDescriptor CHAT_DEEPSEEK = new ProviderDescriptor(
            "DEEPSEEK",
            "DeepSeek",
            "https://api.deepseek.com/v1",
            "deepseek-chat",
            List.of("deepseek-chat", "deepseek-reasoner"),
            false,
            true,
            false,
            false
    );
    private static final ProviderDescriptor CHAT_OPENAI = new ProviderDescriptor(
            "OPENAI",
            "OpenAI (GPT)",
            "https://api.openai.com/v1",
            "gpt-4o",
            List.of("gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4o", "gpt-4o-mini", "o3", "o3-mini", "o4-mini"),
            false,
            true,
            false,
            false
    );
    private static final ProviderDescriptor CHAT_GEMINI = new ProviderDescriptor(
            "GEMINI",
            "Google (Gemini)",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash",
            List.of("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-pro", "gemini-1.5-flash"),
            false,
            true,
            false,
            false
    );
    private static final ProviderDescriptor CHAT_MOONSHOT = new ProviderDescriptor(
            "MOONSHOT",
            "Moonshot (Kimi)",
            "https://api.moonshot.cn/v1",
            "moonshot-v1-8k",
            List.of("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-latest"),
            false,
            true,
            false,
            false
    );
    private static final ProviderDescriptor CHAT_OLLAMA = new ProviderDescriptor(
            "OLLAMA",
            "Ollama (local, no key)",
            "http://localhost:11434/v1",
            "qwen2.5-coder:7b",
            List.of("qwen2.5-coder:7b", "qwen2.5-coder:14b", "qwen2.5-coder:32b", "llama3.2", "llama3.1", "deepseek-coder-v2", "codellama"),
            true,
            false,
            false,
            false
    );
    private static final ProviderDescriptor CHAT_CUSTOM = new ProviderDescriptor(
            "CUSTOM",
            "Custom",
            "",
            "",
            List.of(),
            true,
            true,
            false,
            false
    );

    private static final ProviderDescriptor COMPLETION_SAME_AS_CHAT = new ProviderDescriptor(
            "SAME_AS_CHAT",
            "Same as Chat Provider",
            "",
            "",
            List.of(),
            false,
            false,
            false,
            true
    );
    private static final ProviderDescriptor COMPLETION_DEEPSEEK = new ProviderDescriptor(
            "DEEPSEEK",
            "DeepSeek  [FIM, beta endpoint]",
            "https://api.deepseek.com/beta",
            "deepseek-chat",
            List.of("deepseek-chat"),
            false,
            true,
            true,
            false
    );
    private static final ProviderDescriptor COMPLETION_OPENAI = new ProviderDescriptor(
            "OPENAI",
            "OpenAI  [chat format]",
            "https://api.openai.com/v1",
            "gpt-4.1-nano",
            List.of("gpt-4.1-nano", "gpt-4.1-mini", "gpt-4o-mini"),
            false,
            true,
            false,
            false
    );
    private static final ProviderDescriptor COMPLETION_GEMINI = new ProviderDescriptor(
            "GEMINI",
            "Google (Gemini)  [chat format]",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.0-flash-lite",
            List.of("gemini-2.0-flash-lite", "gemini-2.0-flash", "gemini-1.5-flash"),
            false,
            true,
            false,
            false
    );
    private static final ProviderDescriptor COMPLETION_MOONSHOT = new ProviderDescriptor(
            "MOONSHOT",
            "Moonshot (Kimi)  [chat format]",
            "https://api.moonshot.cn/v1",
            "moonshot-v1-8k",
            List.of("moonshot-v1-8k", "moonshot-v1-32k"),
            false,
            true,
            false,
            false
    );
    private static final ProviderDescriptor COMPLETION_OLLAMA = new ProviderDescriptor(
            "OLLAMA",
            "Ollama  [FIM, local]",
            "http://localhost:11434/v1",
            "qwen2.5-coder:7b",
            List.of("qwen2.5-coder:7b", "qwen2.5-coder:14b", "qwen2.5-coder:32b", "codellama"),
            true,
            false,
            true,
            false
    );
    private static final ProviderDescriptor COMPLETION_CUSTOM = new ProviderDescriptor(
            "CUSTOM",
            "Custom",
            "",
            "",
            List.of(),
            true,
            true,
            false,
            false
    );

    private static final List<ProviderDescriptor> CHAT_PROVIDERS = List.of(
            CHAT_DEEPSEEK,
            CHAT_OPENAI,
            CHAT_GEMINI,
            CHAT_MOONSHOT,
            CHAT_OLLAMA,
            CHAT_CUSTOM
    );
    private static final List<ProviderDescriptor> COMPLETION_PROVIDERS = List.of(
            COMPLETION_SAME_AS_CHAT,
            COMPLETION_DEEPSEEK,
            COMPLETION_OPENAI,
            COMPLETION_GEMINI,
            COMPLETION_MOONSHOT,
            COMPLETION_OLLAMA,
            COMPLETION_CUSTOM
    );
    private static final Map<String, ProviderDescriptor> CHAT_BY_NAME = indexByName(CHAT_PROVIDERS);
    private static final Map<String, ProviderDescriptor> COMPLETION_BY_NAME = indexByName(COMPLETION_PROVIDERS);

    private ProviderCatalog() {
    }

    public static List<ProviderDescriptor> chatProviders() {
        return CHAT_PROVIDERS;
    }

    public static List<ProviderDescriptor> completionProviders() {
        return COMPLETION_PROVIDERS;
    }

    public static ProviderDescriptor chatProvider(String name) {
        return CHAT_BY_NAME.getOrDefault(normalize(name), CHAT_DEEPSEEK);
    }

    public static ProviderDescriptor completionProvider(String name) {
        return COMPLETION_BY_NAME.getOrDefault(normalize(name), COMPLETION_SAME_AS_CHAT);
    }

    public static ProviderDescriptor effectiveCompletionProvider(RikkiSettings.State state) {
        ProviderDescriptor configured = completionProvider(state.getCompletionProvider());
        return configured.sameAsChat() ? chatProvider(state.getProvider()) : configured;
    }

    public static String chatBaseUrl(String providerName, String customBaseUrl) {
        ProviderDescriptor provider = chatProvider(providerName);
        if (provider.urlEditable()) {
            String value = trimSlashes(customBaseUrl);
            return value.isBlank() ? provider.defaultBaseUrl() : value;
        }
        return provider.defaultBaseUrl();
    }

    public static String completionBaseUrl(RikkiSettings.State state) {
        ProviderDescriptor effective = effectiveCompletionProvider(state);
        if (effective.sameAsChat()) {
            return state.currentBaseUrl();
        }
        if ("CUSTOM".equals(effective.name()) || "OLLAMA".equals(effective.name())) {
            String candidate = trimSlashes(state.getCompletionCustomBaseUrl());
            if (candidate.isBlank() && effective.name().equals(state.getProvider())) {
                candidate = trimSlashes(state.getCustomBaseUrl());
            }
            return candidate.isBlank() ? effective.defaultBaseUrl() : candidate;
        }
        return effective.defaultBaseUrl();
    }

    public static boolean completionUsesFim(String providerName) {
        return completionProvider(providerName).fim();
    }

    public static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim().toUpperCase();
    }

    public static String trimSlashes(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("/+$", "");
    }

    private static Map<String, ProviderDescriptor> indexByName(List<ProviderDescriptor> providers) {
        LinkedHashMap<String, ProviderDescriptor> index = new LinkedHashMap<>();
        for (ProviderDescriptor provider : providers) {
            index.put(provider.name(), provider);
        }
        return Map.copyOf(index);
    }
}
