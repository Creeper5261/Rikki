package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records applied workspace changes for a session (already executed on disk).
 * This replaces the old "pending changes" flow for UI reporting.
 */
public final class AppliedChangesManager {
    private static final AppliedChangesManager INSTANCE = new AppliedChangesManager();
    private final List<AppliedChange> changes = new CopyOnWriteArrayList<>();

    private AppliedChangesManager() {}

    public static AppliedChangesManager getInstance() {
        return INSTANCE;
    }

    public void addChange(AppliedChange change) {
        String normPath = normalizePath(change.path);
        changes.removeIf(c -> normalizePath(c.path).equals(normPath) && sameScope(c, change));
        changes.add(change);
    }

    public List<AppliedChange> getChanges() {
        return Collections.unmodifiableList(changes);
    }

    public List<AppliedChange> getChanges(String workspaceRoot, String sessionId) {
        return changes.stream()
                .filter(c -> scopeMatches(c, workspaceRoot, sessionId))
                .toList();
    }

    public void clear() {
        changes.clear();
    }

    public void clear(String workspaceRoot, String sessionId) {
        changes.removeIf(c -> scopeMatches(c, workspaceRoot, sessionId));
    }

    public JsonNode toJson(ObjectMapper mapper, String workspaceRoot, String sessionId) {
        ArrayNode array = mapper.createArrayNode();
        for (AppliedChange c : getChanges(workspaceRoot, sessionId)) {
            ObjectNode node = mapper.createObjectNode();
            node.put("id", c.id);
            node.put("path", c.path);
            node.put("type", c.type);
            node.put("oldContent", c.oldContent);
            node.put("newContent", c.newContent);
            node.put("timestamp", c.timestamp);
            node.put("workspaceRoot", c.workspaceRoot);
            node.put("sessionId", c.sessionId);
            array.add(node);
        }
        return array;
    }

    private String normalizePath(String path) {
        if (path == null) return "";
        return path.replace('\\', '/').trim();
    }

    private boolean scopeMatches(AppliedChange change, String workspaceRoot, String sessionId) {
        if (change == null) return false;
        if (workspaceRoot != null && !workspaceRoot.isEmpty()) {
            if (change.workspaceRoot == null || !change.workspaceRoot.equals(workspaceRoot)) {
                return false;
            }
        }
        if (sessionId != null && !sessionId.isEmpty()) {
            if (change.sessionId == null || !change.sessionId.equals(sessionId)) {
                return false;
            }
        }
        return true;
    }

    private boolean sameScope(AppliedChange a, AppliedChange b) {
        if (a == null || b == null) return false;
        return java.util.Objects.equals(a.workspaceRoot, b.workspaceRoot)
                && java.util.Objects.equals(a.sessionId, b.sessionId);
    }

    public static final class AppliedChange {
        public final String id;
        public final String path;
        public final String type; // CREATE, EDIT, DELETE
        public final String oldContent;
        public final String newContent;
        public final long timestamp;
        public final String workspaceRoot;
        public final String sessionId;

        public AppliedChange(String id, String path, String type, String oldContent, String newContent, long timestamp, String workspaceRoot, String sessionId) {
            this.id = id;
            this.path = path;
            this.type = type;
            this.oldContent = oldContent;
            this.newContent = newContent;
            this.timestamp = timestamp;
            this.workspaceRoot = workspaceRoot;
            this.sessionId = sessionId;
        }

        public AppliedChange(String path, String type, String oldContent, String newContent, String workspaceRoot, String sessionId) {
            this(UUID.randomUUID().toString(), path, type, oldContent, newContent, System.currentTimeMillis(), workspaceRoot, sessionId);
        }
    }
}

