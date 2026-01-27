package com.zzf.codeagent.core.rag.vector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

public final class DashScopeEmbeddingService implements EmbeddingService {
    private static final Logger logger = LoggerFactory.getLogger(DashScopeEmbeddingService.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI baseUri;
    private final String apiKey;
    private final String model;
    private final int expectedDims;
    private final int timeoutMs;

    public DashScopeEmbeddingService(HttpClient http, ObjectMapper mapper, URI baseUri, String apiKey, String model, int expectedDims, int timeoutMs) {
        this.http = http;
        this.mapper = mapper;
        this.baseUri = baseUri;
        this.apiKey = apiKey;
        this.model = model;
        this.expectedDims = expectedDims;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public float[] embed(String text) {
        long t0 = System.nanoTime();
        String fp = fingerprint(text);
        int chars = text == null ? 0 : text.length();
        logger.info("embed.dashscope.start model={} expectedDims={} inputChars={} inputFp={}", model, expectedDims, chars, fp);
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", model);
            if (expectedDims > 0) {
                payload.put("dimension", expectedDims);
            }
            ArrayNode input = payload.putArray("input");
            input.add(text == null ? "" : text);
            String body = mapper.writeValueAsString(payload);

            URI endpoint = embeddingsUri();
            HttpRequest req = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            logger.info("embed.dashscope.http.begin model={} timeoutMs={} inputFp={}", model, timeoutMs, fp);
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long httpMs = (System.nanoTime() - t0) / 1_000_000L;
            logger.info("embed.dashscope.http.end status={} tookMs={} model={} inputFp={}", resp.statusCode(), httpMs, model, fp);
            if (resp.statusCode() < HttpURLConnection.HTTP_OK || resp.statusCode() >= 300) {
                throw new IllegalStateException("embedding http " + resp.statusCode() + ": " + truncate(resp.body(), 500));
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() == 0) {
                throw new IllegalStateException("embedding response missing data");
            }
            JsonNode embedding = data.get(0).path("embedding");
            if (!embedding.isArray() || embedding.size() == 0) {
                throw new IllegalStateException("embedding response missing embedding");
            }
            float[] v = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                v[i] = (float) embedding.get(i).asDouble();
            }
            if (expectedDims > 0 && v.length != expectedDims) {
                throw new IllegalStateException("embedding dims mismatch expected=" + expectedDims + " actual=" + v.length);
            }
            long tookMs = (System.nanoTime() - t0) / 1_000_000L;
            logger.info("embed.dashscope.ok dims={} tookMs={} model={} inputFp={}", v.length, tookMs, model, fp);
            return v;
        } catch (Exception e) {
            long tookMs = (System.nanoTime() - t0) / 1_000_000L;
            logger.warn("embed.dashscope.fail tookMs={} model={} inputChars={} inputFp={} err={}", tookMs, model, chars, fp, e.toString());
            throw new RuntimeException(e);
        }
    }

    private static String fingerprint(String s) {
        try {
            byte[] data = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6 && i < digest.length; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "na";
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max);
    }

    private URI embeddingsUri() {
        String s = baseUri == null ? "" : baseUri.toString();
        if (s.endsWith("/")) {
            return baseUri.resolve("embeddings");
        }
        return URI.create(s + "/embeddings");
    }
}
