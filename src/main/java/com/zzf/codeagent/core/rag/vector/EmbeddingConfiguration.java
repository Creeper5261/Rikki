package com.zzf.codeagent.core.rag.vector;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;

@Configuration
public class EmbeddingConfiguration {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddingConfiguration.class);

    @Bean
    public EmbeddingService embeddingService(
            ObjectMapper mapper,
            @Value("${embedding.provider:dashscope}") String provider,
            @Value("${embedding.api.url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${embedding.api.key:}") String apiKey,
            @Value("${embedding.api.model:text-embedding-v4}") String model,
            @Value("${embedding.api.dimension:2048}") int dimension,
            @Value("${embedding.api.timeout-ms:15000}") int timeoutMs
    ) {
        Sha256EmbeddingService fallback = new Sha256EmbeddingService(Math.max(1, dimension));
        if (provider != null && provider.trim().equalsIgnoreCase("sha256")) {
            logger.info("embed.provider selected=sha256 dims={}", dimension);
            return fallback;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            logger.info("embed.provider selected=sha256 reason=no_api_key dims={}", dimension);
            return fallback;
        }

        HttpClient http = HttpClient.newHttpClient();
        DashScopeEmbeddingService primary = new DashScopeEmbeddingService(http, mapper, URI.create(baseUrl), apiKey.trim(), model, dimension, timeoutMs);
        logger.info("embed.provider selected=dashscope url={} model={} dims={} timeoutMs={} apiKeyPresent=true", safeBaseUrl(baseUrl), model, dimension, timeoutMs);
        return text -> {
            try {
                return primary.embed(text);
            } catch (Exception e) {
                logger.warn("embed.fallback to=sha256 reason={} dims={} err={}", e.getClass().getSimpleName(), dimension, e.toString());
                return fallback.embed(text);
            }
        };
    }

    private static String safeBaseUrl(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
