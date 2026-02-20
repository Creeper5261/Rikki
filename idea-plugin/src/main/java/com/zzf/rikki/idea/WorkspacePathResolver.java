package com.zzf.rikki.idea;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

final class WorkspacePathResolver {

    private final String workspaceRoot;

    WorkspacePathResolver(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    boolean isNavigableFileTarget(String rawPath) {
        Path target = resolveWorkspaceFilePath(rawPath);
        return target != null && Files.isRegularFile(target);
    }

    Path resolveWorkspaceFilePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        Path parsed = parseAnyPath(rawPath);
        if (parsed == null) {
            return null;
        }
        Path root = parseAnyPath(workspaceRoot);
        Path absolute = parsed;
        if (!absolute.isAbsolute()) {
            if (root == null) {
                return null;
            }
            absolute = root.resolve(parsed);
        }
        absolute = absolute.toAbsolutePath().normalize();
        if (root != null) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (!isUnderWorkspaceRoot(absolute, normalizedRoot)) {
                return null;
            }
        }
        if (Files.isDirectory(absolute)) {
            return null;
        }
        return absolute;
    }

    Path parseAnyPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        String normalized = rawPath.trim();
        if (normalized.matches("^/[a-zA-Z]/.*")) {
            char drive = Character.toUpperCase(normalized.charAt(1));
            normalized = drive + ":" + normalized.substring(2);
        }
        try {
            return Path.of(normalized);
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    boolean isUnderWorkspaceRoot(Path target, Path root) {
        if (target == null || root == null) {
            return false;
        }
        if (target.startsWith(root)) {
            return true;
        }
        String targetText = target.toString().replace('\\', '/').toLowerCase();
        String rootText = root.toString().replace('\\', '/').toLowerCase();
        return targetText.startsWith(rootText);
    }
}
