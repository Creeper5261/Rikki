package com.zzf.rikki.idea;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.openapi.diagnostic.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

final class ChatStopClient {
    private static final Logger logger = Logger.getInstance(ChatStopClient.class);

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String streamEndpoint;
    private final String defaultStopEndpoint;

    ChatStopClient(HttpClient http, ObjectMapper mapper, String streamEndpoint, String defaultStopEndpoint) {
        this.http = http;
        this.mapper = mapper;
        this.streamEndpoint = streamEndpoint;
        this.defaultStopEndpoint = defaultStopEndpoint;
    }

    boolean requestStop(String sessionId, String reason) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        try {
            ObjectNode reqNode = mapper.createObjectNode();
            reqNode.put("sessionID", sessionId);
            reqNode.put("reason", reason == null || reason.isBlank() ? "Stopped by user" : reason);
            String endpoint = resolveStopEndpoint();
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(reqNode), StandardCharsets.UTF_8))
                    .build();
            http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            logger.warn("chat.stop_failed", e);
            return false;
        }
    }

    String resolveStopEndpoint() {
        String normalized = streamEndpoint == null ? "" : streamEndpoint.trim();
        if (normalized.isBlank()) {
            return defaultStopEndpoint;
        }
        if (normalized.endsWith("/stream")) {
            return normalized.substring(0, normalized.length() - "/stream".length()) + "/stop";
        }
        if (normalized.endsWith("/chat")) {
            return normalized + "/stop";
        }
        if (normalized.endsWith("/")) {
            return normalized + "stop";
        }
        return normalized + "/stop";
    }
}
