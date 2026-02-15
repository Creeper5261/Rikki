package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.shell.ShellService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StreamUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * BashTool 实现 (对齐 opencode/src/tool/bash.ts)
 */
@Component
@Slf4j
public class BashTool implements Tool {
    private static final long DEFAULT_TIMEOUT_MS = 60000;
    private static final int MAX_METADATA_LENGTH = 1000;
    private static final int MAX_OUTPUT_LENGTH = 8000;
    private static final boolean FAIL_ON_NON_ZERO_EXIT =
            Boolean.parseBoolean(System.getProperty("codeagent.bash.failOnNonZeroExit", "true"));
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
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

            RiskAssessment risk = assessRisk(command);
            if (risk.requiresApproval) {
                String pendingId = UUID.randomUUID().toString();
                PendingCommandsManager.PendingCommand pending = new PendingCommandsManager.PendingCommand(
                        pendingId,
                        command,
                        description,
                        workdir.toString(),
                        workspaceRoot,
                        ctx != null ? ctx.getSessionID() : null,
                        timeout,
                        "high",
                        risk.reasons,
                        System.currentTimeMillis()
                );
                PendingCommandsManager.getInstance().add(pending);

                Map<String, Object> pendingMeta = new HashMap<>();
                pendingMeta.put("description", description);
                pendingMeta.put("command", command);
                pendingMeta.put("risk_level", "high");
                pendingMeta.put("risk_reasons", risk.reasons);
                pendingMeta.put("pending_command_id", pending.id);
                pendingMeta.put("pending_command", pending);

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

            // 2. Execute Command
            StringBuilder output = new StringBuilder();
            try {
                String shell = shellService.acceptable();
                ProcessBuilder pb;
                if (System.getProperty("os.name").toLowerCase().contains("win") && shell.endsWith("cmd.exe")) {
                    pb = new ProcessBuilder("cmd.exe", "/c", command);
                } else {
                    pb = new ProcessBuilder(shell, "-c", command);
                }
                
                pb.directory(workdir.toFile());
                pb.redirectErrorStream(true);
                
                Process process = pb.start();
                
                // Initial metadata update
                Map<String, Object> meta = new HashMap<>();
                meta.put("output", "");
                meta.put("description", description);
                ctx.metadata(description, meta);

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                        
                        // Periodic metadata update (simplified)
                        if (output.length() < MAX_METADATA_LENGTH) {
                            Map<String, Object> updateMeta = new HashMap<>();
                            updateMeta.put("output", output.toString());
                            updateMeta.put("description", description);
                            ctx.metadata(description, updateMeta);
                        }
                    }
                }

                boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
                if (!finished) {
                    shellService.killTree(process);
                    output.append("\n\n<bash_metadata>\nbash tool terminated command after exceeding timeout ")
                            .append(timeout)
                            .append(" ms\n</bash_metadata>");
                }

                int exitCode = finished ? process.exitValue() : -1;
                
                String finalOutput = output.toString();
                Map<String, Object> resultMetadata = new HashMap<>();
                resultMetadata.put("output", finalOutput.length() > MAX_METADATA_LENGTH ? finalOutput.substring(0, MAX_METADATA_LENGTH) + "\n\n..." : finalOutput);
                resultMetadata.put("exit", exitCode);
                resultMetadata.put("description", description);
                resultMetadata.put("command", command);
                resultMetadata.put("shell", shell);

                if (!finished) {
                    String timeoutMsg = "Command timed out after " + timeout + " ms: " + command;
                    String timeoutDetails = finalOutput.length() > MAX_OUTPUT_LENGTH
                            ? finalOutput.substring(0, MAX_OUTPUT_LENGTH) + "\n\n..."
                            : finalOutput;
                    throw new RuntimeException(timeoutMsg + (timeoutDetails.isBlank() ? "" : "\n" + timeoutDetails));
                }

                if (FAIL_ON_NON_ZERO_EXIT && exitCode != 0) {
                    String err = finalOutput == null ? "" : finalOutput.trim();
                    if (err.length() > MAX_OUTPUT_LENGTH) {
                        err = err.substring(0, MAX_OUTPUT_LENGTH) + "\n\n...";
                    }
                    String msg = "Command failed with exit code " + exitCode + ": " + command;
                    throw new RuntimeException(err.isEmpty() ? msg : msg + "\n" + err);
                }

                return Result.builder()
                        .title(description)
                        .output(finalOutput.length() > MAX_OUTPUT_LENGTH ? finalOutput.substring(0, MAX_OUTPUT_LENGTH) + "\n\n..." : finalOutput)
                        .metadata(resultMetadata)
                        .build();

            } catch (Exception e) {
                log.error("Failed to execute bash command", e);
                throw new RuntimeException(e.getMessage());
            }
        });
    }

    private RiskAssessment assessRisk(String command) {
        if (command == null || command.isBlank()) {
            return new RiskAssessment(false, List.of());
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
        if (normalized.matches(".*\\brm\\b.*\\s-[a-z]*r[a-z]*\\b.*")) {
            addReason(reasons, "recursive delete command detected");
        }
        if (normalized.matches(".*\\b(del|erase)\\b.*(/s|/q|/f).*")) {
            addReason(reasons, "windows recursive/force delete detected");
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
        if (normalized.contains(":(){ :|:& };:")) {
            addReason(reasons, "fork-bomb pattern detected");
        }

        return new RiskAssessment(!reasons.isEmpty(), reasons);
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

    private static class RiskAssessment {
        final boolean requiresApproval;
        final List<String> reasons;

        RiskAssessment(boolean requiresApproval, List<String> reasons) {
            this.requiresApproval = requiresApproval;
            this.reasons = reasons;
        }
    }
}
