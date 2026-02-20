package com.zzf.rikki.idea;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.intellij.execution.ExecutionListener;
import com.intellij.execution.ExecutionManager;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.process.ProcessAdapter;
import com.intellij.execution.process.ProcessEvent;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
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
import com.intellij.openapi.util.Key;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service(Service.Level.PROJECT)
public final class IdeBridgeServer implements Disposable {
    private static final Logger logger = Logger.getInstance(IdeBridgeServer.class);
    private static final int DEFAULT_BUILD_TIMEOUT_MS = 300_000;
    private static final int MIN_BUILD_TIMEOUT_MS = 10_000;
    private static final int MAX_BUILD_TIMEOUT_MS = 1_800_000;
    private static final int MAX_COMPILER_MESSAGE_LINES = 200;
    private static final int MAX_RESPONSE_OUTPUT_CHARS = 20_000;
    private static final int MAX_STATUS_OUTPUT_CHARS = 8_000;
    private static final int MAX_JOB_LOG_LINES = 400;
    private static final long MAX_STATUS_WAIT_MS = 30_000L;

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_SUCCEEDED = "succeeded";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_ABORTED = "aborted";
    private static final String STATUS_CANCELED = "canceled";
    private static final String STATUS_TIMEOUT = "timeout";
    private static final String STATUS_CANCELING = "canceling";

