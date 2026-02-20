package com.zzf.rikki.controller;

import com.zzf.rikki.core.tool.PendingChangesManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        private String traceId; 
        private String workspaceRoot;
        private String path;
        private String changeId;
        private boolean reject;
    }

    @PostMapping
    public Map<String, Object> resolvePendingChange(@RequestBody PendingChangeRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            String path = request.getPath();
            String workspaceRoot = request.getWorkspaceRoot();
            String sessionId = request.getTraceId();
            String changeId = request.getChangeId();
            boolean reject = request.isReject();

            Optional<PendingChangesManager.PendingChange> changeOpt;
            if (changeId != null && !changeId.isBlank()) {
                changeOpt = PendingChangesManager.getInstance().getById(changeId)
                        .filter(change -> requestScopeMatchesChange(change, workspaceRoot, sessionId));
            } else {
                if ((sessionId == null || sessionId.isBlank()) && (workspaceRoot == null || workspaceRoot.isBlank())) {
                    response.put("status", "error");
                    response.put("error", "sessionId or workspaceRoot is required");
                    return response;
                }
                if (path == null || path.isBlank()) {
                    response.put("status", "error");
                    response.put("error", "path is required when changeId is not provided");
                    return response;
                }
                changeOpt = PendingChangesManager.getInstance().getPendingChange(path, workspaceRoot, sessionId);
            }

            if (changeOpt.isEmpty()) {
                response.put("status", "error");
                if (changeId != null && !changeId.isBlank()) {
                    response.put("error", "Pending change not found for id: " + changeId);
                } else {
                    response.put("error", "Pending change not found for scoped path: " + path);
                }
                return response;
            }

            PendingChangesManager.PendingChange change = changeOpt.get();

            if (reject) {
                PendingChangesManager.getInstance().removeChange(change.id);
                response.put("status", "rejected");
                return response;
            }

            applyChangeToDisk(change);
            
            PendingChangesManager.getInstance().removeChange(change.id);
            response.put("status", "applied");
            
        } catch (Exception e) {
            log.error("Failed to resolve pending change", e);
            response.put("status", "error");
            response.put("error", e.getMessage());
        }
        return response;
    }

    private void applyChangeToDisk(PendingChangesManager.PendingChange change) throws IOException {
        String type = change.type;
        String workspaceRoot = change.workspaceRoot;
        String relativePath = change.path;
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            throw new IllegalArgumentException("Pending change workspaceRoot is required.");
        }
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Pending change path is required.");
        }
        Path workspacePath = normalizePath(workspaceRoot).toAbsolutePath().normalize();
        
        Path targetPath = resolveTargetPath(workspacePath, relativePath);
        if (!targetPath.startsWith(workspacePath)) {
            throw new IllegalArgumentException("Target path escapes workspace root: " + targetPath);
        }
        
        if ("DELETE".equalsIgnoreCase(type)) {
            Files.deleteIfExists(targetPath);
        } else {
            
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }
            
            String content = change.newContent;
            if (content != null) {
                Files.writeString(targetPath, content, StandardCharsets.UTF_8);
            } else if ("CREATE".equalsIgnoreCase(type)) {
                
                Files.createFile(targetPath);
            }
        }
    }

    private Path resolveTargetPath(Path workspaceRoot, String path) {
        Path parsed = normalizePath(path);
        if (parsed.isAbsolute()) {
            return parsed.toAbsolutePath().normalize();
        }
        return workspaceRoot.resolve(parsed).normalize();
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

    private String normalizeComparablePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return "";
        }
        Path normalized = normalizePath(rawPath).toAbsolutePath().normalize();
        String text = normalized.toString().replace('\\', '/');
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            text = text.toLowerCase(Locale.ROOT);
        }
        return text;
    }

    private boolean requestScopeMatchesChange(
            PendingChangesManager.PendingChange change,
            String requestedWorkspaceRoot,
            String requestedSessionId
    ) {
        if (change == null) {
            return false;
        }
        if (requestedSessionId == null || requestedSessionId.isBlank()) {
            return false;
        }
        if (change.sessionId == null || !requestedSessionId.equals(change.sessionId)) {
            return false;
        }
        if (requestedWorkspaceRoot != null && !requestedWorkspaceRoot.isBlank()) {
            String expected = normalizeComparablePath(requestedWorkspaceRoot);
            String actual = normalizeComparablePath(change.workspaceRoot);
            return expected.equals(actual);
        }
        return true;
    }
}
