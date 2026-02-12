package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * CodeSearchTool 实现 (对齐 opencode/src/tool/codesearch.ts)
 */
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ResourceLoader;
import java.io.IOException;

@Component
@Slf4j
public class CodeSearchTool implements Tool {
    private static final int DEFAULT_TOKENS_NUM = 5000;
    private static final String BASE_URL = "https://mcp.exa.ai/mcp";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ResourceLoader resourceLoader;

    public CodeSearchTool(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getId() {
        return "search_codebase";
    }

    @Override
    public String getDescription() {
        return loadDescription();
    }

    private String loadDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/search_codebase.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load search_codebase tool description", e);
        }
        return "Search the codebase for relevant code snippets using natural language.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("query").put("type", "string").put("description", "Search query to find relevant context for APIs, Libraries, and SDKs. For example, 'React useState hook examples', 'Python pandas dataframe filtering', 'Express.js middleware', 'Next js partial prerendering configuration'");
        properties.putObject("tokensNum").put("type", "integer").put("description", "Number of tokens to return (1000-50000). Default is 5000 tokens. Adjust this value based on how much context you need - use lower values for focused queries and higher values for comprehensive documentation.");

        schema.putArray("required").add("query");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        String query = args.get("query").asText();
        int tokensNum = args.has("tokensNum") ? args.get("tokensNum").asInt() : DEFAULT_TOKENS_NUM;

        // 1. Permission Check (aligned with opencode ctx.ask)
        Map<String, Object> permissionRequest = new HashMap<>();
        permissionRequest.put("permission", "codesearch");
        permissionRequest.put("patterns", new String[]{query});
        permissionRequest.put("always", new String[]{"*"});
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("query", query);
        metadata.put("tokensNum", tokensNum);
        permissionRequest.put("metadata", metadata);

        return ctx.ask(permissionRequest).thenCompose(v -> {
            // 2. Prepare MCP Request
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
                        .thenApply(response -> {
                            if (response.statusCode() != 200) {
                                throw new RuntimeException("Code search error (" + response.statusCode() + "): " + response.body());
                            }

                            String responseBody = response.body();
                            // Parse SSE response (simplified for Java version)
                            String[] lines = responseBody.split("\n");
                            for (String line : lines) {
                                if (line.startsWith("data: ")) {
                                    try {
                                        JsonNode data = objectMapper.readTree(line.substring(6));
                                        JsonNode content = data.path("result").path("content");
                                        if (content.isArray() && content.size() > 0) {
                                            return Result.builder()
                                                    .title("Code search: " + query)
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
                                    .title("Code search: " + query)
                                    .output("No code snippets or documentation found. Please try a different query, be more specific about the library or programming concept, or check the spelling of framework names.")
                                    .metadata(new HashMap<>())
                                    .build();
                        });

            } catch (Exception e) {
                log.error("Failed to prepare or send codesearch request", e);
                CompletableFuture<Result> failed = new CompletableFuture<>();
                failed.completeExceptionally(e);
                return failed;
            }
        });
    }
}
