package com.zzf.codeagent.core.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

public final class SessionWorkspace {
    public enum Kind {
        NONE,
        COPY,
        WORKTREE
    }

    private static final Set<String> DEFAULT_SKIP_DIRS = new HashSet<>();

    static {
        DEFAULT_SKIP_DIRS.add(".git");
        DEFAULT_SKIP_DIRS.add(".idea");
        DEFAULT_SKIP_DIRS.add(".gradle");
        DEFAULT_SKIP_DIRS.add(".codeagent");
        DEFAULT_SKIP_DIRS.add(".trae");
        DEFAULT_SKIP_DIRS.add("build");
        DEFAULT_SKIP_DIRS.add("out");
        DEFAULT_SKIP_DIRS.add("target");
        DEFAULT_SKIP_DIRS.add("node_modules");
        DEFAULT_SKIP_DIRS.add("dist");
    }

    private final Path realRoot;
    private final Path sessionRoot;
    private final boolean active;
    private final Kind kind;
    private final String branchName;

    private SessionWorkspace(Path realRoot, Path sessionRoot, boolean active, Kind kind, String branchName) {
        this.realRoot = realRoot;
        this.sessionRoot = sessionRoot;
        this.active = active;
        this.kind = kind == null ? Kind.NONE : kind;
        this.branchName = branchName;
    }

    public static SessionWorkspace create(String workspaceRoot, String sessionId) {
        if (workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
            return new SessionWorkspace(null, null, false, Kind.NONE, null);
        }
        Path real = Path.of(workspaceRoot.trim()).toAbsolutePath().normalize();
        String safeId = sessionId == null || sessionId.trim().isEmpty()
                ? "session"
                : sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        if (isGitRepo(real)) {
            Path tempBase = Path.of(System.getProperty("java.io.tmpdir"), "codeagent-worktrees");
            Path session = tempBase.resolve(safeId);
            String branch = "codeagent/" + safeId;
            try {
                if (Files.exists(session)) {
                    removeWorktree(real, session, null);
                    deleteDirectory(session);
                }
                Files.createDirectories(tempBase);
                if (createWorktree(real, session, branch)) {
                    return new SessionWorkspace(real, session, true, Kind.WORKTREE, branch);
                }
            } catch (Exception ignored) {
            }
        }
        Path tempBase = Path.of(System.getProperty("java.io.tmpdir"), "codeagent-sessions");
        Path session = tempBase.resolve(safeId);
        try {
            if (Files.exists(session)) {
                deleteDirectory(session);
            }
            Files.createDirectories(session);
            copyDirectory(real, session);
            return new SessionWorkspace(real, session, true, Kind.COPY, null);
        } catch (Exception e) {
            return new SessionWorkspace(real, real, false, Kind.NONE, null);
        }
    }

    public Path getRealRoot() {
        return realRoot;
    }

    public Path getSessionRoot() {
        return sessionRoot;
    }

    public boolean isActive() {
        return active;
    }

    public Kind getKind() {
        return kind;
    }

    public void cleanup() {
        if (!active || sessionRoot == null) return;
        try {
            if (kind == Kind.WORKTREE) {
                removeWorktree(realRoot, sessionRoot, branchName);
            } else if (kind == Kind.COPY) {
                deleteDirectory(sessionRoot);
            }
        } catch (Exception ignored) {
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                if (!rel.toString().isEmpty()) {
                    Path name = dir.getFileName();
                    if (name != null && DEFAULT_SKIP_DIRS.contains(name.toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                Path dest = target.resolve(rel);
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Path dest = target.resolve(rel);
                Files.createDirectories(dest.getParent());
                Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteDirectory(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static boolean isGitRepo(Path root) {
        if (root == null) return false;
        Path git = root.resolve(".git");
        return Files.exists(git);
    }

    private static boolean createWorktree(Path repoRoot, Path worktreePath, String branch) {
        String repo = repoRoot.toString();
        String wt = worktreePath.toString();
        boolean added = runGit(new String[]{"git", "-C", repo, "worktree", "add", "--no-checkout", "-b", branch, wt});
        if (!added) {
            return false;
        }
        return runGit(new String[]{"git", "-C", wt, "reset", "--hard"});
    }

    private static void removeWorktree(Path repoRoot, Path worktreePath, String branch) {
        if (repoRoot == null || worktreePath == null) {
            return;
        }
        String repo = repoRoot.toString();
        String wt = worktreePath.toString();
        runGit(new String[]{"git", "-C", repo, "worktree", "remove", "--force", wt});
        if (branch != null && !branch.isEmpty()) {
            runGit(new String[]{"git", "-C", repo, "branch", "-D", branch});
        }
    }

    private static boolean runGit(String[] command) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (InputStream in = p.getInputStream()) {
                in.readAllBytes();
            }
            int code = p.waitFor();
            return code == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