    private final Project project;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object lock = new Object();
    private final Map<String, IdeJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService jobExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "rikki-ide-bridge-job");
        t.setDaemon(true);
        return t;
    });

    private volatile HttpServer server;
    private volatile String baseUrl = "";

    public IdeBridgeServer(Project project) {
        this.project = project;
        installExecutionListener();
    }

    private void installExecutionListener() {
        try {
            project.getMessageBus().connect(this).subscribe(ExecutionManager.EXECUTION_TOPIC, new ExecutionListener() {
                @Override
                public void processStarted(
                        @NotNull String executorId,
                        @NotNull ExecutionEnvironment env,
                        @NotNull ProcessHandler handler
                ) {
                    onProcessStarted(executorId, env, handler);
                }

                @Override
                public void processTerminated(
                        @NotNull String executorId,
                        @NotNull ExecutionEnvironment env,
                        @NotNull ProcessHandler handler,
                        int exitCode
                ) {
                    onProcessTerminated(executorId, env, handler, exitCode);
                }
            });
        } catch (Throwable t) {
            logger.warn("Failed to install IDE execution listener", t);
        }
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
                    Thread t = new Thread(r, "rikki-ide-bridge-http");
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
                    
                }
                server = null;
            }
            baseUrl = "";
        }
        jobExecutor.shutdownNow();
        jobs.clear();
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
                case "capabilities" -> executeCapabilities();
                case "start" -> executeStart(request);
                case "status" -> executeStatus(request);
                case "cancel" -> executeCancel(request);
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

    private ObjectNode executeCapabilities() {
        ObjectNode node = mapper.createObjectNode();
        node.put("ok", true);
        node.put("action", "capabilities");
        node.put("status", "success");
        node.put("projectName", firstNonBlank(project.getName()));
        node.put("jobMode", true);
        node.put("logStreaming", true);

        ArrayNode asyncOps = node.putArray("asyncOperations");
        asyncOps.add("build");
        asyncOps.add("run");
        asyncOps.add("test");
        asyncOps.add("status");
        asyncOps.add("cancel");
        asyncOps.add("capabilities");

        ArrayNode directOps = node.putArray("directOperations");
        directOps.add("build");
        directOps.add("run");
        directOps.add("test");

        ArrayNode runConfigs = node.putArray("runConfigurations");
        RunManager runManager = RunManager.getInstance(project);
        List<RunnerAndConfigurationSettings> all = runManager.getAllSettings();
        int sample = Math.min(30, all.size());
        for (int i = 0; i < sample; i++) {
            RunnerAndConfigurationSettings item = all.get(i);
            if (item == null) {
                continue;
            }
            runConfigs.add(firstNonBlank(item.getName(), "(unnamed)"));
        }
        node.put("summary", "IDE bridge capabilities available.");
        node.put("output", "");
        return node;
    }

    private ObjectNode executeStart(JsonNode request) {
        String operation = normalizeOperation(request.path("operation").asText(request.path("op").asText("")));
        if (operation.isBlank()) {
            return error("missing_operation", "Field 'operation' is required for action=start.");
        }
        if (!isSupportedOperation(operation)) {
            return error("unsupported_operation", "Unsupported operation: " + operation);
        }

        IdeJob job = new IdeJob(UUID.randomUUID().toString(), operation);
        jobs.put(job.id, job);
        job.requestedConfiguration = firstNonBlank(
                request.path("configuration").asText(""),
                request.path("configurationName").asText(""),
                request.path("name").asText("")
        );
        job.requestedExecutor = normalizeExecutorId(firstNonBlank(request.path("executor").asText(""), "run"));
        job.status = STATUS_PENDING;
        job.summary = "Job queued: " + operation + ".";
        job.output = "queued: operation=" + operation;
        appendLog(job, job.output);
        job.updatedAt = System.currentTimeMillis();
        job.cancellable = true;

        Future<?> future = jobExecutor.submit(() -> runJob(job, request));
        job.future = future;
        return jobSnapshot("start", job, 0L);
    }

    private void runJob(IdeJob job, JsonNode request) {
        if (job == null) {
            return;
        }
        job.startedAt = System.currentTimeMillis();
        job.updatedAt = job.startedAt;
        job.status = STATUS_RUNNING;
        job.summary = "Job is running: " + job.operation + ".";
        job.output = trimOutput("running: operation=" + job.operation);
        appendLog(job, job.output);
        job.cancellable = true;

        try {
            switch (job.operation) {
                case "build" -> {
                    ObjectNode result = executeBuild(request);
                    applyResultToJob(job, result);
                }
                case "run" -> {
                    ObjectNode result = executeRunLike(request, false);
                    applyRunLikeStartResult(job, result);
                }
                case "test" -> {
                    ObjectNode result = executeRunLike(request, true);
                    applyRunLikeStartResult(job, result);
                }
                default -> applyResultToJob(job, error("unsupported_operation", "Unsupported operation: " + job.operation));
            }
        } catch (Exception e) {
            job.status = STATUS_FAILED;
            job.summary = "Job failed: " + safeMessage(e);
            job.output = trimOutput(job.summary);
            appendLog(job, job.output);
        } finally {
            long now = System.currentTimeMillis();
            job.updatedAt = now;
            if (isTerminal(job.status)) {
                job.completedAt = now;
                job.cancellable = false;
            }
        }
    }

    private void applyRunLikeStartResult(IdeJob job, ObjectNode response) {
        if (job == null || response == null) {
            return;
        }
        boolean ok = response.path("ok").asBoolean(false);
        if (!ok) {
            applyResultToJob(job, response);
            return;
        }
        job.resolvedConfiguration = firstNonBlank(response.path("configuration").asText(""), job.requestedConfiguration);
        job.executorId = normalizeExecutorId(firstNonBlank(response.path("executor").asText(""), job.requestedExecutor));
        job.status = STATUS_RUNNING;
        job.summary = firstNonBlank(response.path("summary").asText(""), "Run configuration started.");
        job.output = trimOutput(job.summary);
        job.updatedAt = System.currentTimeMillis();
        job.cancellable = true;
        appendLog(job, job.summary);
    }

    private void onProcessStarted(String executorId, ExecutionEnvironment env, ProcessHandler handler) {
        if (env == null || handler == null || project.isDisposed()) {
            return;
        }
        String profileName = resolveProfileName(env);
        IdeJob job = findPendingRunJob(profileName, executorId);
        if (job == null) {
            return;
        }
        job.processAttached = true;
        job.processHandler = handler;
        job.executorId = normalizeExecutorId(firstNonBlank(executorId, job.executorId, job.requestedExecutor));
        if (job.resolvedConfiguration == null || job.resolvedConfiguration.isBlank()) {
            job.resolvedConfiguration = firstNonBlank(profileName, job.requestedConfiguration);
        }
        job.status = STATUS_RUNNING;
        job.summary = "Process started: " + firstNonBlank(job.resolvedConfiguration, profileName, "run");
        job.output = trimOutput(job.summary);
        job.updatedAt = System.currentTimeMillis();
        job.cancellable = true;
        appendLog(job, "$ started " + firstNonBlank(job.resolvedConfiguration, profileName, "run")
                + " [" + firstNonBlank(job.executorId, "run") + "]");

        handler.addProcessListener(new ProcessAdapter() {
            @Override
            public void onTextAvailable(@NotNull ProcessEvent event, @NotNull Key outputType) {
                onProcessText(job, event.getText());
            }

            @Override
            public void processTerminated(@NotNull ProcessEvent event) {
                onProcessTerminatedInternal(job, event.getExitCode());
            }
        });
    }

    private void onProcessTerminated(String executorId, ExecutionEnvironment env, ProcessHandler handler, int exitCode) {
        if (handler == null) {
            return;
        }
        IdeJob job = findJobByProcessHandler(handler);
        if (job != null) {
            onProcessTerminatedInternal(job, exitCode);
        }
    }

    private void onProcessText(IdeJob job, String text) {
        if (job == null || text == null) {
            return;
        }
        appendLog(job, text);
        job.updatedAt = System.currentTimeMillis();
        job.output = trimOutput(tailLogs(job, 120));
    }

    private void onProcessTerminatedInternal(IdeJob job, int exitCode) {
        if (job == null) {
            return;
        }
        if (job.completedAt > 0L && isTerminal(job.status)) {
            return;
        }
        job.exitCode = exitCode;
        job.processAttached = false;
        job.processHandler = null;
        job.updatedAt = System.currentTimeMillis();
        job.completedAt = job.updatedAt;
        job.cancellable = false;

        if (job.cancelRequested) {
            job.status = STATUS_CANCELED;
            job.summary = "Process canceled.";
        } else if (exitCode == 0) {
            job.status = STATUS_SUCCEEDED;
            job.summary = "Process exited successfully.";
        } else {
            job.status = STATUS_FAILED;
            job.summary = "Process exited with code " + exitCode + ".";
        }
        appendLog(job, "[exit " + exitCode + "] " + job.summary);
        job.output = trimOutput(tailLogs(job, 160));
    }

    private IdeJob findPendingRunJob(String profileName, String executorId) {
        String normalizedProfile = firstNonBlank(profileName).toLowerCase(Locale.ROOT);
        String normalizedExecutor = normalizeExecutorId(executorId);
        long now = System.currentTimeMillis();

        IdeJob best = null;
        int bestScore = Integer.MIN_VALUE;
        for (IdeJob job : jobs.values()) {
            if (job == null) {
                continue;
            }
            if (!"run".equals(job.operation) && !"test".equals(job.operation)) {
                continue;
            }
            if (isTerminal(job.status)) {
                continue;
            }
            if (job.processAttached) {
                continue;
            }
            if (job.startedAt > 0 && now - job.startedAt > 120_000L) {
                continue;
            }

            int score = 0;
            String requestedCfg = firstNonBlank(job.requestedConfiguration).toLowerCase(Locale.ROOT);
            String resolvedCfg = firstNonBlank(job.resolvedConfiguration).toLowerCase(Locale.ROOT);
            if (!normalizedProfile.isBlank()) {
                if (!requestedCfg.isBlank() && requestedCfg.equals(normalizedProfile)) {
                    score += 4;
                } else if (!resolvedCfg.isBlank() && resolvedCfg.equals(normalizedProfile)) {
                    score += 3;
                } else if ((!requestedCfg.isBlank() && normalizedProfile.contains(requestedCfg))
                        || (!resolvedCfg.isBlank() && normalizedProfile.contains(resolvedCfg))) {
                    score += 2;
                }
            } else {
                score += 1;
            }

            String requestedExecutor = normalizeExecutorId(firstNonBlank(job.requestedExecutor, job.executorId));
            if (!requestedExecutor.isBlank() && !normalizedExecutor.isBlank() && requestedExecutor.equals(normalizedExecutor)) {
                score += 2;
            }

            if (job.startedAt > 0) {
                score += (int) Math.min(10, Math.max(0, (120_000L - (now - job.startedAt)) / 15_000L));
            }

            if (score > bestScore) {
                bestScore = score;
                best = job;
            }
        }
        return best;
    }

    private IdeJob findJobByProcessHandler(ProcessHandler handler) {
        for (IdeJob job : jobs.values()) {
            if (job != null && job.processHandler == handler) {
                return job;
            }
        }
        return null;
    }

    private String resolveProfileName(ExecutionEnvironment env) {
        if (env == null || env.getRunProfile() == null) {
            return "";
        }
        return firstNonBlank(env.getRunProfile().getName());
    }

    private String tailLogs(IdeJob job, int maxLines) {
        if (job == null) {
            return "";
        }
        int safeLines = Math.max(1, maxLines);
        StringBuilder sb = new StringBuilder();
        synchronized (job.logMonitor) {
            int size = job.logs.size();
            int start = Math.max(0, size - safeLines);
            for (int i = start; i < size; i++) {
                String line = job.logs.get(i);
                if (line == null || line.isBlank()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }

    private ObjectNode executeStatus(JsonNode request) {
        String jobId = firstNonBlank(request.path("jobId").asText(""));
        if (jobId.isBlank()) {
            return error("missing_job_id", "Field 'jobId' is required for action=status.");
        }
        long sinceRevision = request.path("sinceRevision").asLong(0L);
        long waitMs = Math.max(0L, Math.min(MAX_STATUS_WAIT_MS, request.path("waitMs").asLong(0L)));
        IdeJob job = jobs.get(jobId);
        if (job == null) {
            return error("job_not_found", "No IDE bridge job found for id=" + jobId);
        }
        if (waitMs > 0L) {
            awaitJobUpdate(job, sinceRevision, waitMs);
        }
        return jobSnapshot("status", job, sinceRevision);
    }

    private ObjectNode executeCancel(JsonNode request) {
        String jobId = firstNonBlank(request.path("jobId").asText(""));
        if (jobId.isBlank()) {
            return error("missing_job_id", "Field 'jobId' is required for action=cancel.");
        }
        IdeJob job = jobs.get(jobId);
        if (job == null) {
            return error("job_not_found", "No IDE bridge job found for id=" + jobId);
        }
        if (isTerminal(job.status)) {
            return jobSnapshot("cancel", job, 0L);
        }

        job.cancelRequested = true;
        job.updatedAt = System.currentTimeMillis();
        job.summary = "Cancel requested.";
        job.status = STATUS_CANCELING;
        appendLog(job, "cancel requested");

        ProcessHandler processHandler = job.processHandler;
        if (processHandler != null) {
            try {
                if (!processHandler.isProcessTerminated()) {
                    processHandler.destroyProcess();
                }
            } catch (Exception e) {
                appendLog(job, "cancel warning: " + safeMessage(e));
            }
        }

        Future<?> future = job.future;
        if (future != null) {
            future.cancel(true);
        }
        if (processHandler == null && (future == null || future.isDone())) {
            job.status = STATUS_CANCELED;
            job.summary = "Job canceled.";
            job.output = trimOutput(job.summary);
            job.completedAt = System.currentTimeMillis();
            job.cancellable = false;
            appendLog(job, job.summary);
        }
        return jobSnapshot("cancel", job, 0L);
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
        boolean interrupted = false;
        try {
            completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            completed = false;
            interrupted = true;
        }
        long durationMs = Math.max(0L, System.currentTimeMillis() - startedAt);

        if (!completed) {
            if (interrupted) {
                if (result.status == null || result.status.isBlank()) {
                    result.status = "canceled";
                    result.summary = "Build canceled.";
                }
            } else if (result.status == null || result.status.isBlank()) {
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

    private void applyResultToJob(IdeJob job, JsonNode response) {
        if (job == null || response == null) {
            return;
        }
        String rawStatus = firstNonBlank(response.path("status").asText(""));
        boolean ok = response.path("ok").asBoolean(false);
        String mapped = mapToJobStatus(rawStatus, ok);
        job.status = mapped;
        job.summary = firstNonBlank(
                response.path("summary").asText(""),
                mapped.equals(STATUS_SUCCEEDED) ? "IDE action succeeded." : "IDE action finished."
        );
        job.output = trimOutput(firstNonBlank(response.path("output").asText("")));
        appendLog(job, job.summary);
        appendLog(job, job.output);
        job.errors = response.path("errors").asInt(0);
        job.warnings = response.path("warnings").asInt(0);
        job.aborted = response.path("aborted").asBoolean(false);
        job.durationMs = response.path("durationMs").asLong(0L);
        if (isTerminal(mapped)) {
            job.completedAt = System.currentTimeMillis();
        }
    }

    private String mapToJobStatus(String status, boolean ok) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return ok ? STATUS_SUCCEEDED : STATUS_FAILED;
        }
        return switch (normalized) {
            case "pending", "queued" -> STATUS_PENDING;
            case "running" -> STATUS_RUNNING;
            case "success", "succeeded", "completed", "ok", "started" -> STATUS_SUCCEEDED;
            case "failed", "failure", "error" -> STATUS_FAILED;
            case "aborted" -> STATUS_ABORTED;
            case "timeout" -> STATUS_TIMEOUT;
            case "canceled", "cancelled" -> STATUS_CANCELED;
            case "canceling" -> STATUS_CANCELING;
            default -> ok ? STATUS_SUCCEEDED : STATUS_FAILED;
        };
    }

    private boolean isSupportedOperation(String operation) {
        return "build".equals(operation) || "run".equals(operation) || "test".equals(operation);
    }

    private boolean isTerminal(String status) {
        String normalized = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return STATUS_SUCCEEDED.equals(normalized)
                || STATUS_FAILED.equals(normalized)
                || STATUS_ABORTED.equals(normalized)
                || STATUS_CANCELED.equals(normalized)
                || STATUS_TIMEOUT.equals(normalized);
    }

    private String normalizeOperation(String raw) {
        String normalized = firstNonBlank(raw).toLowerCase(Locale.ROOT);
        if ("build".equals(normalized) || "run".equals(normalized) || "test".equals(normalized)) {
            return normalized;
        }
        return "";
    }

    private String normalizeExecutorId(String raw) {
        String normalized = firstNonBlank(raw).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.contains("debug")) {
            return "debug";
        }
        if (normalized.contains("run")) {
            return "run";
        }
        return normalized;
    }

    private ObjectNode jobSnapshot(String action, IdeJob job, long sinceRevision) {
        ObjectNode node = mapper.createObjectNode();
        if (job == null) {
            node.put("ok", false);
            node.put("action", firstNonBlank(action, "status"));
            node.put("status", "error");
            node.put("summary", "Job not found.");
            node.put("output", "Job not found.");
            return node;
        }
        String status = firstNonBlank(job.status, STATUS_PENDING);
        boolean ok = !STATUS_FAILED.equals(status) && !STATUS_TIMEOUT.equals(status);
        node.put("ok", ok);
        node.put("action", firstNonBlank(action, "status"));
        node.put("operation", firstNonBlank(job.operation));
        node.put("jobId", firstNonBlank(job.id));
        node.put("status", status);
        node.put("summary", firstNonBlank(job.summary));
        node.put("output", trimStatusOutput(firstNonBlank(job.output)));
        node.put("configuration", firstNonBlank(job.resolvedConfiguration, job.requestedConfiguration));
        node.put("executor", firstNonBlank(job.executorId, job.requestedExecutor));
        node.put("processAttached", job.processAttached);
        node.put("errors", Math.max(0, job.errors));
        node.put("warnings", Math.max(0, job.warnings));
        node.put("aborted", job.aborted);
        node.put("cancelRequested", job.cancelRequested);
        node.put("cancellable", job.cancellable && !isTerminal(status));
        node.put("startedAt", Math.max(0L, job.startedAt));
        node.put("updatedAt", Math.max(0L, job.updatedAt));
        node.put("completedAt", Math.max(0L, job.completedAt));
        node.put("durationMs", computeDurationMs(job));
        if (job.exitCode != null) {
            node.put("exitCode", job.exitCode);
        }
        appendSnapshotLogs(node, job, sinceRevision);
        return node;
    }

    private void appendSnapshotLogs(ObjectNode node, IdeJob job, long sinceRevision) {
        if (node == null || job == null) {
            return;
        }
        long from = Math.max(0L, sinceRevision);
        ArrayNode logsNode = node.putArray("logs");
        synchronized (job.logMonitor) {
            int size = job.logs.size();
            int start = (int) Math.min(Math.max(0L, from), size);
            for (int i = start; i < size; i++) {
                logsNode.add(firstNonBlank(job.logs.get(i)));
            }
            node.put("fromRevision", start);
            node.put("logRevision", size);
        }
    }

    private long computeDurationMs(IdeJob job) {
        if (job == null) {
            return 0L;
        }
        if (job.durationMs > 0) {
            return job.durationMs;
        }
        long started = Math.max(0L, job.startedAt);
        long end = job.completedAt > 0 ? job.completedAt : System.currentTimeMillis();
        if (started <= 0) {
            return 0L;
        }
        return Math.max(0L, end - started);
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
        if ("canceled".equalsIgnoreCase(status)) {
            return "IDE build canceled.";
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

    private String trimStatusOutput(String output) {
        if (output == null) {
            return "";
        }
        if (output.length() <= MAX_STATUS_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_STATUS_OUTPUT_CHARS) + "\n... output truncated ...";
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

    private void appendLog(IdeJob job, String line) {
        if (job == null || line == null) {
            return;
        }
        String normalized = line.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (normalized.isBlank()) {
            return;
        }
        String[] rows = normalized.split("\n");
        synchronized (job.logMonitor) {
            for (String row : rows) {
                String trimmed = row == null ? "" : row.trim();
                if (trimmed.isBlank()) {
                    continue;
                }
                job.logs.add(trimmed);
            }
            while (job.logs.size() > MAX_JOB_LOG_LINES) {
                job.logs.remove(0);
            }
            job.logMonitor.notifyAll();
        }
    }

    private void awaitJobUpdate(IdeJob job, long sinceRevision, long waitMs) {
        if (job == null || waitMs <= 0L) {
            return;
        }
        long safeSince = Math.max(0L, sinceRevision);
        long deadline = System.currentTimeMillis() + waitMs;
        synchronized (job.logMonitor) {
            while (true) {
                if (job.logs.size() > safeSince || isTerminal(job.status)) {
                    break;
                }
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0L) {
                    break;
                }
                try {
                    job.logMonitor.wait(Math.min(remain, 1000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private static final class BuildResult {
        String status;
        String summary;
        String output;
        int errors;
        int warnings;
        boolean aborted;
    }

    private static final class IdeJob {
        final String id;
        final String operation;
        volatile String status = STATUS_PENDING;
        volatile String summary = "";
        volatile String output = "";
        volatile int errors;
        volatile int warnings;
        volatile boolean aborted;
        volatile boolean cancelRequested;
        volatile boolean cancellable;
        volatile long startedAt;
        volatile long updatedAt;
        volatile long completedAt;
        volatile long durationMs;
        volatile String requestedConfiguration = "";
        volatile String resolvedConfiguration = "";
        volatile String requestedExecutor = "";
        volatile String executorId = "";
        volatile boolean processAttached;
        volatile ProcessHandler processHandler;
        volatile Integer exitCode;
        volatile Future<?> future;
        final Object logMonitor = new Object();
        final List<String> logs = new ArrayList<>();

        private IdeJob(String id, String operation) {
            this.id = id;
            this.operation = operation;
        }
    }
}
