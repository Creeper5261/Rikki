package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

@Slf4j
final class IdeBridgeClient {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;

    private IdeBridgeClient() {
    }

    static String resolveBridgeUrl(Tool.Context ctx) {
        if (ctx == null || ctx.getExtra() == null || ctx.getExtra().isEmpty()) {
            return "";
        }
        Object direct = ctx.getExtra().get("ideBridgeUrl");
        if (direct instanceof String && !((String) direct).isBlank()) {
            return normalizeBridgeUrl((String) direct);
        }
        Object ideContextObj = ctx.getExtra().get("ideContext");
        if (ideContextObj instanceof Map<?, ?> ideContext) {
            Object url = ideContext.get("ideBridgeUrl");
            if (url instanceof String && !((String) url).isBlank()) {
                return normalizeBridgeUrl((String) url);
            }
        }
        return "";
    }

    static JsonNode execute(
            ObjectMapper mapper,
            String bridgeUrl,
            ObjectNode payload,
            long timeoutMs
    ) throws Exception {
        if (bridgeUrl == null || bridgeUrl.isBlank()) {
            throw new IllegalArgumentException("IDE bridge URL is empty.");
        }
        URI executeUri = toExecuteUri(bridgeUrl);
        if (!isAllowedLoopback(executeUri)) {
            throw new IllegalArgumentException("IDE bridge URL must be localhost/loopback: " + bridgeUrl);
        }

        long safeTimeoutMs = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
        String requestBody = mapper.writeValueAsString(payload == null ? mapper.createObjectNode() : payload);
        HttpRequest request = HttpRequest.newBuilder(executeUri)
                .timeout(Duration.ofMillis(Math.max(2_000L, safeTimeoutMs)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int statusCode = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("IDE bridge HTTP " + statusCode + ": " + trim(body, 600));
        }
        if (body.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(body);
    }

    private static String normalizeBridgeUrl(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }

    private static URI toExecuteUri(String bridgeUrl) {
        URI base = URI.create(bridgeUrl.trim());
        String path = base.getPath() == null ? "" : base.getPath().trim();
        String normalizedPath;
        if (path.isBlank() || "/".equals(path)) {
            normalizedPath = "/execute";
        } else if (path.endsWith("/execute")) {
            normalizedPath = path;
        } else if (path.endsWith("/")) {
            normalizedPath = path + "execute";
        } else {
            normalizedPath = path + "/execute";
        }
        try {
            return new URI(
                    base.getScheme(),
                    base.getUserInfo(),
                    base.getHost(),
                    base.getPort(),
                    normalizedPath,
                    null,
                    null
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid IDE bridge URL: " + bridgeUrl, e);
        }
    }

    private static boolean isAllowedLoopback(URI uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme)) {
            return false;
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    static String trim(String value, int limit) {
        if (value == null) {
            return "";
        }
        if (value.length() <= limit) {
            return value;
        }
        return value.substring(0, Math.max(0, limit - 3)) + "...";
    }
}

