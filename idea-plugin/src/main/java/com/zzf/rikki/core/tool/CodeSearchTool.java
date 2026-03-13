package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.rikki.session.PromptTextLoader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class CodeSearchTool implements Tool {
    private static final String BASE_URL = "https://mcp.exa.ai/mcp";
    private static final int DEFAULT_TOKENS_NUM = 5000;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public CodeSearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String getId() {
        return "search_codebase";
    }

    @Override
    public String getDescription() {
        return PromptTextLoader.loadToolPrompt("search_codebase");
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string").put("description", "Search query to find relevant APIs, libraries, or SDK usage.");
        properties.putObject("tokensNum").put("type", "integer").put("description", "Number of tokens to return. Defaults to 5000.");
        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        String query = args.path("query").asText("");
        int tokensNum = args.has("tokensNum") ? args.path("tokensNum").asInt(DEFAULT_TOKENS_NUM) : DEFAULT_TOKENS_NUM;

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("id", 1);
        requestBody.put("method", "tools/call");
        ObjectNode params = requestBody.putObject("params");
        params.put("name", "get_code_context_exa");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("query", query);
        arguments.put("tokensNum", tokensNum);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL))
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> parseResponse(response, query));
        } catch (Exception e) {
            CompletableFuture<Result> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    private Result parseResponse(HttpResponse<String> response, String query) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Code search error (" + response.statusCode() + "): " + response.body());
        }
        String responseBody = response.body();
        for (String line : responseBody.split("\n")) {
            if (!line.startsWith("data: ")) {
                continue;
            }
            try {
                JsonNode data = objectMapper.readTree(line.substring(6));
                JsonNode content = data.path("result").path("content");
                if (content.isArray() && content.size() > 0) {
                    return new Result("Code search: " + query, Map.of(), content.get(0).path("text").asText(""), List.of());
                }
            } catch (Exception ignored) {
            }
        }
        return Result.of("Code search: " + query, "No code snippets or documentation found. Please try a different query.");
    }
}
