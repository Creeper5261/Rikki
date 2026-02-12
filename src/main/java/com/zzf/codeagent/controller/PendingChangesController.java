package com.zzf.codeagent.controller;

import com.zzf.codeagent.core.tool.PendingChangesManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/agent/pending")
@RequiredArgsConstructor
public class PendingChangesController {

    @Data
    public static class PendingChangeRequest {
        private String traceId; // sessionId
        private String workspaceRoot;
        private String path;
        private boolean reject;
    }

    @PostMapping
    public Map<String, Object> resolvePendingChange(@RequestBody PendingChangeRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String path = request.getPath();
            String workspaceRoot = request.getWorkspaceRoot();
            String sessionId = request.getTraceId();
            boolean reject = request.isReject();

            Optional<PendingChangesManager.PendingChange> changeOpt = 
                PendingChangesManager.getInstance().getPendingChange(path, workspaceRoot, sessionId);

            if (changeOpt.isEmpty()) {
                // Try finding by path only if session/root doesn't match exactly (fallback)
                changeOpt = PendingChangesManager.getInstance().getPendingChange(path);
            }

            if (changeOpt.isEmpty()) {
                response.put("status", "error");
                response.put("error", "Pending change not found for path: " + path);
                return response;
            }

            PendingChangesManager.PendingChange change = changeOpt.get();

            if (reject) {
                PendingChangesManager.getInstance().removeChange(change.id);
                response.put("status", "rejected");
                return response;
            }

            // Apply Change
            applyChangeToDisk(change, workspaceRoot);
            
            PendingChangesManager.getInstance().removeChange(change.id);
            response.put("status", "applied");
            
        } catch (Exception e) {
            log.error("Failed to resolve pending change", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }
        return response;
    }

    private void applyChangeToDisk(PendingChangesManager.PendingChange change, String requestedWorkspaceRoot) throws IOException {
        String type = change.type;
        String workspaceRoot = (requestedWorkspaceRoot != null && !requestedWorkspaceRoot.isBlank())
                ? requestedWorkspaceRoot
                : change.workspaceRoot;
        String relativePath = change.path;
        
        Path targetPath = resolveTargetPath(workspaceRoot, relativePath);
        
        if ("DELETE".equalsIgnoreCase(type)) {
            Files.deleteIfExists(targetPath);
        } else {
            // EDIT or CREATE
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            
            String content = change.newContent;
            if (content != null) {
                Files.writeString(targetPath, content, StandardCharsets.UTF_8);
            } else if ("CREATE".equalsIgnoreCase(type)) {
                // Create empty file if content is null
                Files.createFile(targetPath);
            }
        }
    }

    private Path resolveTargetPath(String workspaceRoot, String path) {
        Path parsed = normalizePath(path);
        if (parsed.isAbsolute()) {
            return parsed.normalize();
        }
        String base = (workspaceRoot == null || workspaceRoot.isBlank())
                ? System.getProperty("user.dir")
                : workspaceRoot;
        return normalizePath(base).resolve(parsed).normalize();
    }

    private Path normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return Paths.get(".");
        }
        String normalized = rawPath.trim();
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("win");
        if (windows && normalized.matches("^/[a-zA-Z]/.*")) {
            char drive = Character.toUpperCase(normalized.charAt(1));
            normalized = drive + ":" + normalized.substring(2);
        }
        return Paths.get(normalized);
    }
}
