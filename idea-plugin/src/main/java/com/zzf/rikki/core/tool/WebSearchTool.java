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

public final class WebSearchTool implements Tool {
    private static final String BASE_URL = "https://mcp.exa.ai/mcp";
    private static final int DEFAULT_NUM_RESULTS = 8;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WebSearchTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public String getId() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        String prompt = PromptTextLoader.loadToolPrompt("web_search");
        return prompt.isBlank() ? "Search the internet for real-time information." : prompt;
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("query").put("type", "string").put("description", "Websearch query");
        properties.putObject("numResults").put("type", "integer").put("description", "Number of search results to return (default: 8)");
        properties.putObject("livecrawl").put("type", "string").put("description", "Live crawl mode. Use fallback by default or preferred to force live crawling.");
        properties.putObject("type").put("type", "string").put("description", "Search type. Use auto by default, or fast/deep when needed.");
        properties.putObject("contextMaxCharacters").put("type", "integer").put("description", "Maximum context characters to return.");
        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        String query = args.path("query").asText("");
        int numResults = args.has("numResults") ? args.path("numResults").asInt(DEFAULT_NUM_RESULTS) : DEFAULT_NUM_RESULTS;
        String livecrawl = args.path("livecrawl").asText("fallback");
        String searchType = args.path("type").asText("auto");
        Integer contextMaxCharacters = args.has("contextMaxCharacters") && args.path("contextMaxCharacters").canConvertToInt()
                ? args.path("contextMaxCharacters").asInt()
                : null;

        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("jsonrpc", "2.0");
        requestBody.put("id", 1);
        requestBody.put("method", "tools/call");
        ObjectNode params = requestBody.putObject("params");
        params.put("name", "web_search_exa");
        ObjectNode arguments = params.putObject("arguments");
        arguments.put("query", query);
        arguments.put("type", searchType);
        arguments.put("numResults", numResults);
        arguments.put("livecrawl", livecrawl);
        if (contextMaxCharacters != null) {
            arguments.put("contextMaxCharacters", contextMaxCharacters);
        }

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
            throw new IllegalStateException("Web search error (" + response.statusCode() + "): " + response.body());
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
                    return new Result("Web search: " + query, Map.of(), content.get(0).path("text").asText(""), List.of());
                }
            } catch (Exception ignored) {
            }
        }
        return Result.of("Web search: " + query, "No search results found. Please try a different query.");
    }
}