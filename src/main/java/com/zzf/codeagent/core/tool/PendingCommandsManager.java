package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class PendingCommandsManager {

    private static final PendingCommandsManager INSTANCE = new PendingCommandsManager();
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private final Map<String, PendingCommand> commands = new ConcurrentHashMap<>();

    private PendingCommandsManager() {
    }

    public static PendingCommandsManager getInstance() {
        return INSTANCE;
    }

    public void add(PendingCommand command) {
        if (command == null || command.id == null || command.id.isBlank()) {
            return;
        }
        commands.put(command.id, command);
    }

    public Optional<PendingCommand> get(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(commands.get(id));
    }

    public void remove(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        commands.remove(id);
    }

    public boolean hasPendingForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        for (PendingCommand command : commands.values()) {
            if (command != null && sessionId.equals(command.sessionId)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasPendingForScope(String workspaceRoot, String sessionId) {
        if ((workspaceRoot == null || workspaceRoot.isBlank())
                && (sessionId == null || sessionId.isBlank())) {
            return !commands.isEmpty();
        }
        String expectedRoot = normalize(workspaceRoot);
        for (PendingCommand command : commands.values()) {
            if (command == null) {
                continue;
            }
            if (sessionId != null && !sessionId.isBlank()
                    && (command.sessionId == null || !sessionId.equals(command.sessionId))) {
                continue;
            }
            if (workspaceRoot != null && !workspaceRoot.isBlank()) {
                String actualRoot = normalize(command.workspaceRoot);
                if (!expectedRoot.equals(actualRoot)) {
                    continue;
                }
            }
            return true;
        }
        return false;
    }

    void clear() {
        commands.clear();
    }

    public boolean scopeMatches(PendingCommand command, String workspaceRoot, String sessionId) {
        if (command == null) {
            return false;
        }
        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            String expectedRoot = normalize(workspaceRoot);
            String actualRoot = normalize(command.workspaceRoot);
            if (!actualRoot.equals(expectedRoot)) {
                return false;
            }
        }
        if (sessionId != null && !sessionId.isBlank()) {
            if (command.sessionId == null || !command.sessionId.equals(sessionId)) {
                return false;
            }
        }
        return true;
    }

    private String normalize(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (WINDOWS) {
            normalized = normalized.toLowerCase(Locale.ROOT);
            if (normalized.matches("^/[a-z]/.*")) {
                normalized = normalized.charAt(1) + ":" + normalized.substring(2);
            }
        }
        return normalized;
    }

    public static class PendingCommand {
        public final String id;
        public final String command;
        public final String description;
        public final String workdir;
        public final String workspaceRoot;
        public final String sessionId;
        public final long timeoutMs;
        public final String riskLevel;
        public final List<String> reasons;
        public final long createdAt;

        @JsonCreator
        public PendingCommand(
                @JsonProperty("id") String id,
                @JsonProperty("command") String command,
                @JsonProperty("description") String description,
                @JsonProperty("workdir") String workdir,
                @JsonProperty("workspaceRoot") String workspaceRoot,
                @JsonProperty("sessionId") String sessionId,
                @JsonProperty("timeoutMs") long timeoutMs,
                @JsonProperty("riskLevel") String riskLevel,
                @JsonProperty("reasons") List<String> reasons,
                @JsonProperty("createdAt") long createdAt
        ) {
            this.id = id;
            this.command = command;
            this.description = description;
            this.workdir = workdir;
            this.workspaceRoot = workspaceRoot;
            this.sessionId = sessionId;
            this.timeoutMs = timeoutMs;
            this.riskLevel = riskLevel;
            this.reasons = reasons;
            this.createdAt = createdAt;
        }
    }
}
