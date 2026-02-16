package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class PendingCommandsManager {

    public static final String DECISION_MANUAL = "manual";
    public static final String DECISION_WHITELIST = "whitelist";
    public static final String DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE = "always_allow_non_destructive";

    private static final PendingCommandsManager INSTANCE = new PendingCommandsManager();
    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private final Map<String, PendingCommand> commands = new ConcurrentHashMap<>();
    private final Map<String, ApprovalPolicy> sessionPolicies = new ConcurrentHashMap<>();

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

    public boolean shouldAutoApprove(String sessionId, String commandFamily, boolean strictApproval) {
        if (strictApproval || sessionId == null || sessionId.isBlank()) {
            return false;
        }
        ApprovalPolicy policy = sessionPolicies.get(sessionId);
        if (policy == null) {
            return false;
        }
        if (policy.alwaysAllowNonDestructive) {
            return true;
        }
        String family = normalizeCommandFamily(commandFamily);
        return !family.isBlank() && policy.whitelistFamilies.contains(family);
    }

    public void applyApprovalDecision(PendingCommand command, String decisionModeRaw) {
        if (command == null || command.strictApproval) {
            return;
        }
        if (command.sessionId == null || command.sessionId.isBlank()) {
            return;
        }
        String decisionMode = normalizeDecisionMode(decisionModeRaw);
        ApprovalPolicy policy = sessionPolicies.computeIfAbsent(command.sessionId, key -> new ApprovalPolicy());
        if (DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE.equals(decisionMode)) {
            policy.alwaysAllowNonDestructive = true;
            return;
        }
        if (DECISION_WHITELIST.equals(decisionMode)) {
            String family = normalizeCommandFamily(firstNonBlank(command.commandFamily, inferCommandFamily(command.command)));
            if (!family.isBlank()) {
                policy.whitelistFamilies.add(family);
            }
        }
    }

    public static String normalizeDecisionMode(String decisionModeRaw) {
        if (decisionModeRaw == null || decisionModeRaw.isBlank()) {
            return DECISION_MANUAL;
        }
        String normalized = decisionModeRaw.trim().toLowerCase(Locale.ROOT);
        if (DECISION_WHITELIST.equals(normalized)) {
            return DECISION_WHITELIST;
        }
        if (DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE.equals(normalized)) {
            return DECISION_ALWAYS_ALLOW_NON_DESTRUCTIVE;
        }
        return DECISION_MANUAL;
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

    public void clearPendingForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        commands.entrySet().removeIf(entry -> {
            PendingCommand command = entry.getValue();
            return command != null && sessionId.equals(command.sessionId);
        });
    }

    public boolean hasPendingForScope(String workspaceRoot, String sessionId) {
        boolean hasWorkspaceScope = workspaceRoot != null && !workspaceRoot.isBlank();
        boolean hasSessionScope = sessionId != null && !sessionId.isBlank();
        if (!hasWorkspaceScope && !hasSessionScope) {
            return !commands.isEmpty();
        }
        String expectedRoot = normalize(workspaceRoot);
        for (PendingCommand command : commands.values()) {
            if (command == null) {
                continue;
            }
            if (hasSessionScope) {
                if (command.sessionId == null || !sessionId.equals(command.sessionId)) {
                    continue;
                }
                return true;
            }
            if (hasWorkspaceScope) {
                String actualRoot = normalize(command.workspaceRoot);
                if (!expectedRoot.equals(actualRoot)) {
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    public boolean scopeMatches(PendingCommand command, String workspaceRoot, String sessionId) {
        if (command == null) {
            return false;
        }
        boolean hasSessionScope = sessionId != null && !sessionId.isBlank();
        if (hasSessionScope) {
            return sessionId.equals(command.sessionId);
        }
        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            String expectedRoot = normalize(workspaceRoot);
            String actualRoot = normalize(command.workspaceRoot);
            return actualRoot.equals(expectedRoot);
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

    private static String normalizeCommandFamily(String rawFamily) {
        if (rawFamily == null) {
            return "";
        }
        String normalized = rawFamily.trim().toLowerCase(Locale.ROOT);
        int slash = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < normalized.length()) {
            normalized = normalized.substring(slash + 1);
        }
        normalized = trimExecutableExtension(normalized);
        return normalized.replaceAll("[^a-z0-9._-]", "");
    }

    private static String inferCommandFamily(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String trimmed = command.trim();
        int firstSpace = trimmed.indexOf(' ');
        String token = firstSpace > 0 ? trimmed.substring(0, firstSpace) : trimmed;
        token = token.replace("\"", "").replace("'", "");
        return normalizeCommandFamily(token);
    }

    private static String trimExecutableExtension(String token) {
        if (token.endsWith(".exe") || token.endsWith(".cmd") || token.endsWith(".bat")) {
            return token.substring(0, token.length() - 4);
        }
        return token;
    }

    private static boolean isStrictCategory(String riskCategory) {
        if (riskCategory == null || riskCategory.isBlank()) {
            return false;
        }
        String normalized = riskCategory.trim().toLowerCase(Locale.ROOT);
        return "destructive".equals(normalized) || "strict".equals(normalized);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second == null ? "" : second;
    }

    void clear() {
        commands.clear();
        sessionPolicies.clear();
    }

    private static final class ApprovalPolicy {
        volatile boolean alwaysAllowNonDestructive;
        final Set<String> whitelistFamilies = ConcurrentHashMap.newKeySet();
    }

    public static class PendingCommand {
        public final String id;
        public final String command;
        public final String description;
        public final String workdir;
        public final String shell;
        public final String workspaceRoot;
        public final String sessionId;
        public final String messageId;
        public final String callId;
        public final long timeoutMs;
        public final String riskLevel;
        public final List<String> reasons;
        public final String commandFamily;
        public final String riskCategory;
        public final boolean strictApproval;
        public final long createdAt;

        @JsonCreator
        public PendingCommand(
                @JsonProperty("id") String id,
                @JsonProperty("command") String command,
                @JsonProperty("description") String description,
                @JsonProperty("workdir") String workdir,
                @JsonProperty("shell") String shell,
                @JsonProperty("workspaceRoot") String workspaceRoot,
                @JsonProperty("sessionId") String sessionId,
                @JsonProperty("messageId") String messageId,
                @JsonProperty("callId") String callId,
                @JsonProperty("timeoutMs") long timeoutMs,
                @JsonProperty("riskLevel") String riskLevel,
                @JsonProperty("reasons") List<String> reasons,
                @JsonProperty("commandFamily") String commandFamily,
                @JsonProperty("riskCategory") String riskCategory,
                @JsonProperty("strictApproval") Boolean strictApproval,
                @JsonProperty("createdAt") long createdAt
        ) {
            this.id = id;
            this.command = command;
            this.description = description;
            this.workdir = workdir;
            this.shell = shell;
            this.workspaceRoot = workspaceRoot;
            this.sessionId = sessionId;
            this.messageId = messageId;
            this.callId = callId;
            this.timeoutMs = timeoutMs;
            this.riskLevel = riskLevel;
            this.reasons = reasons;
            this.commandFamily = normalizeCommandFamily(firstNonBlank(commandFamily, inferCommandFamily(command)));
            this.riskCategory = (riskCategory == null || riskCategory.isBlank()) ? "restricted" : riskCategory;
            this.strictApproval = Boolean.TRUE.equals(strictApproval) || isStrictCategory(this.riskCategory);
            this.createdAt = createdAt;
        }
    }
}
