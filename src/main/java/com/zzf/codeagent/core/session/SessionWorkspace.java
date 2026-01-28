package com.zzf.codeagent.core.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

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

    private static final String[] ADJECTIVES = {
            "brave", "calm", "clever", "cosmic", "crisp", "curious", "eager", "gentle", "glowing", "happy",
            "hidden", "jolly", "kind", "lucky", "mighty", "misty", "neon", "nimble", "playful", "proud",
            "quick", "quiet", "shiny", "silent", "stellar", "sunny", "swift", "tidy", "witty"
    };

    private static final String[] NOUNS = {
            "cabin", "cactus", "canyon", "circuit", "comet", "eagle", "engine", "falcon", "forest", "garden",
            "harbor", "island", "knight", "lagoon", "meadow", "moon", "mountain", "nebula", "orchid", "otter",
            "panda", "pixel", "planet", "river", "rocket", "sailor", "squid", "star", "tiger", "wizard", "wolf"
    };

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
            try {
                Path base = resolveWorktreeBase(real);
                Files.createDirectories(base);
                WorktreeCandidate candidate = pickCandidate(base, slug(fileName(real)), real);
                if (candidate != null && createWorktree(real, candidate.directory, candidate.branch)) {
                    return new SessionWorkspace(real, candidate.directory, true, Kind.WORKTREE, candidate.branch);
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

    private static String fileName(Path path) {
        if (path == null) return "";
        Path name = path.getFileName();
        return name == null ? "" : name.toString();
    }

    private static String slug(String input) {
        if (input == null) return "";
        String raw = input.trim().toLowerCase();
        String cleaned = raw.replaceAll("[^a-z0-9]+", "-");
        cleaned = cleaned.replaceAll("^-+", "").replaceAll("-+$", "");
        return cleaned;
    }

    private static String randomName() {
        return pick(ADJECTIVES) + "-" + pick(NOUNS);
    }

    private static String pick(String[] list) {
        if (list == null || list.length == 0) return "";
        int idx = ThreadLocalRandom.current().nextInt(list.length);
        return list[idx];
    }

    private static Path resolveWorktreeBase(Path repoRoot) {
        String hash = hashPath(repoRoot == null ? "" : repoRoot.toString());
        Path home = Path.of(System.getProperty("user.home"), ".codeagent", "worktree");
        return home.resolve(hash);
    }

    private static String hashPath(String input) {
        if (input == null) return "unknown";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    private static WorktreeCandidate pickCandidate(Path root, String base, Path repoRoot) throws IOException {
        String baseSlug = base == null ? "" : slug(base);
        for (int attempt = 0; attempt < 26; attempt++) {
            String name;
            if (!baseSlug.isEmpty()) {
                name = attempt == 0 ? baseSlug : baseSlug + "-" + randomName();
            } else {
                name = randomName();
            }
            String branch = "codeagent/" + name;
            Path directory = root.resolve(name);
            if (Files.exists(directory)) {
                continue;
            }
            if (gitBranchExists(repoRoot, branch)) {
                continue;
            }
            return new WorktreeCandidate(name, branch, directory);
        }
        return null;
    }

    private static boolean gitBranchExists(Path repoRoot, String branch) {
        if (repoRoot == null || branch == null || branch.isEmpty()) return false;
        return runGit(repoRoot, "show-ref", "--verify", "--quiet", "refs/heads/" + branch);
    }

    private static boolean createWorktree(Path repoRoot, Path worktreePath, String branch) {
        boolean added = runGit(repoRoot, "worktree", "add", "--no-checkout", "-b", branch, worktreePath.toString());
        if (!added) {
            return false;
        }
        return runGit(worktreePath, "reset", "--hard");
    }

    private static void removeWorktree(Path repoRoot, Path worktreePath, String branch) {
        if (repoRoot == null || worktreePath == null) {
            return;
        }
        runGit(repoRoot, "worktree", "remove", "--force", worktreePath.toString());
        if (branch != null && !branch.isEmpty()) {
            runGit(repoRoot, "branch", "-D", branch);
        }
    }

    private static boolean runGit(Path workdir, String... args) {
        if (workdir == null) return false;
        String[] command = new String[args.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = workdir.toString();
        System.arraycopy(args, 0, command, 3, args.length);
        return runCommand(command);
    }

    private static boolean runCommand(String[] command) {
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

    private static final class WorktreeCandidate {
        private final String name;
        private final String branch;
        private final Path directory;

        private WorktreeCandidate(String name, String branch, Path directory) {
            this.name = name;
            this.branch = branch;
            this.directory = directory;
        }
    }
}
