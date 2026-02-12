package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * WebSearchTool 实现 (对齐 opencode/src/tool/websearch.ts)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSearchTool implements Tool {

    private final ProjectContext projectContext;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private static final String BASE_URL = "https://mcp.exa.ai/mcp";
    private static final int DEFAULT_NUM_RESULTS = 8;

    @Override
    public String getId() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return loadDescription();
    }

    private String loadDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/web_search.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load web_search tool description", e);
        }
        return "Search the internet for real-time information.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("query").put("type", "string").put("description", "Websearch query");
        properties.putObject("numResults").put("type", "integer").put("description", "Number of search results to return (default: 8)");
        properties.putObject("livecrawl").put("type", "string").put("description", "Live crawl mode - 'fallback': use live crawling as backup if cached content unavailable, 'preferred': prioritize live crawling (default: 'fallback')");
        properties.putObject("type").put("type", "string").put("description", "Search type - 'auto': balanced search (default), 'fast': quick results, 'deep': comprehensive search");
        properties.putObject("contextMaxCharacters").put("type", "integer").put("description", "Maximum characters for context string optimized for LLMs (default: 10000)");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        String query = args.get("query").asText();
        int numResults = args.has("numResults") ? args.get("numResults").asInt() : DEFAULT_NUM_RESULTS;
        String livecrawl = args.has("livecrawl") ? args.get("livecrawl").asText() : "fallback";
        String searchType = args.has("type") ? args.get("type").asText() : "auto";
        Integer contextMaxCharacters = args.has("contextMaxCharacters") ? args.get("contextMaxCharacters").asInt() : null;

        // 1. Permission Check
        Map<String, Object> permissionRequest = new HashMap<>();
        permissionRequest.put("permission", "websearch");
        permissionRequest.put("patterns", new String[]{query});
        permissionRequest.put("always", new String[]{"*"});
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("query", query);
        metadata.put("numResults", numResults);
        metadata.put("livecrawl", livecrawl);
        metadata.put("type", searchType);
        metadata.put("contextMaxCharacters", contextMaxCharacters);
        permissionRequest.put("metadata", metadata);

        return ctx.ask(permissionRequest).thenCompose(v -> {
            // 2. Prepare MCP Request
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
                        .thenApply(response -> {
                            if (response.statusCode() != 200) {
                                throw new RuntimeException("Web search error (" + response.statusCode() + "): " + response.body());
                            }

                            String responseBody = response.body();
                            String[] lines = responseBody.split("\n");
                            for (String line : lines) {
                                if (line.startsWith("data: ")) {
                                    try {
                                        JsonNode data = objectMapper.readTree(line.substring(6));
                                        JsonNode content = data.path("result").path("content");
                                        if (content.isArray() && content.size() > 0) {
                                            return Result.builder()
                                                    .title("Web search: " + query)
                                                    .output(content.get(0).path("text").asText())
                                                    .metadata(new HashMap<>())
                                                    .build();
                                        }
                                    } catch (Exception e) {
                                        log.error("Failed to parse SSE data: {}", line, e);
                                    }
                                }
                            }

                            return Result.builder()
                                    .title("Web search: " + query)
                                    .output("No search results found. Please try a different query.")
                                    .metadata(new HashMap<>())
                                    .build();
                        });

            } catch (Exception e) {
                log.error("Failed to prepare or send websearch request", e);
                CompletableFuture<Result> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        });
    }
}
