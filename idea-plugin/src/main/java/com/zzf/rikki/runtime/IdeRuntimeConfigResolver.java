package com.zzf.rikki.runtime;

import com.zzf.rikki.idea.settings.ProviderCatalog;
import com.zzf.rikki.idea.settings.ProviderDescriptor;
import com.zzf.rikki.idea.settings.RikkiCredentials;
import com.zzf.rikki.idea.settings.RikkiSettings;

import java.util.Map;
import java.util.function.Supplier;

public class IdeRuntimeConfigResolver implements RuntimeConfigResolver {
    private final Supplier<RikkiSettings.State> stateSupplier;

    public IdeRuntimeConfigResolver() {
        this(() -> RikkiSettings.getInstance().getState());
    }

    public IdeRuntimeConfigResolver(Supplier<RikkiSettings.State> stateSupplier) {
        this.stateSupplier = stateSupplier;
    }

    @Override
    public RuntimeAgentConfig resolve(Map<String, ?> rawConfig) {
        RikkiSettings.State state = stateSupplier.get();
        String provider = firstNonBlank(asString(rawConfig, "provider"), state.getProvider(), "DEEPSEEK");
        ProviderDescriptor descriptor = ProviderCatalog.chatProvider(provider);
        String model = firstNonBlank(asString(rawConfig, "model"), state.getModelName(), descriptor.defaultModel());
        String explicitBaseUrl = firstNonBlank(asString(rawConfig, "baseUrl"), asString(rawConfig, "customBaseUrl"));
        String baseUrl = explicitBaseUrl.isBlank()
                ? ProviderCatalog.chatBaseUrl(provider, state.getCustomBaseUrl())
                : ProviderCatalog.trimSlashes(explicitBaseUrl);
        String apiKey = RikkiCredentials.get(provider);
        String agent = asString(rawConfig, "agent");
        String language = asString(rawConfig, "language");
        Double temperature = asDouble(rawConfig, "temperature");
        return new RuntimeAgentConfig(
                provider,
                model,
                baseUrl,
                apiKey,
                agent,
                language,
                temperature,
                descriptor.requiresApiKey()
        );
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
