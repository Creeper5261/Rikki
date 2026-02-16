package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.core.build.BuildSystemDetection;
import com.zzf.codeagent.core.build.BuildSystemDetector;
import com.zzf.codeagent.core.build.BuildSystemType;
import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.shell.ShellService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BashTool 实现 (对齐 opencode/src/tool/bash.ts)
 */
@Component
@Slf4j
public class BashTool implements Tool {
    private static final long DEFAULT_TIMEOUT_MS = 60000;
    private static final int MAX_METADATA_LENGTH = 1000;
    private static final int MAX_OUTPUT_LENGTH = 8000;
    private static final long SELF_HEAL_PROBE_TIMEOUT_MS = 8000L;
    private static final long SELF_HEAL_FIX_TIMEOUT_MS = 120000L;
    private static final String DEFAULT_WRAPPER_GRADLE_VERSION =
            System.getProperty("codeagent.bash.selfHeal.wrapperVersion", "8.5");
    private static final boolean FAIL_ON_NON_ZERO_EXIT =
            Boolean.parseBoolean(System.getProperty("codeagent.bash.failOnNonZeroExit", "true"));
    private static final boolean BUILD_SELF_HEAL_ENABLED =
            Boolean.parseBoolean(System.getProperty("codeagent.bash.selfHeal.enabled", "true"));
    private static final boolean REQUIRE_IDE_JAVA_FOR_BUILD_WHEN_IDE_CONTEXT =
            Boolean.parseBoolean(System.getProperty("codeagent.bash.requireIdeJavaForBuildWhenIdeContext", "true"));
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Pattern JAVA_VERSION_PATTERN =
            Pattern.compile("(?i)version\\s+\"(\\d+)(?:\\.(\\d+))?");
    private static final Pattern GRADLE_VERSION_PATTERN =
            Pattern.compile("(?im)^\\s*gradle\\s+(\\d+)(?:\\.(\\d+))?");
    private static final Pattern WRAPPER_DIST_PATTERN =
            Pattern.compile("gradle-([0-9]+(?:\\.[0-9]+){0,2})-(?:bin|all)\\.zip", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUILD_TOOL_PATTERN =
            Pattern.compile("(?i)(^|\\s|/|\\\\)(gradle|gradlew|mvn|mvnw)(\\.bat)?(\\s|$)");
    private static final Pattern BUILD_TASK_PATTERN =
            Pattern.compile("(?i)\\b(build|compilejava|assemble|check|test|bootjar|classes)\\b");
    
    private final ShellService shellService;
    private final ProjectContext projectContext;
    private final ResourceLoader resourceLoader;

    public BashTool(ShellService shellService, ProjectContext projectContext, ResourceLoader resourceLoader) {
        this.shellService = shellService;
        this.projectContext = projectContext;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getId() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return loadDescription();
    }

    private String loadDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/bash.txt");
            if (resource.exists()) {
                String desc = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                return desc.replace("${directory}", projectContext.getDirectory());
            }
        } catch (IOException e) {
            log.error("Failed to load bash tool description", e);
        }
        // Fallback description if resource is missing
        return "Executes a given bash command in a persistent shell session.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("command").put("type", "string").put("description", "The command to execute");
        properties.putObject("timeout").put("type", "integer").put("description", "Optional timeout in milliseconds");
        properties.putObject("workdir").put("type", "string").put("description", "The working directory to run the command in. Defaults to " + projectContext.getDirectory());
        properties.putObject("description").put("type", "string").put("description", "Clear, concise description of what this command does in 5-10 words.");

        schema.putArray("required").add("command").add("description");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            String command = args.get("command").asText();
            String description = args.has("description") ? args.get("description").asText("") : "";
            if (description == null || description.isBlank()) {
                description = "Run shell command";
            }
            long timeout = args.has("timeout") ? args.get("timeout").asLong() : DEFAULT_TIMEOUT_MS;
            String workspaceRoot = ToolPathResolver.resolveWorkspaceRoot(projectContext, ctx);
            String workdirStr = args.has("workdir") ? args.get("workdir").asText() : workspaceRoot;
            Path workdir = ToolPathResolver.resolvePath(projectContext, ctx, workdirStr);
            String shell = resolveExecutionShell(shellService.acceptable(), command);

            RiskAssessment risk = assessRisk(command);
            String sessionId = ctx != null ? ctx.getSessionID() : null;
            String commandFamily = extractCommandFamily(command);
            boolean autoApprovedByPolicy = risk.requiresApproval
                    && PendingCommandsManager.getInstance().shouldAutoApprove(sessionId, commandFamily, risk.strictApproval);
            if (risk.requiresApproval && !autoApprovedByPolicy) {
                String pendingId = UUID.randomUUID().toString();
                PendingCommandsManager.PendingCommand pending = new PendingCommandsManager.PendingCommand(
                        pendingId,
                        command,
                        description,
                        workdir.toString(),
                        shell,
                        workspaceRoot,
                        sessionId,
                        ctx != null ? ctx.getMessageID() : null,
                        ctx != null ? ctx.getCallID() : null,
                        timeout,
                        "high",
                        risk.reasons,
                        commandFamily,
                        risk.riskCategory,
                        risk.strictApproval,
                        System.currentTimeMillis()
                );
                PendingCommandsManager.getInstance().add(pending);

                Map<String, Object> pendingMeta = new HashMap<>();
                pendingMeta.put("description", description);
                pendingMeta.put("command", command);
                pendingMeta.put("risk_level", "high");
                pendingMeta.put("risk_reasons", risk.reasons);
                pendingMeta.put("risk_category", risk.riskCategory);
                pendingMeta.put("strict_approval", risk.strictApproval);
                pendingMeta.put("command_family", pending.commandFamily);
                pendingMeta.put("pending_command_id", pending.id);
                pendingMeta.put("pending_command", pending);
                pendingMeta.put("shell", shell);
                pendingMeta.put("requires_explicit_user_consent", true);
                pendingMeta.put("approval_type", risk.strictApproval ? "strict" : "policy_available");
                if (!risk.strictApproval) {
                    pendingMeta.put("approval_options", List.of(
                            PendingCommandsManager.DECISION_MANUAL,
                            PendingCommandsManager.DECISION_WHITELIST,
                            PendingCommandsManager.DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE
                    ));
                }

                return Result.builder()
                        .title(description)
                        .output("High-risk command requires user approval before execution. Command was not executed.")
                        .metadata(pendingMeta)
                        .build();
            }

            // 1. Permission Check (aligned with opencode ctx.ask)
            // Simplified: we skip tree-sitter parsing for now as it's complex in Java, 
            // but we keep the permission structure.
            Map<String, Object> permissionRequest = new HashMap<>();
            permissionRequest.put("permission", "bash");
            permissionRequest.put("patterns", new String[]{command});
            permissionRequest.put("always", new String[]{command.split(" ")[0] + "*"});
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("command", command);
            metadata.put("workspaceRoot", workspaceRoot);
            permissionRequest.put("metadata", metadata);

            try {
                ctx.ask(permissionRequest).get(); // Wait for permission
            } catch (Exception e) {
                throw new RuntimeException("Permission denied or error during permission check", e);
            }

