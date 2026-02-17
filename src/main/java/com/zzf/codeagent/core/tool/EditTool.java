package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.snapshot.SnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * EditTool 实现 (对齐 opencode/src/tool/edit.ts)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EditTool implements Tool {

    private final ProjectContext projectContext;
    private final ObjectMapper objectMapper;
    private final SnapshotService snapshotService;
    private final ResourceLoader resourceLoader;

    private List<String> findMatches(String content, String find) {
        List<String> results = new ArrayList<>();
        String[] originalLines = content.split("\n", -1);
        String[] searchLines = find.split("\n", -1);

        
        List<String> searchLinesList = new ArrayList<>(List.of(searchLines));
        if (!searchLinesList.isEmpty() && searchLinesList.get(searchLinesList.size() - 1).isEmpty()) {
            searchLinesList.remove(searchLinesList.size() - 1);
        }

        if (searchLinesList.isEmpty()) return results;

        for (int i = 0; i <= originalLines.length - searchLinesList.size(); i++) {
            boolean matches = true;
            for (int j = 0; j < searchLinesList.size(); j++) {
                String originalTrimmed = originalLines[i + j].trim();
                String searchTrimmed = searchLinesList.get(j).trim();
                if (!originalTrimmed.equals(searchTrimmed)) {
                    matches = false;
                    break;
                }
            }

            if (matches) {
                
                int matchStartIndex = 0;
                for (int k = 0; k < i; k++) {
                    matchStartIndex += originalLines[k].length() + 1;
                }

                int matchEndIndex = matchStartIndex;
                for (int k = 0; k < searchLinesList.size(); k++) {
                    matchEndIndex += originalLines[i + k].length();
                    if (k < searchLinesList.size() - 1) {
                        matchEndIndex += 1;
                    }
                }
                results.add(content.substring(matchStartIndex, matchEndIndex));
            }
        }
        return results;
    }

    @Override
    public String getId() {
        return "edit";
    }

    @Override
    public String getDescription() {
        return loadDescription();
    }

    private String loadDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/edit.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load edit tool description", e);
        }
        return "Performs exact string replacements in files."; 
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        
        properties.putObject("filePath").put("type", "string").put("description", "The absolute path to the file to modify");
        properties.putObject("oldString").put("type", "string").put("description", "The text to replace. Leave empty if creating a new file.");
        properties.putObject("newString").put("type", "string").put("description", "The text to replace it with (must be different from oldString)");
        properties.putObject("replaceAll").put("type", "boolean").put("description", "Replace all occurrences of oldString (default false)");

        schema.putArray("required").add("filePath").add("newString");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String filePathStr = args.get("filePath").asText();
                String oldString = args.has("oldString") ? args.get("oldString").asText() : "";
                String newString = args.get("newString").asText();
                boolean replaceAll = args.has("replaceAll") && args.get("replaceAll").asBoolean();

                Path filePath = ToolPathResolver.resolvePath(projectContext, ctx, filePathStr);

                String contentOld = "";
                String contentNew = "";
                String changeType = "EDIT";

                if (!Files.exists(filePath)) {
                    
                    if (!oldString.isEmpty()) {
                        throw new RuntimeException("File " + filePath + " not found. To create a new file, leave oldString empty.");
                    }
                    changeType = "CREATE";
                    contentNew = newString;
                } else {
                    
                    if (oldString.equals(newString)) {
                        throw new RuntimeException("oldString and newString must be different");
                    }
                    
                    contentOld = Files.readString(filePath);
                    
                    
                    List<String> matches = findMatches(contentOld, oldString);
                    
                    if (matches.isEmpty()) {
                        throw new RuntimeException("oldString not found in content (exact or trimmed)");
                    }
                    
                    if (!replaceAll && matches.size() > 1) {
                        throw new RuntimeException("oldString found multiple times and requires more code context to uniquely identify the intended match");
                    }

                    if (replaceAll) {
                        contentNew = contentOld;
                        for (String match : matches) {
                            contentNew = contentNew.replace(match, newString);
                        }
                    } else {
                        contentNew = contentOld.replace(matches.get(0), newString);
                    }
                }

                if (filePath.getParent() != null) {
                    Files.createDirectories(filePath.getParent());
                }
                Files.writeString(filePath, contentNew, StandardCharsets.UTF_8);

                
                String workspaceRoot = ToolPathResolver.resolveWorkspaceRoot(projectContext, ctx);
                String relativePath = ToolPathResolver.safeRelativePath(workspaceRoot, filePath);
                
                PendingChangesManager.PendingChange change = new PendingChangesManager.PendingChange(
                        java.util.UUID.randomUUID().toString(),
                        relativePath,
                        changeType,
                        contentOld,
                        contentNew,
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
                metadata.put("workspace_applied", true);

                
                try {
                    metadata.put("diagnostics", getDiagnostics(filePath.toString()));
                } catch (Exception e) {
                    log.warn("Failed to fetch diagnostics for {}: {}", filePath, e.getMessage());
                }

                
                int additions = 0;
                int deletions = 0;
                

                SnapshotService.FileDiff filediff = SnapshotService.FileDiff.builder()
                        .file(filePath.toString())
                        .before(contentOld)
                        .after(contentNew)
                        .additions(additions)
                        .deletions(deletions)
                        .build();
                metadata.put("filediff", filediff);
                snapshotService.record(filediff);

                return Result.builder()
                        .title(filePath.getFileName().toString())
                        .metadata(metadata)
                        .output("File updated in workspace: " + relativePath)
                        .build();

            } catch (Exception e) {
                log.error("Failed to execute edit tool", e);
                throw new RuntimeException(e.getMessage());
            }
        });
    }

    /**
     * Fetches diagnostics for a file via Shell (simulating LSP diagnostics)
     * Aligned with opencode's diagnostic retrieval logic
     */
    private Object getDiagnostics(String filePath) {
        
        
        
        
        
        
        
        
        return new java.util.ArrayList<>(); 
    }
}
