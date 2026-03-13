package com.zzf.rikki.idea.completion;

public interface CompletionClient {
    void streamCompletion(
            CompletionConfigResolver.CompletionConfig config,
            String prefix,
            String suffix,
            String language,
            TokenConsumer onToken
    ) throws Exception;

    @FunctionalInterface
    interface TokenConsumer {
        void onToken(String token) throws Exception;
    }
}
