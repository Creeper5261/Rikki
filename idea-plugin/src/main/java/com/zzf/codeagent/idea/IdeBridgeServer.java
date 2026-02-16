package com.zzf.codeagent.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.CompilerMessage;
import com.intellij.openapi.compiler.CompilerMessageCategory;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service(Service.Level.PROJECT)
public final class IdeBridgeServer implements Disposable {
    private static final Logger logger = Logger.getInstance(IdeBridgeServer.class);
    private static final int DEFAULT_BUILD_TIMEOUT_MS = 300_000;
    private static final int MIN_BUILD_TIMEOUT_MS = 10_000;
    private static final int MAX_BUILD_TIMEOUT_MS = 1_800_000;
    private static final int MAX_COMPILER_MESSAGE_LINES = 200;
    private static final int MAX_RESPONSE_OUTPUT_CHARS = 20_000;

    private final Project project;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();

    private volatile HttpServer server;
    private volatile String baseUrl = "";

    public IdeBridgeServer(Project project) {
        this.project = project;
    }

    public void ensureStarted() {
        synchronized (lock) {
            if (server != null || project.isDisposed()) {
                return;
            }
            try {
                HttpServer created = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                created.createContext("/execute", this::handleExecute);
                created.setExecutor(Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "rikki-ide-bridge");
                    t.setDaemon(true);
                    return t;
                }));
                created.start();
                this.server = created;
                this.baseUrl = "http://127.0.0.1:" + created.getAddress().getPort();
                logger.info("IDE bridge started: " + baseUrl + ", project=" + project.getName());
            } catch (Exception e) {
                logger.warn("IDE bridge start failed", e);
            }
        }
    }

    public String getBaseUrl() {
        ensureStarted();
        return firstNonBlank(baseUrl);
    }

    @Override
    public void dispose() {
        synchronized (lock) {
            if (server != null) {
                try {
                    server.stop(0);
                } catch (Exception ignored) {
                    // no-op
                }
                server = null;
            }
            baseUrl = "";
        }
    }

    private void handleExecute(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, error("method_not_allowed", "Only POST is supported."));
            return;
        }
        if (project.isDisposed()) {
            writeJson(exchange, 410, error("project_disposed", "Project is already disposed."));
            return;
        }

        JsonNode request;
        try {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            request = raw == null || raw.length == 0 ? mapper.createObjectNode() : mapper.readTree(raw);
        } catch (Exception e) {
            writeJson(exchange, 400, error("invalid_json", "Failed to parse request JSON: " + safeMessage(e)));
            return;
        }

        String action = firstNonBlank(request.path("action").asText("")).toLowerCase(Locale.ROOT);
        ObjectNode response;
        try {
            response = switch (action) {
                case "build" -> executeBuild(request);
                case "run" -> executeRunLike(request, false);
                case "test" -> executeRunLike(request, true);
                default -> error("unsupported_action", "Unsupported action: " + action);
            };
        } catch (Exception e) {
            logger.warn("IDE bridge action failed: " + action, e);
            response = error("bridge_exception", safeMessage(e));
        }

        writeJson(exchange, 200, response);
    }

    private ObjectNode executeBuild(JsonNode request) {
        String mode = normalizeBuildMode(request.path("mode").asText(""));
        long timeoutMs = normalizeTimeout(request.path("timeoutMs").asLong(DEFAULT_BUILD_TIMEOUT_MS));

        BuildResult result = new BuildResult();
        CountDownLatch latch = new CountDownLatch(1);
        long startedAt = System.currentTimeMillis();

        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed()) {
                result.status = "error";
                result.summary = "Project disposed before build started.";
                latch.countDown();
                return;
            }
            CompileStatusNotification callback = (aborted, errors, warnings, compileContext) -> {
                result.aborted = aborted;
                result.errors = errors;
                result.warnings = warnings;
                result.output = collectCompilerMessages(compileContext, MAX_COMPILER_MESSAGE_LINES);
                if (aborted) {
                    result.status = "aborted";
                } else if (errors > 0) {
                    result.status = "failed";
                } else {
                    result.status = "success";
                }
                result.summary = buildSummary(result.status, errors, warnings, aborted);
                latch.countDown();
            };

            CompilerManager compilerManager = CompilerManager.getInstance(project);
            if ("rebuild".equals(mode)) {
                compilerManager.rebuild(callback);
            } else {
                compilerManager.make(callback);
            }
        });

        boolean completed;
        try {
            completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);

        if (!completed) {
            if (result.status == null || result.status.isBlank()) {
                result.status = "timeout";
                result.summary = "Build did not finish within " + timeoutMs + "ms.";
            }
        }

        boolean ok = "success".equalsIgnoreCase(result.status);
        ObjectNode node = mapper.createObjectNode();
        node.put("ok", ok);
        node.put("action", "build");
        node.put("mode", mode);
        node.put("status", firstNonBlank(result.status, "error"));
        node.put("aborted", result.aborted);
        node.put("errors", Math.max(0, result.errors));
        node.put("warnings", Math.max(0, result.warnings));
        node.put("durationMs", durationMs);
        node.put("summary", firstNonBlank(result.summary, ok ? "IDE build succeeded." : "IDE build failed."));
        node.put("output", trimOutput(firstNonBlank(result.output)));
        return node;
    }

    private ObjectNode executeRunLike(JsonNode request, boolean preferTest) {
        String requestedName = firstNonBlank(
                request.path("configuration").asText(""),
                request.path("configurationName").asText(""),
                request.path("name").asText("")
        );
        String requestedExecutor = firstNonBlank(request.path("executor").asText(""), "run").toLowerCase(Locale.ROOT);
        boolean debug = "debug".equals(requestedExecutor);
        RunnerAndConfigurationSettings target = resolveRunConfiguration(requestedName, preferTest);
        if (target == null) {
            return error(
                    "configuration_not_found",
                    "No matching Run Configuration found. Available: " + listRunConfigurations(20)
            );
        }

        try {
            ApplicationManager.getApplication().invokeLater(() -> ProgramRunnerUtil.executeConfiguration(
                    target,
                    debug ? DefaultDebugExecutor.getDebugExecutorInstance() : DefaultRunExecutor.getRunExecutorInstance()
            ));
        } catch (Exception e) {
            return error("start_failed", "Failed to start configuration: " + safeMessage(e));
        }

        ObjectNode node = mapper.createObjectNode();
        node.put("ok", true);
        node.put("action", preferTest ? "test" : "run");
        node.put("status", "started");
        node.put("executor", debug ? "debug" : "run");
        node.put("configuration", firstNonBlank(target.getName()));
        node.put("summary", "Started " + (preferTest ? "test" : "run") + " configuration: " + target.getName());
        node.put("output", "");
        return node;
    }

    private RunnerAndConfigurationSettings resolveRunConfiguration(String requestedName, boolean preferTest) {
        RunManager runManager = RunManager.getInstance(project);
        List<RunnerAndConfigurationSettings> settings = runManager.getAllSettings();
        if (settings.isEmpty()) {
            return null;
        }

        if (!requestedName.isBlank()) {
            for (RunnerAndConfigurationSettings item : settings) {
                if (item != null && requestedName.equalsIgnoreCase(firstNonBlank(item.getName()))) {
                    return item;
                }
            }
            for (RunnerAndConfigurationSettings item : settings) {
                if (item == null) {
                    continue;
                }
                String name = firstNonBlank(item.getName()).toLowerCase(Locale.ROOT);
                if (name.contains(requestedName.toLowerCase(Locale.ROOT))) {
                    return item;
                }
            }
        }

        if (preferTest) {
            for (RunnerAndConfigurationSettings item : settings) {
                if (item == null || item.getConfiguration() == null) {
                    continue;
                }
                String typeName = firstNonBlank(item.getConfiguration().getType().getDisplayName()).toLowerCase(Locale.ROOT);
                String cfgName = firstNonBlank(item.getName()).toLowerCase(Locale.ROOT);
                if (typeName.contains("test") || cfgName.contains("test")) {
                    return item;
                }
            }
        }

        RunnerAndConfigurationSettings selected = runManager.getSelectedConfiguration();
        if (selected != null) {
            return selected;
        }
        return settings.get(0);
    }

    private String listRunConfigurations(int limit) {
        RunManager runManager = RunManager.getInstance(project);
        List<RunnerAndConfigurationSettings> settings = runManager.getAllSettings();
        if (settings.isEmpty()) {
            return "(none)";
        }
        List<String> names = new ArrayList<>();
        int max = Math.min(Math.max(limit, 1), settings.size());
        for (int i = 0; i < max; i++) {
            RunnerAndConfigurationSettings item = settings.get(i);
            if (item == null) {
                continue;
            }
            names.add(firstNonBlank(item.getName(), "(unnamed)"));
        }
        return names.isEmpty() ? "(none)" : String.join(", ", names);
    }

    private String collectCompilerMessages(CompileContext context, int maxLines) {
        if (context == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        appendCompilerMessages(lines, context.getMessages(CompilerMessageCategory.ERROR), "ERROR", maxLines);
        appendCompilerMessages(lines, context.getMessages(CompilerMessageCategory.WARNING), "WARN", maxLines);
        appendCompilerMessages(lines, context.getMessages(CompilerMessageCategory.INFORMATION), "INFO", maxLines);
        return String.join("\n", lines);
    }

    private void appendCompilerMessages(
            List<String> lines,
            CompilerMessage[] messages,
            String level,
            int maxLines
    ) {
        if (messages == null || messages.length == 0 || lines.size() >= maxLines) {
            return;
        }
        for (CompilerMessage msg : messages) {
            if (msg == null) {
                continue;
            }
            if (lines.size() >= maxLines) {
                lines.add("... truncated compiler output ...");
                break;
            }
            String message = firstNonBlank(msg.getMessage());
            String path = msg.getVirtualFile() == null ? "" : firstNonBlank(msg.getVirtualFile().getPath());
            if (!path.isBlank()) {
                lines.add("[" + level + "] " + message + " (" + path + ")");
            } else {
                lines.add("[" + level + "] " + message);
            }
        }
    }

    private String normalizeBuildMode(String raw) {
        String normalized = firstNonBlank(raw, "make").trim().toLowerCase(Locale.ROOT);
        if ("rebuild".equals(normalized)) {
            return "rebuild";
        }
        return "make";
    }

    private long normalizeTimeout(long rawMs) {
        long safe = rawMs <= 0 ? DEFAULT_BUILD_TIMEOUT_MS : rawMs;
        safe = Math.max(MIN_BUILD_TIMEOUT_MS, safe);
        safe = Math.min(MAX_BUILD_TIMEOUT_MS, safe);
        return safe;
    }

    private ObjectNode error(String code, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("ok", false);
        node.put("status", "error");
        node.put("code", firstNonBlank(code, "error"));
        node.put("summary", firstNonBlank(message, "IDE bridge error."));
        node.put("output", firstNonBlank(message, "IDE bridge error."));
        return node;
    }

    private void writeJson(HttpExchange exchange, int statusCode, ObjectNode payload) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(payload == null ? mapper.createObjectNode() : payload);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String buildSummary(String status, int errors, int warnings, boolean aborted) {
        if ("success".equalsIgnoreCase(status)) {
            return "IDE build succeeded (" + warnings + " warning(s)).";
        }
        if ("aborted".equalsIgnoreCase(status) || aborted) {
            return "IDE build aborted (" + errors + " error(s), " + warnings + " warning(s)).";
        }
        if ("timeout".equalsIgnoreCase(status)) {
            return "IDE build timeout.";
        }
        return "IDE build failed (" + errors + " error(s), " + warnings + " warning(s)).";
    }

    private String trimOutput(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= MAX_RESPONSE_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_RESPONSE_OUTPUT_CHARS) + "\n... output truncated ...";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null) {
                String trimmed = value.trim();
                if (!trimmed.isBlank()) {
                    return trimmed;
                }
            }
        }
        return "";
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.toString();
        }
        return message;
    }

    private static final class BuildResult {
        String status;
        String summary;
        String output;
        int errors;
        int warnings;
        boolean aborted;
    }
}
