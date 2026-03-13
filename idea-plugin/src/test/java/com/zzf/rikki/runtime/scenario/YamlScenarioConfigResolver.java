package com.zzf.rikki.runtime.scenario;

import com.zzf.rikki.idea.settings.ProviderCatalog;
import com.zzf.rikki.idea.settings.ProviderDescriptor;
import com.zzf.rikki.runtime.RuntimeAgentConfig;
import com.zzf.rikki.runtime.RuntimeConfigResolver;

import java.util.Map;
import java.util.function.Function;

public final class YamlScenarioConfigResolver implements RuntimeConfigResolver {
    private final Function<String, String> envLookup;

    public YamlScenarioConfigResolver(Function<String, String> envLookup) {
        this.envLookup = envLookup;
    }

    @Override
    public RuntimeAgentConfig resolve(Map<String, ?> rawConfig) {
        String provider = firstNonBlank(asString(rawConfig, "provider"), "DEEPSEEK");
        ProviderDescriptor descriptor = ProviderCatalog.chatProvider(provider);
        String model = firstNonBlank(asString(rawConfig, "model"), descriptor.defaultModel());
        String baseUrl = firstNonBlank(
                asString(rawConfig, "baseUrl"),
                ProviderCatalog.chatBaseUrl(provider, "")
        );
        String apiKey = firstNonBlank(asString(rawConfig, "apiKey"), readEnv(asString(rawConfig, "apiKeyEnv")));
        return new RuntimeAgentConfig(
                provider,
                model,
                baseUrl,
                apiKey,
                asString(rawConfig, "agent"),
                asString(rawConfig, "language"),
                asDouble(rawConfig, "temperature"),
                descriptor.requiresApiKey()
        );
    }

    private String readEnv(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String value = envLookup.apply(name.trim());
        return value == null ? "" : value;
    }

    private static String asString(Map<String, ?> map, String key) {
        if (map == null) {
            return "";
        }
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static Double asDouble(Map<String, ?> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
