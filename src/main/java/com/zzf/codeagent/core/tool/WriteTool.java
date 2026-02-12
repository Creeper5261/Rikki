package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.snapshot.SnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * WriteTool Implementation
 * Allows creating or overwriting files completely (unlike EditTool which patches).
 * This aligns with the 'write' tool expected by many agents.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriteTool implements Tool {

    private final ProjectContext projectContext;
    private final ObjectMapper objectMapper;
    private final SnapshotService snapshotService;

    @Override
    public String getId() {
        return "write";
    }

    @Override
    public String getDescription() {
        return "Writes content to a file. Overwrites the file if it exists, or creates it if it doesn't. Use this for creating new files or replacing entire file contents.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        
        properties.putObject("filePath").put("type", "string").put("description", "The absolute or relative path to the file");
        properties.putObject("content").put("type", "string").put("description", "The full content to write to the file");

        schema.putArray("required").add("filePath").add("content");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String filePathStr = args.get("filePath").asText();
                String content = args.get("content").asText();

                Path filePath = ToolPathResolver.resolvePath(projectContext, ctx, filePathStr);

                String workspaceRoot = ToolPathResolver.resolveWorkspaceRoot(projectContext, ctx);
                String relativePath = ToolPathResolver.safeRelativePath(workspaceRoot, filePath);
                
                // Determine change type and old content
                String changeType = "CREATE";
                String oldContent = "";
                
                if (Files.exists(filePath)) {
                    changeType = "EDIT"; // Technically overwrite, but mapped to EDIT type for UI
                    try {
                        oldContent = Files.readString(filePath);
                    } catch (Exception e) {
                        log.warn("Could not read old content for {}", filePath);
                    }
                }

                // Create Pending Change
                PendingChangesManager.PendingChange change = new PendingChangesManager.PendingChange(
                        UUID.randomUUID().toString(),
                        relativePath,
                        changeType,
                        oldContent,
                        content,
                        null,
                        System.currentTimeMillis(),
                        workspaceRoot,
                        ctx.getSessionID()
                );
                
                PendingChangesManager.getInstance().addChange(change);

                // Build Metadata
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("filepath", filePath.toString());
                metadata.put("pending_change_id", change.id);
                metadata.put("pending_change", change);

                // Record Snapshot (Simplified)
                SnapshotService.FileDiff filediff = SnapshotService.FileDiff.builder()
                        .file(filePath.toString())
                        .before(oldContent)
                        .after(content)
                        .additions(content.split("\n").length)
                        .deletions(oldContent.isEmpty() ? 0 : oldContent.split("\n").length)
                        .build();
                metadata.put("filediff", filediff);
                snapshotService.record(filediff);

                return Result.builder()
                        .title(filePath.getFileName().toString())
                        .metadata(metadata)
                        .output("File write staged. Please review and commit via Pending Changes.")
                        .build();

            } catch (Exception e) {
                log.error("Failed to execute write tool", e);
                throw new RuntimeException(e.getMessage());
            }
        });
    }
}