            try {
                String preparedCommand = prepareCommandForShell(command, shell);
                IdeHints ideHints = resolveIdeHints(ctx);
                if (shouldRequireIdeJavaForBuild(command, ctx)
                        && (ideHints == null || ideHints.javaHome == null || ideHints.javaHome.isBlank())) {
                    throw new RuntimeException("Cannot guarantee IDE build environment: IDE Java home is unavailable in IDE context. Configure IDEA Project SDK/JDK path and retry.");
                }
                String effectiveCommand = maybeApplyIdeJavaHomeOverride(command, preparedCommand, shell, ideHints);
                CommandExecution primary = executeCommand(
                        effectiveCommand,
                        shell,
                        workdir,
                        timeout,
                        description,
                        ctx,
                        true
                );

                BuildSelfHealReport healReport = null;
                CommandExecution finalExecution = primary;
                String finalCommand = effectiveCommand;

                if (isBuildLikeCommand(command) && BUILD_SELF_HEAL_ENABLED) {
                    healReport = processBuildSelfHeal(command, effectiveCommand, shell, workdir, timeout, primary, ideHints);
                    if (healReport != null && healReport.hasSuccessfulRetry()) {
                        finalExecution = healReport.retryExecution;
                        finalCommand = healReport.retryCommand;
                    }
                }

                String finalOutput = finalExecution.output;
                if (healReport != null) {
                    finalOutput = appendSelfHealContext(finalOutput, healReport);
                }
                Map<String, Object> resultMetadata = new HashMap<>();
                resultMetadata.put("output", truncate(finalOutput, MAX_METADATA_LENGTH));
                resultMetadata.put("exit", finalExecution.exitCode);
                resultMetadata.put("description", description);
                resultMetadata.put("command", finalCommand);
                resultMetadata.put("original_command", command);
                resultMetadata.put("shell", shell);
                resultMetadata.put("ide_java_override_applied", !effectiveCommand.equals(preparedCommand));
                if (healReport != null) {
                    resultMetadata.put("self_heal", healReport.toMetadata());
                }

                if (finalExecution.timedOut) {
                    String timeoutMsg = "Command timed out after " + timeout + " ms: " + command;
                    String timeoutDetails = truncate(finalOutput, MAX_OUTPUT_LENGTH);
                    throw new RuntimeException(timeoutMsg + (timeoutDetails.isBlank() ? "" : "\n" + timeoutDetails));
                }

                if (FAIL_ON_NON_ZERO_EXIT && finalExecution.exitCode != 0) {
                    String err = truncate(finalOutput == null ? "" : finalOutput.trim(), MAX_OUTPUT_LENGTH);
                    String msg = "Command failed with exit code " + finalExecution.exitCode + ": " + command;
                    throw new RuntimeException(err.isEmpty() ? msg : msg + "\n" + err);
                }

                return Result.builder()
                        .title(description)
                        .output(truncate(finalOutput, MAX_OUTPUT_LENGTH))
                        .metadata(resultMetadata)
                        .build();

            } catch (Exception e) {
                log.error("Failed to execute bash command", e);
                throw new RuntimeException(e.getMessage());
            }
        });
    }

    private CommandExecution executeCommand(
            String command,
            String shell,
            Path workdir,
            long timeoutMs,
            String description,
            Context ctx,
            boolean streamMetadata
    ) throws Exception {
        StringBuilder output = new StringBuilder();
        boolean windows = isWindows();
        String lowerShell = shell == null ? "" : shell.toLowerCase(Locale.ROOT);

        ProcessBuilder pb;
        if (windows && lowerShell.endsWith("cmd.exe")) {
            pb = new ProcessBuilder("cmd.exe", "/c", command);
        } else if (isPowerShell(lowerShell)) {
            pb = new ProcessBuilder(shell, "-NoProfile", "-NonInteractive", "-Command", command);
        } else if (lowerShell.endsWith("bash") || lowerShell.endsWith("bash.exe")) {
            pb = new ProcessBuilder(shell, "-lc", command);
        } else {
            pb = new ProcessBuilder(shell, "-c", command);
        }

        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);

        long startedAt = System.currentTimeMillis();
        Process process = pb.start();

        if (streamMetadata && ctx != null) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("output", "");
            meta.put("description", description);
            ctx.metadata(description, meta);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (streamMetadata && ctx != null && output.length() < MAX_METADATA_LENGTH) {
                    Map<String, Object> updateMeta = new HashMap<>();
                    updateMeta.put("output", output.toString());
                    updateMeta.put("description", description);
                    ctx.metadata(description, updateMeta);
                }
            }
        }

        boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            shellService.killTree(process);
            output.append("\nCommand terminated due to timeout (").append(timeoutMs).append(" ms).");
            return new CommandExecution(command, -1, output.toString(), true, shell, System.currentTimeMillis() - startedAt);
        }

        return new CommandExecution(
                command,
                process.exitValue(),
                output.toString(),
                false,
                shell,
                System.currentTimeMillis() - startedAt
        );
    }

    private BuildSelfHealReport processBuildSelfHeal(
            String originalCommand,
            String preparedCommand,
            String shell,
            Path workdir,
            long timeoutMs,
            CommandExecution primary,
            IdeHints ideHints
    ) {
        if (primary == null || primary.exitCode == 0 || primary.timedOut) {
            return null;
        }

        BuildSystemDetection detection = BuildSystemDetector.detect(workdir, preparedCommand);
        BuildProbe probe = probeBuildEnvironment(shell, workdir);
        List<String> diagnoses = buildSelfHealDiagnostics(originalCommand, primary.output, probe, ideHints, detection);

        BuildSelfHealReport report = new BuildSelfHealReport();
        report.originalCommand = originalCommand;
        report.preparedCommand = preparedCommand;
        report.probe = probe;
        report.ideHints = ideHints;
        report.detection = detection;
        report.diagnoses.addAll(diagnoses);

        String quotedCommand = quoteLeadingWindowsExecutable(preparedCommand);
        boolean canApplyQuoteFix = isWindowsBashShell(shell)
                && !quotedCommand.equals(preparedCommand)
                && looksLikeWindowsPathQuotingFailure(primary.output, preparedCommand);

        if (canApplyQuoteFix) {
            report.fixStrategy = "quote_windows_executable";
            report.fixCommand = quotedCommand;
            try {
                report.retryCommand = quotedCommand;
                report.retryExecution = executeCommand(
                        quotedCommand,
                        shell,
                        workdir,
                        timeoutMs,
                        "Retry build command after quoting executable",
                        null,
                        false
                );
            } catch (Exception e) {
                report.fixError = e.getMessage();
            }
            return report;
        }

        boolean mavenCommand = shouldTryMavenFix(detection, preparedCommand, probe);
        boolean mavenCommandMissing = looksLikeMavenMissingFailure(primary.output);
        if (mavenCommand && mavenCommandMissing) {
            String wrapperCommand = resolveMavenWrapperRetryCommand(preparedCommand, probe);
            if (wrapperCommand != null && !wrapperCommand.isBlank()) {
                report.fixStrategy = "switch_to_maven_wrapper";
                report.fixCommand = wrapperCommand;
                try {
                    report.retryCommand = wrapperCommand;
                    report.retryExecution = executeCommand(
                            wrapperCommand,
                            shell,
                            workdir,
                            timeoutMs,
                            "Retry build command via Maven wrapper",
                            null,
                            false
                    );
                } catch (Exception e) {
                    report.fixError = e.getMessage();
                }
                return report;
            }
        }

        boolean gradleWrapperFixSignal = shouldTryGradleWrapperFix(detection, preparedCommand, probe, primary.output);
        if (gradleWrapperFixSignal && probe.gradleAvailable) {
            String wrapperVersion = probe.wrapperDistributionVersion == null || probe.wrapperDistributionVersion.isBlank()
                    ? DEFAULT_WRAPPER_GRADLE_VERSION
                    : probe.wrapperDistributionVersion;
            String fixCommand = "gradle wrapper --gradle-version " + wrapperVersion;
            report.fixStrategy = "regenerate_gradle_wrapper";
            report.fixCommand = fixCommand;
            try {
                report.fixExecution = executeCommand(
                        fixCommand,
                        shell,
                        workdir,
                        Math.max(SELF_HEAL_FIX_TIMEOUT_MS, timeoutMs),
                        "Regenerate Gradle wrapper",
                        null,
                        false
                );
                report.wrapperJarExistsAfterFix = Files.exists(workdir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar"));
                if (report.fixExecution.exitCode == 0 && report.wrapperJarExistsAfterFix) {
                    report.retryCommand = preparedCommand;
                    report.retryExecution = executeCommand(
                            preparedCommand,
                            shell,
                            workdir,
                            timeoutMs,
                            "Retry build command after wrapper fix",
                            null,
                            false
                    );
                }
            } catch (Exception e) {
                report.fixError = e.getMessage();
            }
            return report;
        }

        boolean canUseIdeJavaHome = ideHints != null
                && ideHints.javaHome != null
                && !ideHints.javaHome.isBlank();
        boolean ideJavaMismatch = canUseIdeJavaHome
                && ideHints.javaMajor != null
                && probe.javaMajor != null
                && !ideHints.javaMajor.equals(probe.javaMajor);
        boolean ideJavaCompatible = isIdeJavaCompatibleWithProject(ideHints, probe);
        if (canUseIdeJavaHome
                && ideJavaCompatible
                && (ideJavaMismatch || looksLikeJavaMismatchFailure(primary.output))) {
            String overrideCommand = buildJavaHomeOverrideCommand(preparedCommand, ideHints.javaHome, shell);
            if (overrideCommand != null && !overrideCommand.isBlank() && !overrideCommand.equals(preparedCommand)) {
                report.fixStrategy = "use_ide_project_sdk";
                report.fixCommand = overrideCommand;
                try {
                    report.retryCommand = overrideCommand;
                    report.retryExecution = executeCommand(
                            overrideCommand,
                            shell,
                            workdir,
                            timeoutMs,
                            "Retry build command via IDE project SDK",
                            null,
                            false
                    );
                } catch (Exception e) {
                    report.fixError = e.getMessage();
                }
                return report;
            }
        }

        return report;
    }

    private BuildProbe probeBuildEnvironment(String shell, Path workdir) {
        String resolvedShell = resolveShell(shell);
        BuildProbe probe = new BuildProbe();
        probe.windows = isWindows();
        probe.shell = resolvedShell;
        probe.shellBashLikeOnWindows = probe.windows && isWindowsBashShell(resolvedShell);
        probe.javaHome = safeEnv("JAVA_HOME");

        try {
            CommandExecution javaInfo = executeCommand(
                    "java -version",
                    resolvedShell,
                    workdir,
                    SELF_HEAL_PROBE_TIMEOUT_MS,
                    "Probe java version",
                    null,
                    false
            );
            probe.javaVersionOutput = firstNonBlank(javaInfo.output);
            probe.javaMajor = parseJavaMajor(probe.javaVersionOutput);
        } catch (Exception ignored) {
            // no-op
        }

        try {
            CommandExecution gradleInfo = executeCommand(
                    "gradle -v",
                    resolvedShell,
                    workdir,
                    SELF_HEAL_PROBE_TIMEOUT_MS,
                    "Probe gradle version",
                    null,
                    false
            );
            probe.gradleAvailable = gradleInfo.exitCode == 0;
            probe.gradleVersionOutput = firstNonBlank(gradleInfo.output);
            probe.gradleMajor = parseGradleMajor(probe.gradleVersionOutput);
        } catch (Exception ignored) {
            // no-op
        }

        try {
            CommandExecution mavenInfo = executeCommand(
                    "mvn -v",
                    resolvedShell,
                    workdir,
                    SELF_HEAL_PROBE_TIMEOUT_MS,
                    "Probe maven version",
                    null,
                    false
            );
            probe.mavenAvailable = mavenInfo.exitCode == 0;
            probe.mavenVersionOutput = firstNonBlank(mavenInfo.output);
        } catch (Exception ignored) {
            // no-op
        }

        probe.gradlewExists = Files.exists(workdir.resolve("gradlew"));
        probe.gradlewBatExists = Files.exists(workdir.resolve("gradlew.bat"));
        probe.wrapperJarExists = Files.exists(workdir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar"));
        probe.wrapperPropertiesExists = Files.exists(workdir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties"));
        probe.pomExists = Files.exists(workdir.resolve("pom.xml"));
        probe.mavenwExists = Files.exists(workdir.resolve("mvnw"));
        probe.mavenwCmdExists = Files.exists(workdir.resolve("mvnw.cmd"));
        probe.wrapperDistributionVersion = extractWrapperDistributionVersion(workdir);
        probe.wrapperGradleMajor = parseGradleMajor(firstNonBlank(probe.wrapperDistributionVersion));
        probe.requiredJavaMajor = readRequiredJavaMajor(workdir);
        return probe;
    }

    private List<String> buildSelfHealDiagnostics(
            String command,
            String output,
            BuildProbe probe,
            IdeHints ideHints,
            BuildSystemDetection detection
    ) {
        List<String> diagnostics = new ArrayList<>();
        String normalizedOutput = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (detection != null && detection.getPrimary() != BuildSystemType.UNKNOWN) {
            diagnostics.add("Detected build system: " + detection.getPrimary().name().toLowerCase(Locale.ROOT) + ".");
        }

        if ((probe.gradlewExists && !probe.wrapperJarExists) || normalizedOutput.contains("gradlewrappermain")) {
            diagnostics.add("Gradle wrapper appears broken (gradle-wrapper.jar missing or wrapper main class not found).");
        }
        if (looksLikeWindowsPathQuotingFailure(output, command)) {
            diagnostics.add("Windows executable path may be unquoted in bash shell.");
        }
        if (probe.requiredJavaMajor != null && probe.javaMajor != null && !probe.requiredJavaMajor.equals(probe.javaMajor)) {
            diagnostics.add("Project requires Java " + probe.requiredJavaMajor + " but runtime Java is " + probe.javaMajor + ".");
        }
        if (probe.wrapperGradleMajor != null && probe.javaMajor != null && probe.javaMajor >= 21 && probe.wrapperGradleMajor < 8) {
            diagnostics.add("Gradle wrapper version " + probe.wrapperDistributionVersion + " may be incompatible with Java " + probe.javaMajor + ".");
        }
        if ((probe.gradlewExists && !probe.wrapperJarExists) && !probe.gradleAvailable) {
            diagnostics.add("Cannot regenerate wrapper automatically because local gradle command is unavailable.");
        }
        if (probe.pomExists && !probe.mavenAvailable && !probe.mavenwExists && !probe.mavenwCmdExists) {
            diagnostics.add("Maven project detected but mvn/mvnw is unavailable.");
        }
        if (looksLikeMavenMissingFailure(output) && (probe.mavenwExists || probe.mavenwCmdExists)) {
            diagnostics.add("Local Maven command missing; Maven wrapper is available and can be used.");
        }
        if (ideHints != null) {
            if (ideHints.javaMajor != null && probe.javaMajor != null && !ideHints.javaMajor.equals(probe.javaMajor)) {
                diagnostics.add("IDE project SDK Java " + ideHints.javaMajor + " differs from runtime Java " + probe.javaMajor + ".");
            }
            if (!isIdeJavaCompatibleWithProject(ideHints, probe)) {
                diagnostics.add("IDE project SDK Java does not match project-required Java version.");
            }
            if (ideHints.javaHome != null && !ideHints.javaHome.isBlank() && looksLikeJavaMismatchFailure(output)) {
                diagnostics.add("Build output suggests Java mismatch; IDE project SDK home is available for retry.");
            }
        }
        if (diagnostics.isEmpty()) {
            diagnostics.add("Build failed; no deterministic auto-fix rule matched.");
        }
        return diagnostics;
    }

    private String appendSelfHealContext(String output, BuildSelfHealReport report) {
        if (report == null) {
            return firstNonBlank(output);
        }
        StringBuilder sb = new StringBuilder();
        String baseOutput = firstNonBlank(output);
        if (!baseOutput.isBlank()) {
            sb.append(baseOutput.trim());
        }

        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append("[build-self-heal]").append('\n');
        if (!report.diagnoses.isEmpty()) {
            sb.append("Diagnoses:").append('\n');
            for (String diag : report.diagnoses) {
                sb.append("- ").append(diag).append('\n');
            }
        }
        if (report.fixStrategy != null && !report.fixStrategy.isBlank()) {
            sb.append("Fix strategy: ").append(report.fixStrategy).append('\n');
            if (report.fixCommand != null && !report.fixCommand.isBlank()) {
                sb.append("Fix command: ").append(report.fixCommand).append('\n');
            }
            if (report.fixExecution != null) {
                sb.append("Fix exit: ").append(report.fixExecution.exitCode).append('\n');
            }
            if (report.fixError != null && !report.fixError.isBlank()) {
                sb.append("Fix error: ").append(report.fixError).append('\n');
            }
        }
        if (report.retryExecution != null) {
            sb.append("Retry command: ").append(firstNonBlank(report.retryCommand, report.preparedCommand)).append('\n');
            sb.append("Retry exit: ").append(report.retryExecution.exitCode).append('\n');
            if (report.retryExecution.exitCode == 0 && report.retryExecution.output != null && !report.retryExecution.output.isBlank()) {
                sb.append('\n').append(report.retryExecution.output.trim()).append('\n');
            }
        }

        return sb.toString().trim();
    }

    private String extractWrapperDistributionVersion(Path workdir) {
        try {
            Path propsPath = workdir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties");
            if (!Files.exists(propsPath)) {
                return "";
            }
            List<String> lines = Files.readAllLines(propsPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                Matcher matcher = WRAPPER_DIST_PATTERN.matcher(line);
                if (matcher.find()) {
                    return firstNonBlank(matcher.group(1));
                }
            }
        } catch (Exception ignored) {
            // no-op
        }
        return "";
    }

    private Integer readRequiredJavaMajor(Path workdir) {
        List<Path> candidates = List.of(
                workdir.resolve("build.gradle"),
                workdir.resolve("build.gradle.kts")
        );
        for (Path file : candidates) {
            if (!Files.exists(file)) {
                continue;
            }
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Integer major = parseRequiredJavaMajor(content);
                if (major != null) {
                    return major;
                }
            } catch (Exception ignored) {
                // no-op
            }
        }
        return null;
    }

    private Integer parseRequiredJavaMajor(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Pattern[] patterns = new Pattern[]{
                Pattern.compile("sourceCompatibility\\s*=\\s*['\"]?(\\d{1,2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("JavaVersion\\.VERSION_(\\d{1,2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("JavaLanguageVersion\\.of\\((\\d{1,2})\\)", Pattern.CASE_INSENSITIVE)
        };
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    static Integer parseJavaMajor(String versionOutput) {
        if (versionOutput == null || versionOutput.isBlank()) {
            return null;
        }
        Matcher versionMatcher = JAVA_VERSION_PATTERN.matcher(versionOutput);
        if (versionMatcher.find()) {
            int major = Integer.parseInt(versionMatcher.group(1));
            if (major == 1 && versionMatcher.group(2) != null) {
                return Integer.parseInt(versionMatcher.group(2));
            }
            return major;
        }
        Matcher fallback = Pattern.compile("(?i)\\b(?:openjdk|java)\\s+(\\d{1,2})").matcher(versionOutput);
        if (fallback.find()) {
            return Integer.parseInt(fallback.group(1));
        }
        return null;
    }

    static Integer parseGradleMajor(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher versionMatcher = GRADLE_VERSION_PATTERN.matcher(text);
        if (versionMatcher.find()) {
            return Integer.parseInt(versionMatcher.group(1));
        }
        Matcher distMatcher = WRAPPER_DIST_PATTERN.matcher(text);
        if (distMatcher.find()) {
            String version = distMatcher.group(1);
            if (version != null) {
                String[] parts = version.split("\\.");
                if (parts.length > 0) {
                    return Integer.parseInt(parts[0]);
                }
            }
        }
        return null;
    }

    static boolean isBuildLikeCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.replace('\r', ' ').replace('\n', ' ');
        return BUILD_TOOL_PATTERN.matcher(normalized).find() && BUILD_TASK_PATTERN.matcher(normalized).find();
    }

    static String quoteLeadingWindowsExecutable(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String trimmed = command.trim();
        if (!trimmed.matches("^[A-Za-z]:\\\\.*")) {
            return command;
        }
        if (trimmed.startsWith("\"") || trimmed.startsWith("'")) {
            return command;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        int exeIndex = lower.indexOf(".exe");
        if (exeIndex < 0) {
            return command;
        }
        int pathEnd = exeIndex + 4;
        String executable = trimmed.substring(0, pathEnd);
        if (!executable.contains(" ")) {
            return command;
        }
        String rest = trimmed.substring(pathEnd);
        return "\"" + executable + "\"" + rest;
    }

    private boolean looksLikeWindowsPathQuotingFailure(String output, String command) {
        String normalizedOutput = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (normalizedOutput.contains("c:program: command not found")) {
            return true;
        }
        return normalizedOutput.contains("command not found")
                && !quoteLeadingWindowsExecutable(command).equals(command)
                && isWindows();
    }

    private String prepareCommandForShell(String command, String shell) {
        if (command == null) {
            return "";
        }
        String normalized = command
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        String lowerShell = shell == null ? "" : shell.toLowerCase(Locale.ROOT);
        if (normalized.contains("\n")) {
            if (isPowerShell(lowerShell)) {
                normalized = normalized.replace("\n", "; ");
            } else if (isWindows() && lowerShell.endsWith("cmd.exe")) {
                normalized = normalized.replace("\n", " && ");
            } else {
                normalized = normalized.replace("\n", " && ");
            }
        }
        if (isWindowsBashShell(shell)) {
            return quoteLeadingWindowsExecutable(normalized);
        }
        return normalized;
    }

    private boolean looksLikeMavenCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("mvn ")
                || normalized.equals("mvn")
                || normalized.startsWith(".\\mvnw")
                || normalized.startsWith("./mvnw")
                || normalized.startsWith("mvnw ");
    }

    private boolean looksLikeGradleCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("gradle ")
                || normalized.equals("gradle")
                || normalized.startsWith(".\\gradlew")
                || normalized.startsWith("./gradlew")
                || normalized.startsWith("gradlew ");
    }

    private boolean shouldTryMavenFix(BuildSystemDetection detection, String preparedCommand, BuildProbe probe) {
        boolean commandLooksMaven = looksLikeMavenCommand(preparedCommand);
        if (commandLooksMaven) {
            return true;
        }
        if (detection != null && detection.supports(BuildSystemType.MAVEN)) {
            return probe != null && (probe.mavenwExists || probe.mavenwCmdExists || probe.pomExists);
        }
        return false;
    }

    private boolean shouldTryGradleWrapperFix(
            BuildSystemDetection detection,
            String preparedCommand,
            BuildProbe probe,
            String primaryOutput
    ) {
        if (probe == null) {
            return false;
        }
        boolean wrapperMissing = probe.gradlewExists && !probe.wrapperJarExists;
        boolean wrapperRelatedFailure = primaryOutput != null
                && primaryOutput.toLowerCase(Locale.ROOT).contains("gradlewrappermain");
        if (wrapperMissing || wrapperRelatedFailure) {
            return true;
        }
        if (looksLikeGradleCommand(preparedCommand)) {
            return true;
        }
        return detection != null && detection.supports(BuildSystemType.GRADLE);
    }

    private boolean looksLikeMavenMissingFailure(String output) {
        String normalized = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return normalized.contains("mvn: command not found")
                || normalized.contains("'mvn' is not recognized")
                || normalized.contains("mvn is not recognized");
    }

    private String resolveMavenWrapperRetryCommand(String command, BuildProbe probe) {
        if (command == null || command.isBlank() || probe == null) {
            return "";
        }
        if (isWindows() && probe.mavenwCmdExists) {
            return replaceLeadingWord(command, "mvn", ".\\mvnw.cmd");
        }
        if (probe.mavenwExists) {
            return replaceLeadingWord(command, "mvn", "./mvnw");
        }
        return "";
    }

    private String replaceLeadingWord(String command, String target, String replacement) {
        if (command == null || command.isBlank() || target == null || target.isBlank()
                || replacement == null || replacement.isBlank()) {
            return command;
        }
        String trimmed = command.trim();
        String replaced = trimmed.replaceFirst("(?i)^" + Pattern.quote(target) + "\\b", Matcher.quoteReplacement(replacement));
        return replaced.equals(trimmed) ? command : replaced;
    }

    private IdeHints resolveIdeHints(Context ctx) {
        IdeHints hints = new IdeHints();
        if (ctx == null || ctx.getExtra() == null || ctx.getExtra().isEmpty()) {
            return hints;
        }
        Object ideContextObj = ctx.getExtra().get("ideContext");
        if (ideContextObj instanceof Map<?, ?> ideMap) {
            hints.javaHome = normalizePotentialJavaHome(readString(
                    ideMap,
                    "projectSdkResolvedHome",
                    "projectSdkHome",
                    "gradleJvmResolvedHome",
                    "mavenRunnerResolvedHome",
                    "runConfigResolvedHome",
                    "sdkHomePath",
                    "javaHome"
            ));
            if ((hints.javaHome == null || hints.javaHome.isBlank())) {
                hints.javaHome = normalizePotentialJavaHome(readFirstStringFromList(ideMap.get("runConfigResolvedHomes")));
            }
            hints.javaMajor = readInteger(ideMap, "projectSdkMajor", "sdkMajor", "javaMajor");
            hints.languageLevel = readString(ideMap, "languageLevel");
            if (hints.javaMajor == null) {
                Integer fromVersion = parseJavaMajor(readString(ideMap, "projectSdkVersion", "sdkVersion"));
                hints.javaMajor = fromVersion;
            }
            if (hints.javaMajor == null) {
                Integer moduleSdkMajor = readFirstIntegerFromList(ideMap.get("moduleSdkMajors"));
                if (moduleSdkMajor != null) {
                    hints.javaMajor = moduleSdkMajor;
                }
            }
        }
        if ((hints.javaHome == null || hints.javaHome.isBlank())) {
            Object javaHome = ctx.getExtra().get("javaHome");
            if (javaHome instanceof String && !((String) javaHome).isBlank()) {
                hints.javaHome = normalizePotentialJavaHome(((String) javaHome).trim());
            }
        }
        return hints;
    }

    private String normalizePotentialJavaHome(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        if ("project sdk".equalsIgnoreCase(value)
                || "project jdk".equalsIgnoreCase(value)
                || "use project jdk".equalsIgnoreCase(value)
                || "#JAVA_INTERNAL".equalsIgnoreCase(value)) {
            return "";
        }
        if (value.contains(",")) {
            String first = value.split(",")[0].trim();
            if (!first.isBlank()) {
                value = first;
            }
        }
        try {
            Path path = Paths.get(value);
            if (path.isAbsolute()) {
                return path.normalize().toString();
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private String readFirstStringFromList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    private String readString(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = map.get(key);
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private Integer readInteger(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (text.isBlank()) {
                    continue;
                }
                try {
                    return Integer.parseInt(text);
                } catch (Exception ignored) {
                    Matcher matcher = Pattern.compile("(\\d{1,2})").matcher(text);
                    if (matcher.find()) {
                        try {
                            return Integer.parseInt(matcher.group(1));
                        } catch (Exception ignored2) {
                            // continue fallback
                        }
                    }
                    Integer fromVersion = parseJavaMajor(text);
                    if (fromVersion != null) {
                        return fromVersion;
                    }
                }
            }
        }
        return null;
    }

    private Integer readFirstIntegerFromList(Object listObject) {
        if (!(listObject instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Number) {
                return ((Number) item).intValue();
            }
            if (item instanceof String) {
                String text = ((String) item).trim();
                if (text.isBlank()) {
                    continue;
                }
                try {
                    return Integer.parseInt(text);
                } catch (Exception ignored) {
                    Integer parsed = parseJavaMajor(text);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }

    private boolean looksLikeJavaMismatchFailure(String output) {
        String normalized = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return normalized.contains("unsupported class file major version")
                || normalized.contains("class file has wrong version")
                || normalized.contains("requires java")
                || normalized.contains("source release")
                || normalized.contains("target release")
                || normalized.contains("invalid source release")
                || normalized.contains("invalid target release")
                || normalized.contains("not supported by this gradle version")
                || normalized.contains("could not target platform");
    }

    private String maybeApplyIdeJavaHomeOverride(
            String originalCommand,
            String preparedCommand,
            String shell,
            IdeHints ideHints
    ) {
        if (preparedCommand == null || preparedCommand.isBlank()) {
            return preparedCommand;
        }
        if (ideHints == null || ideHints.javaHome == null || ideHints.javaHome.isBlank()) {
            return preparedCommand;
        }
        boolean buildLike = isBuildLikeCommand(originalCommand);
        if (!buildLike && !isJavaToolchainCommand(originalCommand, preparedCommand)) {
            return preparedCommand;
        }
        if (!buildLike && commandAlreadySetsJavaHome(preparedCommand)) {
            return preparedCommand;
        }
        String override = buildJavaHomeOverrideCommand(preparedCommand, ideHints.javaHome, shell);
        return (override == null || override.isBlank()) ? preparedCommand : override;
    }

    private boolean isJavaToolchainCommand(String originalCommand, String preparedCommand) {
        String normalized = firstNonBlank(originalCommand, preparedCommand).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        String family = extractCommandFamily(normalized);
        if ("java".equals(family)
                || "javac".equals(family)
                || "jar".equals(family)
                || "jshell".equals(family)
                || "jcmd".equals(family)
                || "jdeps".equals(family)
                || "jlink".equals(family)
                || "jpackage".equals(family)
                || "gradle".equals(family)
                || "gradlew".equals(family)
                || "mvn".equals(family)
                || "mvnw".equals(family)) {
            return true;
        }
        return normalized.contains(" gradle ")
                || normalized.startsWith("gradle ")
                || normalized.contains(" gradlew")
                || normalized.startsWith("./gradlew")
                || normalized.startsWith(".\\gradlew")
                || normalized.contains(" maven ")
                || normalized.startsWith("mvn ")
                || normalized.startsWith("./mvnw")
                || normalized.startsWith(".\\mvnw")
                || normalized.contains(" java ")
                || normalized.startsWith("java ")
                || normalized.contains(" javac ")
                || normalized.startsWith("javac ");
    }

    private boolean commandAlreadySetsJavaHome(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return normalized.contains("java_home=")
                || normalized.contains("$env:java_home")
                || normalized.contains("%java_home%")
                || normalized.contains("set \"java_home=");
    }

    private boolean shouldRequireIdeJavaForBuild(String command, Context ctx) {
        if (!REQUIRE_IDE_JAVA_FOR_BUILD_WHEN_IDE_CONTEXT) {
            return false;
        }
        if (!isBuildLikeCommand(command)) {
            return false;
        }
        if (ctx == null || ctx.getExtra() == null) {
            return false;
        }
        Object ideContext = ctx.getExtra().get("ideContext");
        return ideContext instanceof Map<?, ?> && !((Map<?, ?>) ideContext).isEmpty();
    }

    private String buildJavaHomeOverrideCommand(String command, String javaHome, String shell) {
        if (command == null || command.isBlank() || javaHome == null || javaHome.isBlank()) {
            return command;
        }
        String normalizedCommand = command.trim();
        String normalizedHome = normalizeJavaHomeForShell(javaHome.trim(), shell);
        String lowerShell = shell == null ? "" : shell.toLowerCase(Locale.ROOT);

        if (isWindows() && lowerShell.endsWith("cmd.exe")) {
            return "set \"JAVA_HOME=" + escapeCmdValue(normalizedHome) + "\" && "
                    + "set \"PATH=%JAVA_HOME%\\bin;%PATH%\" && "
                    + normalizedCommand;
        }
        if (isPowerShell(lowerShell)) {
            return "$env:JAVA_HOME='" + escapePowerShellSingleQuoted(normalizedHome) + "'; "
                    + "$env:PATH=\"$env:JAVA_HOME\\bin;$env:PATH\"; "
                    + normalizedCommand;
        }
        return "export JAVA_HOME='" + escapeSingleQuoted(normalizedHome) + "'; "
                + "export PATH=\"$JAVA_HOME/bin:$PATH\"; "
                + normalizedCommand;
    }

    private String normalizeJavaHomeForShell(String javaHome, String shell) {
        if (javaHome == null || javaHome.isBlank()) {
            return "";
        }
        if (!isWindowsBashShell(shell)) {
            return javaHome;
        }
        String normalized = javaHome.trim().replace('\\', '/');
        Matcher driveMatcher = Pattern.compile("^([a-zA-Z]):/(.*)$").matcher(normalized);
        if (driveMatcher.find()) {
            String drive = driveMatcher.group(1).toLowerCase(Locale.ROOT);
            String rest = driveMatcher.group(2);
            return "/" + drive + "/" + rest;
        }
        return normalized;
    }

    private boolean isPowerShell(String lowerShell) {
        if (lowerShell == null || lowerShell.isBlank()) {
            return false;
        }
        return lowerShell.endsWith("powershell.exe")
                || lowerShell.endsWith("pwsh.exe")
                || lowerShell.contains("\\powershell")
                || lowerShell.endsWith("powershell")
                || lowerShell.endsWith("pwsh");
    }

    private String escapeCmdValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "\\\"");
    }

    private String escapePowerShellSingleQuoted(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private String escapeSingleQuoted(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "'\"'\"'");
    }

    private boolean isWindowsBashShell(String shell) {
        if (!isWindows() || shell == null || shell.isBlank()) {
            return false;
        }
        String normalized = shell.toLowerCase(Locale.ROOT);
        return normalized.endsWith("bash") || normalized.endsWith("bash.exe");
    }

    private String resolveExecutionShell(String preferredShell, String command) {
        String shell = resolveShell(preferredShell);
        if (isWindows() && isWindowsBashShell(shell) && looksWindowsBatchCommand(command)) {
            return "cmd.exe";
        }
        if (isWindows() && !isWindowsBashShell(shell) && looksBashSpecific(command)) {
            String bash = findWindowsBash();
            if (!bash.isBlank()) {
                return bash;
            }
        }
        return shell;
    }

    private boolean looksBashSpecific(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return normalized.contains("mkdir -p")
                || normalized.contains("rm -")
                || normalized.contains("chmod +")
                || normalized.contains("export ")
                || normalized.contains("cat <<")
                || normalized.contains("<<'eof'")
                || normalized.contains("<<\"eof\"")
                || normalized.contains("<<eof")
                || normalized.contains("$(");
    }

    private boolean looksWindowsBatchCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".bat")
                || normalized.endsWith(".cmd")
                || normalized.startsWith("gradlew.bat")
                || normalized.startsWith(".\\gradlew.bat")
                || normalized.startsWith("mvnw.cmd")
                || normalized.startsWith(".\\mvnw.cmd")
                || normalized.startsWith("where ")
                || normalized.startsWith("dir ");
    }

    private String findWindowsBash() {
        String[] candidates = new String[]{
                "C:\\Program Files\\Git\\bin\\bash.exe",
                "C:\\Program Files (x86)\\Git\\bin\\bash.exe",
                System.getProperty("user.home", "") + "\\AppData\\Local\\Programs\\Git\\bin\\bash.exe"
        };
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                Path path = Path.of(candidate);
                if (Files.exists(path)) {
                    return path.toString();
                }
            }
        }
        String fromPath = findExecutableOnPath("bash");
        return fromPath == null ? "" : fromPath;
    }

    private String findExecutableOnPath(String executable) {
        if (executable == null || executable.isBlank()) {
            return null;
        }
        String[] command = isWindows()
                ? new String[]{"where", executable}
                : new String[]{"which", executable};
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            boolean finished = process.waitFor(2, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                if (line != null && !line.isBlank()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {
            // best effort
        }
        return null;
    }

    private boolean isIdeJavaCompatibleWithProject(IdeHints ideHints, BuildProbe probe) {
        if (ideHints == null || ideHints.javaMajor == null) {
            return true;
        }
        if (probe == null || probe.requiredJavaMajor == null) {
            return true;
        }
        return ideHints.javaMajor.equals(probe.requiredJavaMajor);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String safeEnv(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String value = System.getenv(key);
        return value == null ? "" : value;
    }

    private String resolveShell(String shell) {
        if (shell != null && !shell.isBlank()) {
            return shell;
        }
        return isWindows() ? "cmd.exe" : "/bin/sh";
    }

    private String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "\n\n...";
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static class CommandExecution {
        final String command;
        final int exitCode;
        final String output;
        final boolean timedOut;
        final String shell;
        final long durationMs;

        CommandExecution(String command, int exitCode, String output, boolean timedOut, String shell, long durationMs) {
            this.command = command;
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
            this.timedOut = timedOut;
            this.shell = shell == null ? "" : shell;
            this.durationMs = Math.max(0L, durationMs);
        }
    }

    private static class BuildProbe {
        boolean windows;
        String shell;
        boolean shellBashLikeOnWindows;
        String javaHome;
        String javaVersionOutput;
        Integer javaMajor;
        boolean gradleAvailable;
        String gradleVersionOutput;
        Integer gradleMajor;
        boolean mavenAvailable;
        String mavenVersionOutput;
        boolean gradlewExists;
        boolean gradlewBatExists;
        boolean pomExists;
        boolean mavenwExists;
        boolean mavenwCmdExists;
        boolean wrapperJarExists;
        boolean wrapperPropertiesExists;
        String wrapperDistributionVersion;
        Integer wrapperGradleMajor;
        Integer requiredJavaMajor;
    }

    private static class IdeHints {
        String javaHome;
        Integer javaMajor;
        String languageLevel;
    }

    private static class BuildSelfHealReport {
        String originalCommand;
        String preparedCommand;
        BuildSystemDetection detection;
        BuildProbe probe;
        IdeHints ideHints;
        List<String> diagnoses = new ArrayList<>();
        String fixStrategy;
        String fixCommand;
        CommandExecution fixExecution;
        String fixError;
        boolean wrapperJarExistsAfterFix;
        String retryCommand;
        CommandExecution retryExecution;

        boolean hasSuccessfulRetry() {
            return retryExecution != null && !retryExecution.timedOut && retryExecution.exitCode == 0;
        }

        Map<String, Object> toMetadata() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("diagnoses", new ArrayList<>(diagnoses));
            metadata.put("fix_strategy", firstNonBlankStatic(fixStrategy));
            metadata.put("fix_command", firstNonBlankStatic(fixCommand));
            metadata.put("fix_error", firstNonBlankStatic(fixError));
            metadata.put("retry_command", firstNonBlankStatic(retryCommand));
            if (detection != null) {
                metadata.put("build_system", detection.getPrimary().name().toLowerCase(Locale.ROOT));
                List<String> candidates = detection.getCandidates().stream()
                        .map(type -> type.name().toLowerCase(Locale.ROOT))
                        .toList();
                metadata.put("build_candidates", candidates);
            }
            if (fixExecution != null) {
                metadata.put("fix_exit", fixExecution.exitCode);
            }
            if (retryExecution != null) {
                metadata.put("retry_exit", retryExecution.exitCode);
            }
            metadata.put("wrapper_jar_after_fix", wrapperJarExistsAfterFix);
            if (ideHints != null) {
                Map<String, Object> ideMeta = new HashMap<>();
                ideMeta.put("java_home", firstNonBlankStatic(ideHints.javaHome));
                ideMeta.put("java_major", ideHints.javaMajor);
                ideMeta.put("language_level", firstNonBlankStatic(ideHints.languageLevel));
                metadata.put("ide_hints", ideMeta);
            }
            if (probe != null) {
                Map<String, Object> probeMeta = new HashMap<>();
                probeMeta.put("windows", probe.windows);
                probeMeta.put("shell", firstNonBlankStatic(probe.shell));
                probeMeta.put("java_home", firstNonBlankStatic(probe.javaHome));
                probeMeta.put("java_major", probe.javaMajor);
                probeMeta.put("gradle_available", probe.gradleAvailable);
                probeMeta.put("gradle_major", probe.gradleMajor);
                probeMeta.put("maven_available", probe.mavenAvailable);
                probeMeta.put("required_java_major", probe.requiredJavaMajor);
                probeMeta.put("wrapper_gradle_major", probe.wrapperGradleMajor);
                probeMeta.put("wrapper_distribution_version", firstNonBlankStatic(probe.wrapperDistributionVersion));
                probeMeta.put("wrapper_jar_exists", probe.wrapperJarExists);
                probeMeta.put("pom_exists", probe.pomExists);
                probeMeta.put("mavenw_exists", probe.mavenwExists);
                probeMeta.put("mavenw_cmd_exists", probe.mavenwCmdExists);
                metadata.put("probe", probeMeta);
            }
            return metadata;
        }

        private static String firstNonBlankStatic(String value) {
            return value == null ? "" : value;
        }
    }

    private RiskAssessment assessRisk(String command) {
        if (command == null || command.isBlank()) {
            return new RiskAssessment(false, List.of(), false, "restricted");
        }
        String normalized = command.toLowerCase(Locale.ROOT)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        List<String> reasons = new ArrayList<>();

        if (containsAny(normalized, "rm -rf", "rm -fr", "sudo rm -rf", "sudo rm -fr")) {
            addReason(reasons, "recursive force delete detected");
        }
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:sudo\\s+)?rm\\s+.+")) {
            addReason(reasons, "file delete command detected");
        }
        if (normalized.matches(".*\\brm\\b.*\\s-[a-z]*r[a-z]*\\b.*")) {
            addReason(reasons, "recursive delete command detected");
        }
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:cmd\\s+/c\\s+)?(?:del|erase)\\s+.+")) {
            addReason(reasons, "windows file delete command detected");
        }
        if (normalized.matches(".*\\b(del|erase)\\b.*(/s|/q|/f).*")) {
            addReason(reasons, "windows recursive/force delete detected");
        }
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:powershell(?:\\.exe)?\\s+-command\\s+)?(?:remove-item|ri)\\s+.+")) {
            addReason(reasons, "powershell file delete command detected");
        }
        if (normalized.matches(".*\\b(rmdir|rd)\\b.*(/s|/q|-r|-rf).*")) {
            addReason(reasons, "directory tree delete detected");
        }
        if (normalized.matches(".*\\b(remove-item|ri)\\b.*(-recurse|/s).*")) {
            addReason(reasons, "powershell recursive delete detected");
        }
        if (normalized.matches(".*\\b(remove-item|ri)\\b.*(-force|/f).*")) {
            addReason(reasons, "powershell force delete detected");
        }
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:sudo\\s+)?mv\\s+.+")) {
            addReason(reasons, "move/rename command detected");
        }
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:cmd\\s+/c\\s+)?(?:move|ren|rename)\\s+.+")) {
            addReason(reasons, "windows move/rename command detected");
        }
        if (normalized.matches(".*\\b(rename-item)\\b.*")) {
            addReason(reasons, "powershell move/rename command detected");
        }
        if (normalized.matches(".*\\bgit\\s+mv\\b.*")) {
            addReason(reasons, "git move/rename detected");
        }
        if (normalized.matches(".*\\bfind\\b.*\\b-delete\\b.*")) {
            addReason(reasons, "find -delete sweep detected");
        }
        if (normalized.matches(".*\\bfind\\b.*\\b-exec\\b.*\\b(rm|unlink|shred)\\b.*")) {
            addReason(reasons, "find -exec destructive command detected");
        }
        if (containsAny(
                normalized,
                "git reset --hard",
                "git clean -fd",
                "git clean -df",
                "git clean -fx",
                "git clean -xfd",
                "git clean -fdx"
        )) {
            addReason(reasons, "destructive git cleanup/reset detected");
        }
        if (normalized.matches(".*\\bgit\\s+(checkout|restore)\\b.*\\s--\\s.*")) {
            addReason(reasons, "git checkout/restore destructive target detected");
        }
        if (normalized.matches(".*\\b(format|mkfs|fdisk|diskpart|parted|wipefs)\\b.*")) {
            addReason(reasons, "disk formatting/partition command detected");
        }
        if (normalized.matches(".*\\bdd\\b.*\\bof=(/dev/|\\\\\\\\.\\\\physicaldrive).*")) {
            addReason(reasons, "raw disk write command detected");
        }
        if (normalized.matches(".*\\b(shutdown|reboot|poweroff|halt)\\b.*")) {
            addReason(reasons, "system shutdown/reboot command detected");
        }
        if (normalized.matches(".*\\b(chmod|chown|icacls|takeown)\\b.*\\s-[a-z]*r[a-z]*\\b.*")) {
            addReason(reasons, "recursive permission/ownership change detected");
        }
        if (normalized.matches(".*\\b(curl|wget|invoke-webrequest|iwr)\\b.*\\|\\s*(sh|bash|zsh|cmd|powershell|pwsh)\\b.*")) {
            addReason(reasons, "remote script pipe execution detected");
        }
        if (normalized.matches(".*\\b(winget|choco|chocolatey|scoop|apt|apt-get|yum|dnf|zypper|pacman|brew|port|pip|pip3|npm|pnpm|yarn|gem|cargo|go)\\b.*\\b(install|add|upgrade|update)\\b.*")) {
            addReason(reasons, "software/package installation detected (explicit user consent required)");
        }
        if (normalized.matches(".*\\b(sdk|jabba|asdf)\\b.*\\binstall\\b.*")) {
            addReason(reasons, "runtime/toolchain installation detected (explicit user consent required)");
        }
        if (normalized.matches(".*\\bgit\\s+clone\\b.*")) {
            addReason(reasons, "repository download/clone detected (explicit user consent required)");
        }
        if (normalized.matches(".*\\b(curl|wget|invoke-webrequest|iwr|start-bitstransfer|bitsadmin)\\b.*(https?://|ftp://).*")) {
            addReason(reasons, "network download command detected (explicit user consent required)");
        }
        if (normalized.contains(":(){ :|:& };:")) {
            addReason(reasons, "fork-bomb pattern detected");
        }

        boolean strictApproval = reasons.stream().anyMatch(this::isStrictRiskReason);
        String riskCategory = strictApproval ? "destructive" : "restricted";
        return new RiskAssessment(!reasons.isEmpty(), reasons, strictApproval, riskCategory);
    }

    private boolean containsAny(String source, String... fragments) {
        if (source == null || fragments == null) {
            return false;
        }
        for (String fragment : fragments) {
            if (fragment != null && !fragment.isBlank() && source.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private void addReason(List<String> reasons, String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }

    private boolean isStrictRiskReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("delete")
                || normalized.contains("destructive")
                || normalized.contains("disk")
                || normalized.contains("shutdown")
                || normalized.contains("fork-bomb")
                || normalized.contains("move/rename");
    }

    private String extractCommandFamily(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        Matcher matcher = Pattern.compile("\"([^\"]+)\"|'([^']+)'|(\\S+)").matcher(command);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = firstNonBlank(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3)
            );
            if (token != null && !token.isBlank()) {
                tokens.add(token.trim());
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            String token = normalizeFamilyToken(tokens.get(i));
            if (token.isBlank()) {
                continue;
            }
            if ("sudo".equals(token) || "env".equals(token)) {
                continue;
            }
            if (isShellWrapperToken(token)) {
                continue;
            }
            if (token.startsWith("-")) {
                continue;
            }
            return token;
        }
        return "";
    }

    private boolean isShellWrapperToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return "bash".equals(token)
                || "sh".equals(token)
                || "zsh".equals(token)
                || "fish".equals(token)
                || "cmd".equals(token)
                || "cmd.exe".equals(token)
                || "powershell".equals(token)
                || "powershell.exe".equals(token)
                || "pwsh".equals(token)
                || "pwsh.exe".equals(token)
                || "-c".equals(token)
                || "-lc".equals(token)
                || "/c".equals(token)
                || "-command".equals(token)
                || "-noprofile".equals(token)
                || "-noninteractive".equals(token);
    }

    private String normalizeFamilyToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return "";
        }
        String token = rawToken.trim().replace("\"", "").replace("'", "");
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < token.length()) {
            token = token.substring(slash + 1);
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            lower = lower.substring(0, lower.length() - 4);
        }
        lower = lower.replaceAll("[^a-z0-9._-]", "");
        return lower;
    }

    private static class RiskAssessment {
        final boolean requiresApproval;
        final List<String> reasons;
        final boolean strictApproval;
        final String riskCategory;

        RiskAssessment(boolean requiresApproval, List<String> reasons, boolean strictApproval, String riskCategory) {
            this.requiresApproval = requiresApproval;
            this.reasons = reasons;
            this.strictApproval = strictApproval;
            this.riskCategory = riskCategory == null ? "restricted" : riskCategory;
        }
    }
}
