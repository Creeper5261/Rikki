package com.zzf.rikki.idea.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zzf.rikki.idea.agent.compat.InMemoryPendingApprovalService;
import com.zzf.rikki.idea.agent.compat.LiteToolExecutor;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public class LiteAgentServer {
    private final Project project;
    private final ObjectMapper mapper = new ObjectMapper();
    private final InMemoryPendingApprovalService pendingApprovalService = new InMemoryPendingApprovalService();
    private final LiteToolExecutor toolExecutor;
    private final LiteAgentEngine engine;
    private HttpServer server;
    private int port;

    public LiteAgentServer(Project project) {
        this.project = project;
        this.toolExecutor = new LiteToolExecutor(project, mapper, pendingApprovalService);
        this.engine = new LiteAgentEngine(project, mapper, pendingApprovalService, toolExecutor);
    }

    public void start() {
        try {
            HttpServer httpServer = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32);
            httpServer.createContext("/api/agent/chat/stream", this::handleStream);
            httpServer.createContext("/api/agent/skip", this::handleSkip);
            httpServer.createContext("/api/agent/confirm", this::handleConfirm);
            httpServer.createContext("/api/agent/pending-command", this::handlePendingCommand);
            httpServer.createContext("/api/agent/todos", this::handleTodos);
            httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread thread = new Thread(r, "rikki-lite-agent");
                thread.setDaemon(true);
                return thread;
            }));
            httpServer.start();
            server = httpServer;
            port = httpServer.getAddress().getPort();
            System.setProperty("rikki.endpoint", "http://127.0.0.1:" + port + "/api/agent/chat/stream");
            System.setProperty("rikki.skip.endpoint", "http://127.0.0.1:" + port + "/api/agent/skip");
            System.setProperty("rikki.confirm.endpoint", "http://127.0.0.1:" + port + "/api/agent/confirm");
            System.setProperty("rikki.pending.command.endpoint", "http://127.0.0.1:" + port + "/api/agent/pending-command");
            System.setProperty("rikki.pending.enabled", "false");
        } catch (Exception e) {
            throw new RuntimeException("Failed to start LiteAgentServer", e);
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    public int getPort() {
        return port;
    }

    private void handleSkip(HttpExchange exchange) throws java.io.IOException {
        pendingApprovalService.skipCurrentExecution();
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private void handleConfirm(HttpExchange exchange) throws java.io.IOException {
        String query = exchange.getRequestURI().getRawQuery();
        boolean approved = query != null && query.toLowerCase().contains("decision=approve");
        pendingApprovalService.resolveLatestPending(approved);
        exchange.sendResponseHeaders(200, -1);
        exchange.close();
    }

    private void handlePendingCommand(HttpExchange exchange) throws java.io.IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        JsonNode request;
        try {
            request = mapper.readTree(exchange.getRequestBody().readAllBytes());
        } catch (Exception e) {
            writeJson(exchange, 400, Map.of("status", "error", "error", "Invalid JSON payload"));
            return;
        }
        Object result = pendingApprovalService.resolve(
                request.path("commandId").asText(""),
                request.path("reject").asBoolean(false),
                request.path("decisionMode").asText("")
        );
        writeJson(exchange, 200, result);
    }

    private void handleTodos(HttpExchange exchange) throws java.io.IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        String workspaceRoot = queryParams(exchange.getRequestURI().getRawQuery()).getOrDefault("workspaceRoot", "");
        String json = workspaceRoot.isBlank() ? "[]" : toolExecutor.todosAsListJson(workspaceRoot);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private void handleStream(HttpExchange exchange) throws java.io.IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        JsonNode request;
        try {
            request = mapper.readTree(exchange.getRequestBody().readAllBytes());
        } catch (Exception ignored) {
            exchange.close();
            return;
        }
        String goal = request.path("goal").asText("");
        String workspaceRoot = request.path("workspaceRoot").asText(project.getBasePath() == null ? "" : project.getBasePath());
        JsonNode ideContext = request.path("ideContext");
        JsonNode history = request.path("history");
        String sessionId = request.path("sessionID").asText("");
        JsonNode settings = request.path("settings");

        exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=UTF-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-cache");
        exchange.getResponseHeaders().add("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);
        LiteSseWriter writer = new LiteSseWriter(exchange.getResponseBody());
        try {
            engine.run(goal, workspaceRoot, ideContext, history, settings, sessionId, writer);
        } catch (Exception ignored) {
        } finally {
            exchange.getResponseBody().close();
        }
    }

    private void writeJson(HttpExchange exchange, int status, Object payload) throws java.io.IOException {
        byte[] bytes = mapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
    }

    private Map<String, String> queryParams(String rawQuery) {
        HashMap<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return params;
        }
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            if (idx < 0) {
                continue;
            }
            params.put(pair.substring(0, idx), URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8));
        }
        return params;
    }
}
