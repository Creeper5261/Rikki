package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * DeleteTool Implementation
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteTool implements Tool {

    private final ProjectContext projectContext;
    private final ObjectMapper objectMapper;

    @Override
    public String getId() {
        return "delete_file";
    }

    @Override
    public String getDescription() {
        return "Delete a file from the workspace.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        
        properties.putObject("filePath").put("type", "string").put("description", "The absolute path to the file to delete");
        
        schema.putArray("required").add("filePath");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String filePathStr = args.get("filePath").asText();
                Path filePath = ToolPathResolver.resolvePath(projectContext, ctx, filePathStr);

                if (!Files.exists(filePath)) {
                    throw new RuntimeException("File " + filePath + " not found");
                }

                String contentOld = Files.readString(filePath);
                
                // Use PendingChangesManager
                String workspaceRoot = ToolPathResolver.resolveWorkspaceRoot(projectContext, ctx);
                String relativePath = ToolPathResolver.safeRelativePath(workspaceRoot, filePath);
                
                PendingChangesManager.PendingChange change = new PendingChangesManager.PendingChange(
                        java.util.UUID.randomUUID().toString(),
                        relativePath,
                        "DELETE",
                        contentOld,
                        null, // newContent is null for DELETE
                        null, 
                        System.currentTimeMillis(),
                        workspaceRoot,
                        ctx.getSessionID()
                );
                
                PendingChangesManager.getInstance().addChange(change);

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("filepath", filePath.toString());
                metadata.put("pending_change_id", change.id);
                metadata.put("pending_change", change);

                return Result.builder()
                        .output("Delete staged and waiting for user approval: " + relativePath)
                        .metadata(metadata)
                        .title("Delete " + relativePath)
                        .build();

            } catch (Exception e) {
                log.error("Delete failed", e);
                throw new RuntimeException("Failed to delete file: " + e.getMessage(), e);
            }
        });
    }
}
