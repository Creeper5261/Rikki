package com.zzf.codeagent.core.rag.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class CodeAgentV2IndexInitializer {
    private static final Logger logger = LoggerFactory.getLogger(CodeAgentV2IndexInitializer.class);

    private final HttpClient http;
    private final URI baseUri;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final int vectorDims;

    public CodeAgentV2IndexInitializer(HttpClient http, URI baseUri) {
        this(http, baseUri, 2048);
    }

    public CodeAgentV2IndexInitializer(HttpClient http, URI baseUri, int vectorDims) {
        this.http = http;
        this.baseUri = baseUri;
        this.vectorDims = vectorDims;
    }

    public void ensureIndexExists(String indexName) throws Exception {
        long t0 = System.nanoTime();
        if (indexExists(indexName)) {
            Integer existing = readExistingVectorDims(indexName);
            if (existing != null && vectorDims > 0 && existing.intValue() != vectorDims) {
                logger.info("index.ensure.recreate index={} existingDims={} desiredDims={}", indexName, existing, vectorDims);
                deleteIndex(indexName);
            } else {
                logger.info("index.ensure.skip index={} existingDims={} desiredDims={}", indexName, existing, vectorDims);
                return;
            }
        }

        String body = applyDims(loadResource("/es/code_agent_v2.json"), vectorDims);
        createIndex(indexName, body);
        logger.info("index.ensure.ok index={} dims={} tookMs={}", indexName, vectorDims, (System.nanoTime() - t0) / 1_000_000L);
    }

    private boolean indexExists(String index) throws Exception {
        try {
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("/" + index))
                    .timeout(Duration.ofSeconds(5))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            if (resp.statusCode() == 200) {
                return true;
            }
            if (resp.statusCode() == 404) {
                return false;
            }
            throw new IllegalStateException("Unexpected response for index exists: " + resp.statusCode());
        } catch (Exception e) {
            throw enrich("indexExists", index, e);
        }
    }

    private void createIndex(String index, String body) throws Exception {
        try {
            logger.info("index.create.start index={} dims={} bodyChars={}", index, vectorDims, body == null ? 0 : body.length());
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("/" + index))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= HttpURLConnection.HTTP_OK && resp.statusCode() < 300) {
                return;
            }
            throw new IllegalStateException("Create index failed: " + resp.statusCode() + " body=" + resp.body());
        } catch (Exception e) {
            throw enrich("createIndex", index, e);
        }
    }

    private void deleteIndex(String index) throws Exception {
        try {
            logger.info("index.delete.start index={}", index);
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("/" + index))
                    .timeout(Duration.ofSeconds(8))
                    .DELETE()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() == 404) {
                return;
            }
            if (resp.statusCode() >= HttpURLConnection.HTTP_OK && resp.statusCode() < 300) {
                return;
            }
            throw new IllegalStateException("Delete index failed: " + resp.statusCode() + " body=" + resp.body());
        } catch (Exception e) {
            throw enrich("deleteIndex", index, e);
        }
    }

    private Integer readExistingVectorDims(String index) {
        try {
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("/" + index + "/_mapping"))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < HttpURLConnection.HTTP_OK || resp.statusCode() >= 300) {
                return null;
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode idx = root.path(index);
            if (idx.isMissingNode()) {
                idx = root.elements().hasNext() ? root.elements().next() : idx;
            }
            JsonNode dimsNode = idx.path("mappings").path("properties").path("contentVector").path("dims");
            if (!dimsNode.isNumber()) {
                return null;
            }
            return dimsNode.asInt();
        } catch (Exception e) {
            return null;
        }
    }

    private Exception enrich(String action, String index, Exception e) {
        Throwable root = rootCause(e);
        String rootMsg = root == null ? null : root.getMessage();
        String msg = "Elasticsearch request failed"
                + " action=" + action
                + " index=" + index
                + " es=" + baseUri
                + " cause=" + (root == null ? e.getClass().getSimpleName() : root.getClass().getSimpleName())
                + (rootMsg == null ? "" : (": " + rootMsg))
                + " (check ES_SCHEME/ES_HOST/ES_PORT or elasticsearch.scheme/host/port; startInfra can start docker-compose)";
        return new IllegalStateException(msg, e);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 12 && cur != null; i++) {
            Throwable next = cur.getCause();
            if (next == null || next == cur) {
                break;
            }
            cur = next;
        }
        return cur;
    }

    private String applyDims(String body, int dims) throws Exception {
        if (dims <= 0) {
            return body;
        }
        JsonNode root = mapper.readTree(body);
        JsonNode props = root.path("mappings").path("properties");
        if (props.isObject()) {
            JsonNode cv = props.path("contentVector");
            if (cv.isObject()) {
                ((ObjectNode) cv).put("dims", dims);
            }
        }
        return mapper.writeValueAsString(root);
    }

    private String loadResource(String path) throws Exception {
        InputStream in = CodeAgentV2IndexInitializer.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException("resource not found: " + path);
        }
        byte[] data = in.readAllBytes();
        return new String(data, StandardCharsets.UTF_8);
    }
}
