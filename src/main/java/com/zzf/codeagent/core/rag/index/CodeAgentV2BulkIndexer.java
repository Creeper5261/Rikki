package com.zzf.codeagent.core.rag.index;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class CodeAgentV2BulkIndexer {
    private static final Logger logger = LoggerFactory.getLogger(CodeAgentV2BulkIndexer.class);

    private final HttpClient http;
    private final URI baseUri;

    public CodeAgentV2BulkIndexer(HttpClient http, URI baseUri) {
        this.http = http;
        this.baseUri = baseUri;
    }

    public void bulkIndex(String indexName, List<CodeAgentV2Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            long t0 = System.nanoTime();
            String body = toNdjson(indexName, docs);
            int approxVecDims = docs.get(0) == null || docs.get(0).getContentVector() == null ? -1 : docs.get(0).getContentVector().length;
            logger.info("es.bulk.start index={} docs={} ndjsonChars={} vecDims={}", indexName, docs.size(), body.length(), approxVecDims);
            HttpRequest req = HttpRequest.newBuilder(baseUri.resolve("/_bulk"))
                    .header("Content-Type", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() < HttpURLConnection.HTTP_OK || resp.statusCode() >= 300) {
                throw new RuntimeException("bulkIndex failed status=" + resp.statusCode() + " body=" + resp.body());
            }
            boolean errors = resp.body() != null && resp.body().contains("\"errors\":true");
            if (errors) {
                logger.warn("es.bulk.partial_errors index={} docs={} tookMs={} resp={}", indexName, docs.size(), (System.nanoTime() - t0) / 1_000_000L, truncate(resp.body(), 800));
            }
            logger.info("es.bulk ok index={} docs={} tookMs={}", indexName, docs.size(), (System.nanoTime() - t0) / 1_000_000L);
        } catch (Exception e) {
            logger.warn("es.bulk error index={} docs={} err={}", indexName, docs.size(), e.toString());
            throw new RuntimeException(e);
        }
    }

    private String toNdjson(String indexName, List<CodeAgentV2Document> docs) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            CodeAgentV2Document doc = docs.get(i);
            sb.append("{\"index\":{\"_index\":\"").append(escape(indexName)).append("\",\"_id\":\"")
                    .append(escape(doc.getId())).append("\"}}\n");
            sb.append("{");
            sb.append("\"repo\":\"").append(escape(doc.getRepo())).append("\",");
            sb.append("\"language\":\"").append(escape(doc.getLanguage())).append("\",");
            sb.append("\"filePath\":\"").append(escape(doc.getFilePath())).append("\",");
            sb.append("\"symbolKind\":\"").append(escape(doc.getSymbolKind())).append("\",");
            sb.append("\"symbolName\":\"").append(escape(doc.getSymbolName())).append("\",");
            sb.append("\"signature\":\"").append(escape(doc.getSignature())).append("\",");
            sb.append("\"startLine\":").append(doc.getStartLine()).append(",");
            sb.append("\"endLine\":").append(doc.getEndLine()).append(",");
            sb.append("\"content\":\"").append(escape(doc.getContent())).append("\",");
            sb.append("\"contentVector\":").append(vector(doc.getContentVector()));
            sb.append("}\n");
        }
        return sb.toString();
    }

    private String vector(float[] v) {
        if (v == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(v[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    private String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n");
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
}
