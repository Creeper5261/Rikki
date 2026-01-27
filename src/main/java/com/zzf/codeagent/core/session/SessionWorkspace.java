package com.zzf.codeagent.core.session;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;

public final class SessionWorkspace {
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

    private SessionWorkspace(Path realRoot, Path sessionRoot, boolean active) {
        this.realRoot = realRoot;
        this.sessionRoot = sessionRoot;
        this.active = active;
    }

    public static SessionWorkspace create(String workspaceRoot, String sessionId) {
        if (workspaceRoot == null || workspaceRoot.trim().isEmpty()) {
            return new SessionWorkspace(null, null, false);
        }
        Path real = Path.of(workspaceRoot.trim()).toAbsolutePath().normalize();
        Path tempBase = Path.of(System.getProperty("java.io.tmpdir"), "codeagent-sessions");
        String safeId = sessionId == null || sessionId.trim().isEmpty() ? "session" : sessionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path session = tempBase.resolve(safeId);
        try {
            if (Files.exists(session)) {
                deleteDirectory(session);
            }
            Files.createDirectories(session);
            copyDirectory(real, session);
            return new SessionWorkspace(real, session, true);
        } catch (Exception e) {
            return new SessionWorkspace(real, real, false);
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

    public void cleanup() {
        if (!active || sessionRoot == null) return;
        try {
            deleteDirectory(sessionRoot);
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
}
