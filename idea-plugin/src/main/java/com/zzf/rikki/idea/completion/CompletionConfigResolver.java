package com.zzf.rikki.idea.completion;

import com.zzf.rikki.idea.settings.ProviderCatalog;
import com.zzf.rikki.idea.settings.RikkiSettings;

public final class CompletionConfigResolver {
    private CompletionConfigResolver() {
    }

    public static CompletionConfig resolve(RikkiSettings.State state) {
        String provider = state.completionEffectiveProvider();
        return new CompletionConfig(
                state.isCompletionEnabled(),
                provider,
                state.completionEffectiveModel(),
                state.completionEffectiveBaseUrl(),
                state.completionEffectiveApiKey(),
                ProviderCatalog.completionUsesFim(provider)
        );
    }

    public record CompletionConfig(
            boolean enabled,
            String provider,
            String model,
            String baseUrl,
            String apiKey,
            boolean useFim
    ) {
        public boolean requiresApiKey() {
            return !"OLLAMA".equals(provider);
        }
    }
}
