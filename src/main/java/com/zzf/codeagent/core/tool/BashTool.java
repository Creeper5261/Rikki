package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.shell.ShellService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BashTool 瀹炵幇 (瀵归綈 opencode/src/tool/bash.ts)
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
    private final BashCommandNormalizer commandNormalizer = new BashCommandNormalizer();
    private final BashRiskAssessor riskAssessor = new BashRiskAssessor();
    private final BashMetadataBuilder metadataBuilder = new BashMetadataBuilder();
    private final BashIdeHintsResolver ideHintsResolver = new BashIdeHintsResolver();
    private final BashJavaHomeOverride javaHomeOverride;
    private final BashCommandExecutor commandExecutor;
    private final BashBuildSelfHealService selfHealService;

    public BashTool(ShellService shellService, ProjectContext projectContext, ResourceLoader resourceLoader) {
        this.shellService = shellService;
        this.projectContext = projectContext;
        this.resourceLoader = resourceLoader;
        this.javaHomeOverride = new BashJavaHomeOverride(
                commandNormalizer,
                riskAssessor,
                REQUIRE_IDE_JAVA_FOR_BUILD_WHEN_IDE_CONTEXT
        );
        this.commandExecutor = new BashCommandExecutor(shellService, MAX_METADATA_LENGTH);
        this.selfHealService = new BashBuildSelfHealService(
                commandExecutor,
                commandNormalizer,
                SELF_HEAL_PROBE_TIMEOUT_MS,
                SELF_HEAL_FIX_TIMEOUT_MS,
                DEFAULT_WRAPPER_GRADLE_VERSION,
                javaHomeOverride::buildJavaHomeOverrideCommand
        );
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
    public void cancel(String sessionID, String callID) {
        commandExecutor.cancel(sessionID, callID);
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
            Path workdir;
            try {
                workdir = ToolPathResolver.resolvePath(projectContext, ctx, workdirStr);
            } catch (IllegalArgumentException ex) {
                throw new RuntimeException("Workdir must stay inside workspace root: " + ex.getMessage());
            }
            String shell = commandNormalizer.resolveExecutionShell(shellService.acceptable(), command);

            BashRiskAssessor.Assessment risk = riskAssessor.assess(command, workspaceRoot);
            String sessionId = ctx != null ? ctx.getSessionID() : null;
            String commandFamily = riskAssessor.extractCommandFamily(command);
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

                Map<String, Object> pendingMeta = metadataBuilder.buildPendingApprovalMetadata(
                        description,
                        command,
                        risk,
                        pending,
                        shell
                );

                return Result.builder()
                        .title(description)
                        .output("High-risk command requires user approval before execution. Command was not executed.")
                        .metadata(pendingMeta)
                        .build();
            }

            
            
            
            Map<String, Object> permissionRequest = metadataBuilder.buildPermissionRequest(command, workspaceRoot);

            try {
                ctx.ask(permissionRequest).get(); 
            } catch (Exception e) {
                throw new RuntimeException("Permission denied or error during permission check", e);
            }

            try {
                String preparedCommand = commandNormalizer.prepareCommandForShell(command, shell);
                BashBuildSelfHealModel.IdeHints ideHints = ideHintsResolver.resolveIdeHints(ctx);
                if (javaHomeOverride.shouldRequireIdeJavaForBuild(command, ctx)
                        && (ideHints == null || ideHints.javaHome == null || ideHints.javaHome.isBlank())) {
                    throw new RuntimeException("Cannot guarantee IDE build environment: IDE Java home is unavailable in IDE context. Configure IDEA Project SDK/JDK path and retry.");
                }
                String effectiveCommand = javaHomeOverride.maybeApplyIdeJavaHomeOverride(command, preparedCommand, shell, ideHints);
                BashCommandExecutor.ExecutionResult primary = commandExecutor.execute(
                        effectiveCommand,
                        shell,
                        workdir,
                        timeout,
                        description,
                        ctx,
                        true
                );

                BashBuildSelfHealModel.Report healReport = null;
                BashCommandExecutor.ExecutionResult finalExecution = primary;
                String finalCommand = effectiveCommand;

                if (isBuildLikeCommand(command) && BUILD_SELF_HEAL_ENABLED) {
                    healReport = selfHealService.processBuildSelfHeal(
                            command,
                            effectiveCommand,
                            shell,
                            workdir,
                            timeout,
                            primary,
                            ideHints
                    );
                    if (healReport != null && healReport.hasSuccessfulRetry()) {
                        finalExecution = healReport.retryExecution;
                        finalCommand = healReport.retryCommand;
                    }
                }

                String finalOutput = finalExecution.output;
                if (healReport != null) {
                    finalOutput = selfHealService.appendSelfHealContext(finalOutput, healReport);
                }
                Map<String, Object> resultMetadata = metadataBuilder.buildResultMetadata(
                        finalOutput,
                        finalExecution.exitCode,
                        description,
                        finalCommand,
                        command,
                        shell,
                        !effectiveCommand.equals(preparedCommand),
                        healReport == null ? null : healReport.toMetadata(),
                        MAX_METADATA_LENGTH
                );

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

}
