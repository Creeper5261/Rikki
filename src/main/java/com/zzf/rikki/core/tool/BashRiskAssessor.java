package com.zzf.rikki.core.tool;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BashRiskAssessor {
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"([^\"]+)\"|'([^']+)'|(\\S+)");

    Assessment assess(String command, String workspaceRoot) {
        Assessment base = assessBaseRisk(command);
        return mergeWorkspaceBoundaryRisk(base, command, workspaceRoot);
    }

    String extractCommandFamily(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        Matcher matcher = TOKEN_PATTERN.matcher(command);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            String token = firstNonBlank(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3)
            );
            if (token != null && !token.isBlank()) {
                tokens.add(token.trim());
            }
        }
        for (String raw : tokens) {
            String token = normalizeFamilyToken(raw);
            if (token.isBlank()) {
                continue;
            }
            if ("sudo".equals(token) || "env".equals(token)) {
                continue;
            }
            if (isShellWrapperToken(token)) {
                continue;
            }
            if (token.startsWith("-")) {
                continue;
            }
            return token;
        }
        return "";
    }

    private Assessment assessBaseRisk(String command) {
        if (command == null || command.isBlank()) {
            return new Assessment(false, List.of(), false, "restricted");
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
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:sudo\\s+)?rm\\s+.+")) {
            addReason(reasons, "file delete command detected");
        }
        if (normalized.matches(".*\\brm\\b.*\\s-[a-z]*r[a-z]*\\b.*")) {
            addReason(reasons, "recursive delete command detected");
        }
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:cmd\\s+/c\\s+)?(?:del|erase)\\s+.+")) {
            addReason(reasons, "windows file delete command detected");
        }
        if (normalized.matches(".*\\b(del|erase)\\b.*(/s|/q|/f).*")) {
            addReason(reasons, "windows recursive/force delete detected");
        }
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:powershell(?:\\.exe)?\\s+-command\\s+)?(?:remove-item|ri)\\s+.+")) {
            addReason(reasons, "powershell file delete command detected");
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
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:sudo\\s+)?mv\\s+.+")) {
            addReason(reasons, "move/rename command detected");
        }
        if (normalized.matches(".*(?:^|[;&|])\\s*(?:cmd\\s+/c\\s+)?(?:move|ren|rename)\\s+.+")) {
            addReason(reasons, "windows move/rename command detected");
        }
        if (normalized.matches(".*\\b(rename-item)\\b.*")) {
            addReason(reasons, "powershell move/rename command detected");
        }
        if (normalized.matches(".*\\bgit\\s+mv\\b.*")) {
            addReason(reasons, "git move/rename detected");
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
        if (normalized.matches(".*\\b(winget|choco|chocolatey|scoop|apt|apt-get|yum|dnf|zypper|pacman|brew|port|pip|pip3|npm|pnpm|yarn|gem|cargo|go)\\b.*\\b(install|add|upgrade|update)\\b.*")) {
            addReason(reasons, "software/package installation detected (explicit user consent required)");
        }
        if (normalized.matches(".*\\b(sdk|jabba|asdf)\\b.*\\binstall\\b.*")) {
            addReason(reasons, "runtime/toolchain installation detected (explicit user consent required)");
        }
        if (normalized.matches(".*\\bgit\\s+clone\\b.*")) {
            addReason(reasons, "repository download/clone detected (explicit user consent required)");
        }
        if (normalized.matches(".*\\b(curl|wget|invoke-webrequest|iwr|start-bitstransfer|bitsadmin)\\b.*(https?://|ftp://).*")) {
            addReason(reasons, "network download command detected (explicit user consent required)");
        }
        if (normalized.contains(":(){ :|:& };:")) {
            addReason(reasons, "fork-bomb pattern detected");
        }

        boolean strictApproval = reasons.stream().anyMatch(this::isStrictRiskReason);
        String riskCategory = strictApproval ? "destructive" : "restricted";
        return new Assessment(!reasons.isEmpty(), reasons, strictApproval, riskCategory);
    }

    private Assessment mergeWorkspaceBoundaryRisk(Assessment baseRisk, String command, String workspaceRoot) {
        List<String> boundaryReasons = detectWorkspaceBoundaryRisk(command, workspaceRoot);
        if (boundaryReasons.isEmpty()) {
            return baseRisk;
        }
        List<String> mergedReasons = new ArrayList<>();
        if (baseRisk != null && baseRisk.reasons != null) {
            mergedReasons.addAll(baseRisk.reasons);
        }
        for (String reason : boundaryReasons) {
            if (reason == null || reason.isBlank()) {
                continue;
            }
            if (!mergedReasons.contains(reason)) {
                mergedReasons.add(reason);
            }
        }
        return new Assessment(true, mergedReasons, true, "workspace_boundary");
    }

    private List<String> detectWorkspaceBoundaryRisk(String command, String workspaceRoot) {
        List<String> reasons = new ArrayList<>();
        if (command == null || command.isBlank()) {
            return reasons;
        }

        if (containsParentTraversal(command)) {
            reasons.add("parent-directory traversal detected (may escape workspace)");
        }

        Set<Path> candidates = extractAbsolutePathCandidates(command);
        if (candidates.isEmpty()) {
            return reasons;
        }

        // Resolve a trusted workspace root. If it is blank or resolves to a trivially
        // shallow path (depth < 2, e.g. "/" or "C:\"), we cannot use it as a meaningful
        // boundary. Fall back to flagging system-level paths directly.
        Path root = null;
        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            try {
                Path candidate = Paths.get(workspaceRoot).toAbsolutePath().normalize();
                if (candidate.getNameCount() >= 2) {
                    root = candidate;
                }
            } catch (Exception ignored) {
            }
        }

        int outsideCount = 0;
        for (Path candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            Path normalized = candidate.toAbsolutePath().normalize();
            if (root != null) {
                // Normal workspace boundary check
                if (!normalized.startsWith(root)) {
                    reasons.add("outside-workspace path access detected: " + normalized);
                    outsideCount++;
                    if (outsideCount >= 3) {
                        break;
                    }
                }
            } else {
                // No valid workspace root available: flag system-level paths
                // (depth ≤ 1 means /etc, /var, /bin, C:\Windows, etc.)
                if (normalized.getNameCount() <= 1) {
                    reasons.add("system-level path access detected (no workspace boundary configured): " + normalized);
                    outsideCount++;
                    if (outsideCount >= 3) {
                        break;
                    }
                }
            }
        }
        return reasons;
    }

    private boolean containsParentTraversal(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT)
                .replace('\r', ' ')
                .replace('\n', ' ');
        return normalized.matches(".*(?:^|[;&|])\\s*cd\\s+\\.\\.(?:[\\\\/][^;&|\\s]+)*.*")
                || normalized.matches(".*(?:^|[;&|])\\s*pushd\\s+\\.\\.(?:[\\\\/][^;&|\\s]+)*.*")
                || normalized.matches(".*(?:^|[;&|])\\s*set-location\\s+\\.\\.(?:[\\\\/][^;&|\\s]+)*.*");
    }

    private Set<Path> extractAbsolutePathCandidates(String command) {
        Set<Path> candidates = new LinkedHashSet<>();
        if (command == null || command.isBlank()) {
            return candidates;
        }
        Matcher matcher = TOKEN_PATTERN.matcher(command);
        while (matcher.find()) {
            String token = firstNonBlank(matcher.group(1), matcher.group(2), matcher.group(3));
            addAbsolutePathCandidate(candidates, token);
        }
        return candidates;
    }

    private void addAbsolutePathCandidate(Set<Path> candidates, String rawToken) {
        if (candidates == null || rawToken == null || rawToken.isBlank()) {
            return;
        }
        String token = sanitizePathToken(rawToken);
        if (token.isBlank()) {
            return;
        }
        // Recursively split compound tokens (e.g. the dequoted content of
        // bash -c "ls /etc" becomes "ls /etc" as one token).
        // Splitting on whitespace ensures the embedded absolute path is checked.
        if (token.contains(" ") || token.contains("\t")) {
            for (String sub : token.trim().split("\\s+")) {
                if (!sub.isBlank() && sub.length() > 1) {
                    addAbsolutePathCandidate(candidates, sub);
                }
            }
            return;
        }
        int equalsIndex = token.indexOf('=');
        if (equalsIndex > 0 && equalsIndex + 1 < token.length()) {
            addAbsolutePathCandidate(candidates, token.substring(equalsIndex + 1));
        }
        Path absolute = parseAbsolutePathCandidate(token);
        if (absolute != null) {
            candidates.add(absolute);
        }
    }

    private String sanitizePathToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String sanitized = token.trim();
        while (!sanitized.isBlank() && "([{".indexOf(sanitized.charAt(0)) >= 0) {
            sanitized = sanitized.substring(1).trim();
        }
        while (!sanitized.isBlank() && ")]},;|&".indexOf(sanitized.charAt(sanitized.length() - 1)) >= 0) {
            sanitized = sanitized.substring(0, sanitized.length() - 1).trim();
        }
        if (sanitized.startsWith("file://")) {
            sanitized = sanitized.substring("file://".length());
        }
        if (sanitized.startsWith("~/")) {
            sanitized = System.getProperty("user.home", "") + sanitized.substring(1);
        }
        return sanitized;
    }

    private Path parseAbsolutePathCandidate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String normalized = token.trim();
        if (isWindows()) {
            if (normalized.matches("^/[a-zA-Z]/.*")) {
                char drive = Character.toUpperCase(normalized.charAt(1));
                normalized = drive + ":" + normalized.substring(2);
            }
            if (normalized.matches("^[a-zA-Z]:[\\\\/].*")) {
                try {
                    return Paths.get(normalized).toAbsolutePath().normalize();
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        if (normalized.startsWith("\\\\") || normalized.startsWith("/")) {
            try {
                return Paths.get(normalized).toAbsolutePath().normalize();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
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

    private boolean isStrictRiskReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        return normalized.contains("delete")
                || normalized.contains("destructive")
                || normalized.contains("disk")
                || normalized.contains("shutdown")
                || normalized.contains("fork-bomb")
                || normalized.contains("move/rename");
    }

    private boolean isShellWrapperToken(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return "bash".equals(token)
                || "sh".equals(token)
                || "zsh".equals(token)
                || "fish".equals(token)
                || "cmd".equals(token)
                || "cmd.exe".equals(token)
                || "powershell".equals(token)
                || "powershell.exe".equals(token)
                || "pwsh".equals(token)
                || "pwsh.exe".equals(token)
                || "-c".equals(token)
                || "-lc".equals(token)
                || "/c".equals(token)
                || "-command".equals(token)
                || "-noprofile".equals(token)
                || "-noninteractive".equals(token);
    }

    private String normalizeFamilyToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return "";
        }
        String token = rawToken.trim().replace("\"", "").replace("'", "");
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < token.length()) {
            token = token.substring(slash + 1);
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".cmd") || lower.endsWith(".bat")) {
            lower = lower.substring(0, lower.length() - 4);
        }
        lower = lower.replaceAll("[^a-z0-9._-]", "");
        return lower;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    static final class Assessment {
        final boolean requiresApproval;
        final List<String> reasons;
        final boolean strictApproval;
        final String riskCategory;

        Assessment(boolean requiresApproval, List<String> reasons, boolean strictApproval, String riskCategory) {
            this.requiresApproval = requiresApproval;
            this.reasons = reasons;
            this.strictApproval = strictApproval;
            this.riskCategory = riskCategory == null ? "restricted" : riskCategory;
        }
    }
}
