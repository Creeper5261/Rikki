package com.zzf.codeagent.core.tool;

import com.zzf.codeagent.project.ProjectContext;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

final class ToolPathResolver {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT)
            .contains("win");

    private ToolPathResolver() {
    }

    static String resolveWorkspaceRoot(ProjectContext projectContext, Tool.Context ctx) {
        String fromContext = readExtra(ctx, "workspaceRoot");
        if (!fromContext.isBlank()) {
            return normalizeToAbsoluteString(fromContext);
        }
        String fromProject = projectContext != null ? projectContext.getDirectory() : null;
        if (fromProject != null && !fromProject.isBlank()) {
            return normalizeToAbsoluteString(fromProject);
        }
        return normalizeToAbsoluteString(System.getProperty("user.dir"));
    }

    static Path resolvePath(ProjectContext projectContext, Tool.Context ctx, String rawPath) {
        Path parsed = parse(rawPath);
        Path resolved;
        if (parsed.isAbsolute()) {
            resolved = parsed.normalize();
        } else {
            Path workspace = parse(resolveWorkspaceRoot(projectContext, ctx));
            resolved = workspace.resolve(parsed).normalize();
        }
        return ensureInsideWorkspace(projectContext, ctx, resolved);
    }

    static Path resolveAgainst(Path base, String rawPath) {
        Path parsed = parse(rawPath);
        if (parsed.isAbsolute()) {
            return parsed.normalize();
        }
        if (base == null) {
            return parsed.toAbsolutePath().normalize();
        }
        return base.resolve(parsed).normalize();
    }

    static String safeRelativePath(ProjectContext projectContext, Tool.Context ctx, Path targetPath) {
        return safeRelativePath(resolveWorkspaceRoot(projectContext, ctx), targetPath);
    }

    static String safeRelativePath(String workspaceRoot, Path targetPath) {
        if (targetPath == null) {
            return "";
        }
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            return toTransportPath(normalizedTarget.toString());
        }
        Path root = parse(workspaceRoot).toAbsolutePath().normalize();
        try {
            if (normalizedTarget.startsWith(root)) {
                return toTransportPath(root.relativize(normalizedTarget).toString());
            }
        } catch (Exception ignored) {
        }
        return toTransportPath(normalizedTarget.toString());
    }

    static String normalizeToAbsoluteString(String rawPath) {
        Path p = parse(rawPath);
        return p.toAbsolutePath().normalize().toString();
    }

    static Path ensureInsideWorkspace(ProjectContext projectContext, Tool.Context ctx, Path targetPath) {
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        String workspaceRoot = resolveWorkspaceRoot(projectContext, ctx);
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            return normalizedTarget;
        }
        Path root = parse(workspaceRoot).toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace root: " + normalizedTarget);
        }
        return normalizedTarget;
    }

    private static Path parse(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return Paths.get(".");
        }
        String normalized = rawPath.trim();
        if (WINDOWS && normalized.matches("^/[a-zA-Z]/.*")) {
            char drive = Character.toUpperCase(normalized.charAt(1));
            normalized = drive + ":" + normalized.substring(2);
        }
        return Paths.get(normalized);
    }

    private static String readExtra(Tool.Context ctx, String key) {
        if (ctx == null) {
            return "";
        }
        Map<String, Object> extra = ctx.getExtra();
        if (extra == null) {
            return "";
        }
        Object value = extra.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String toTransportPath(String path) {
        return path == null ? "" : path.replace('\\', '/');
    }
}
