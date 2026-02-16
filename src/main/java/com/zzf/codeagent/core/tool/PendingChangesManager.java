package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages a list of pending workspace changes (staging area).
 * Allows accumulating multiple changes before applying them.
 */
@lombok.extern.slf4j.Slf4j
public class PendingChangesManager {
    private static final PendingChangesManager INSTANCE = new PendingChangesManager();
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");
    private final List<PendingChange> changes = new CopyOnWriteArrayList<>();

    private final List<java.util.function.Consumer<PendingChange>> listeners = new CopyOnWriteArrayList<>();

    private PendingChangesManager() {}

    public static PendingChangesManager getInstance() {
        return INSTANCE;
    }

    public void addChangeListener(java.util.function.Consumer<PendingChange> listener) {
        listeners.add(listener);
    }

    public void addChange(PendingChange change) {
        // Remove existing pending change for the same file to avoid conflicts
        // Normalize path to ensure uniqueness (assuming relative paths)
        String normPath = normalizePath(change.path);
        
        PendingChange existing = null;
        for (PendingChange c : changes) {
            if (normalizePath(c.path).equals(normPath) && sameScope(c, change)) {
                existing = c;
                break;
            }
        }

        PendingChange toAdd = change;
        if (existing != null) {
            String newType = existing.type;
            if ("DELETE".equalsIgnoreCase(existing.type) && "CREATE".equalsIgnoreCase(change.type)) {
                newType = "EDIT";
            } else if ("CREATE".equalsIgnoreCase(existing.type) && "EDIT".equalsIgnoreCase(change.type)) {
                newType = "CREATE";
            } else {
                newType = change.type;
            }

            toAdd = new PendingChange(
                existing.id,
                change.path,
                newType,
                existing.oldContent,
                change.newContent,
                change.preview,
                change.timestamp,
                change.workspaceRoot,
                change.sessionId
            );
            changes.remove(existing);
        }
        
        changes.add(toAdd);
        
        // Notify listeners
        for (java.util.function.Consumer<PendingChange> listener : listeners) {
            try {
                listener.accept(toAdd);
            } catch (Exception e) {
                log.error("Failed to notify listener", e);
            }
        }
    }

    public java.util.Optional<PendingChange> getPendingChange(String path) {
        if (path == null) return java.util.Optional.empty();
        String norm = normalizePath(path);
        return changes.stream()
                .filter(c -> normalizePath(c.path).equals(norm))
                .findFirst();
    }

    public java.util.Optional<PendingChange> getPendingChange(String path, String workspaceRoot, String sessionId) {
        if (path == null) return java.util.Optional.empty();
        String norm = normalizePath(path);
        return changes.stream()
                .filter(c -> normalizePath(c.path).equals(norm))
                .filter(c -> scopeMatches(c, workspaceRoot, sessionId))
                .findFirst();
    }
    
    public List<String> getPendingPaths() {
        return changes.stream().map(c -> c.path).collect(java.util.stream.Collectors.toList());
    }

    private String normalizePath(String path) {
        return normalizeComparablePath(path);
    }

    private String normalizeWorkspaceRoot(String workspaceRoot) {
        return normalizeComparablePath(workspaceRoot);
    }

    private String normalizeComparablePath(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        String norm = rawPath.trim();
        if (norm.isEmpty()) {
            return "";
        }
        if (WINDOWS && norm.matches("^/[a-zA-Z]/.*")) {
            char drive = Character.toLowerCase(norm.charAt(1));
            norm = drive + ":" + norm.substring(2);
        }
        norm = norm.replace('\\', '/');
        while (norm.endsWith("/")) {
            norm = norm.substring(0, norm.length() - 1);
        }
        if (WINDOWS) {
            norm = norm.toLowerCase(Locale.ROOT);
        }
        return norm;
    }


    public List<PendingChange> getChanges() {
        return Collections.unmodifiableList(changes);
    }

    public List<PendingChange> getChanges(String workspaceRoot, String sessionId) {
        return changes.stream()
                .filter(c -> scopeMatches(c, workspaceRoot, sessionId))
                .collect(java.util.stream.Collectors.toList());
    }

    public boolean hasPendingDeleteForScope(String workspaceRoot, String sessionId) {
        return changes.stream()
                .filter(c -> "DELETE".equalsIgnoreCase(c.type))
                .anyMatch(c -> scopeMatches(c, workspaceRoot, sessionId));
    }

    public boolean hasPendingDeleteForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return changes.stream()
                .filter(c -> "DELETE".equalsIgnoreCase(c.type))
                .anyMatch(c -> sessionId.equals(c.sessionId));
    }

    public void clearPendingDeletesForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        changes.removeIf(c -> "DELETE".equalsIgnoreCase(c.type) && sessionId.equals(c.sessionId));
    }

    public void clear() {
        changes.clear();
    }

    public void clear(String workspaceRoot, String sessionId) {
        changes.removeIf(c -> scopeMatches(c, workspaceRoot, sessionId));
    }
    
    public void removeChange(String id) {
        changes.removeIf(c -> c.id.equals(id));
    }

    public void loadFromState(JsonNode rootNode) {
        if (rootNode == null) return;
        
        changes.clear();
        
        // Load from pending_changes array
        JsonNode array = rootNode.path("pending_changes");
        if (array.isArray()) {
            for (JsonNode node : array) {
                String path = node.path("path").asText(null);
                if (path != null) {
                    changes.add(new PendingChange(
                        node.path("id").asText(UUID.randomUUID().toString()),
                        path,
                        node.path("type").asText("EDIT"),
                        node.path("old_content").asText(null),
                        node.path("new_content").asText(null),
                        node.path("preview").asText(null),
                        node.path("timestamp").asLong(System.currentTimeMillis()),
                        node.path("workspace_root").asText(null),
                        node.path("session_id").asText(null)
                    ));
                }
            }
        }
        
        // Fallback: load from legacy pending_diff object if array is empty
        if (changes.isEmpty()) {
            JsonNode pending = rootNode.path("pending_diff");
            if (!pending.isMissingNode() && !pending.isNull()) {
                String path = pending.path("path").asText(null);
                if (path != null) {
                    changes.add(new PendingChange(
                        UUID.randomUUID().toString(),
                        path,
                        "EDIT",
                        pending.path("old_content").asText(null),
                        pending.path("new_content").asText(null),
                        null,
                        System.currentTimeMillis(),
                        pending.path("workspace_root").asText(null),
                        pending.path("session_id").asText(null)
                    ));
                }
            }
        }
    }

    public void loadFromState(JsonNode rootNode, String workspaceRoot, String sessionId) {
        if (rootNode == null) return;
        changes.removeIf(c -> scopeMatches(c, workspaceRoot, sessionId));

        JsonNode array = rootNode.path("pending_changes");
        if (array.isArray()) {
            for (JsonNode node : array) {
                String path = node.path("path").asText(null);
                if (path != null) {
                    changes.add(new PendingChange(
                        node.path("id").asText(UUID.randomUUID().toString()),
                        path,
                        node.path("type").asText("EDIT"),
                        node.path("old_content").asText(null),
                        node.path("new_content").asText(null),
                        node.path("preview").asText(null),
                        node.path("timestamp").asLong(System.currentTimeMillis()),
                        node.path("workspace_root").asText(workspaceRoot),
                        node.path("session_id").asText(sessionId)
                    ));
                }
            }
        }

        if (array.isMissingNode() || !array.isArray() || !array.elements().hasNext()) {
            JsonNode pending = rootNode.path("pending_diff");
            if (!pending.isMissingNode() && !pending.isNull()) {
                String path = pending.path("path").asText(null);
                if (path != null) {
                    changes.add(new PendingChange(
                        UUID.randomUUID().toString(),
                        path,
                        "EDIT",
                        pending.path("old_content").asText(null),
                        pending.path("new_content").asText(null),
                        null,
                        System.currentTimeMillis(),
                        pending.path("workspace_root").asText(workspaceRoot),
                        pending.path("session_id").asText(sessionId)
                    ));
                }
            }
        }
    }

    public JsonNode toJson(ObjectMapper mapper) {
        ArrayNode array = mapper.createArrayNode();
        for (PendingChange c : changes) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", c.id);
            node.put("path", c.path);
            node.put("type", c.type); // CREATE, EDIT, DELETE
            node.put("old_content", c.oldContent);
            node.put("new_content", c.newContent);
            node.put("preview", c.preview); // Visual diff or summary
            node.put("timestamp", c.timestamp);
            node.put("workspace_root", c.workspaceRoot);
            node.put("session_id", c.sessionId);
            array.add(node);
        }
        return array;
    }

    public JsonNode toJson(ObjectMapper mapper, String workspaceRoot, String sessionId) {
        ArrayNode array = mapper.createArrayNode();
        List<PendingChange> scoped = getChanges(workspaceRoot, sessionId);
        for (PendingChange c : scoped) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", c.id);
            node.put("path", c.path);
            node.put("type", c.type);
            node.put("old_content", c.oldContent);
            node.put("new_content", c.newContent);
            node.put("preview", c.preview);
            node.put("timestamp", c.timestamp);
            node.put("workspace_root", c.workspaceRoot);
            node.put("session_id", c.sessionId);
            array.add(node);
        }
        return array;
    }

    public static class PendingChange {
        public final String id;
        public final String path;
        public final String type;
        public final String oldContent; // Optional, for verification
        public final String newContent; // Content to write
        public final String preview; // Visual diff
        public final long timestamp;
        public final String workspaceRoot;
        public final String sessionId;

        public PendingChange(String path, String type, String oldContent, String newContent, String preview) {
            this(UUID.randomUUID().toString(), path, type, oldContent, newContent, preview, System.currentTimeMillis(), null, null);
        }

        public PendingChange(String path, String type, String oldContent, String newContent, String preview, String workspaceRoot) {
            this(UUID.randomUUID().toString(), path, type, oldContent, newContent, preview, System.currentTimeMillis(), workspaceRoot, null);
        }

        public PendingChange(String id, String path, String type, String oldContent, String newContent, String preview, long timestamp) {
            this(id, path, type, oldContent, newContent, preview, timestamp, null, null);
        }

        public PendingChange(String id, String path, String type, String oldContent, String newContent, String preview, long timestamp, String workspaceRoot) {
            this(id, path, type, oldContent, newContent, preview, timestamp, workspaceRoot, null);
        }

        @JsonCreator
        public PendingChange(
            @JsonProperty("id") String id, 
            @JsonProperty("path") String path, 
            @JsonProperty("type") String type, 
            @JsonProperty("old_content") String oldContent, 
            @JsonProperty("new_content") String newContent, 
            @JsonProperty("preview") String preview, 
            @JsonProperty("timestamp") long timestamp, 
            @JsonProperty("workspace_root") String workspaceRoot, 
            @JsonProperty("session_id") String sessionId
        ) {
            this.id = id;
            this.path = path;
            this.type = type;
            this.oldContent = oldContent;
            this.newContent = newContent;
            this.preview = preview;
            this.timestamp = timestamp;
            this.workspaceRoot = workspaceRoot;
            this.sessionId = sessionId;
        }
    }

    private boolean scopeMatches(PendingChange change, String workspaceRoot, String sessionId) {
        if (change == null) return false;
        if (sessionId != null && !sessionId.isEmpty()) {
            return sessionId.equals(change.sessionId);
        }
        if (workspaceRoot != null && !workspaceRoot.isEmpty()) {
            String expected = normalizeWorkspaceRoot(workspaceRoot);
            String actual = normalizeWorkspaceRoot(change.workspaceRoot);
            return actual.equals(expected);
        }
        return true;
    }

    private boolean sameScope(PendingChange a, PendingChange b) {
        if (a == null || b == null) return false;
        return java.util.Objects.equals(normalizeWorkspaceRoot(a.workspaceRoot), normalizeWorkspaceRoot(b.workspaceRoot))
                && java.util.Objects.equals(a.sessionId, b.sessionId);
    }
}
