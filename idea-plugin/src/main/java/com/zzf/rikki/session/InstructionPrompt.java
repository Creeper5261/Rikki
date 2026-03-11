package com.zzf.rikki.session;

import com.zzf.rikki.session.model.MessageV2;
import com.zzf.rikki.session.model.PromptPart;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class InstructionPrompt {
    private static final List<String> FILES = List.of("AGENTS.md", "CLAUDE.md", "CONTEXT.md");

    public List<String> system(String workspaceRoot) {
        List<String> result = new ArrayList<>();
        for (String path : systemPaths(workspaceRoot)) {
            String content = readContent(Path.of(path));
            if (content != null && !content.isBlank()) {
                result.add("Instructions from: " + path + "\n" + content);
            }
        }
        return result;
    }

    public Set<String> systemPaths(String workspaceRoot) {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        Path directory = normalizeWorkspaceRoot(workspaceRoot);
        if (directory == null) {
            return paths;
        }

        if (!"true".equalsIgnoreCase(System.getenv("OPENCODE_DISABLE_PROJECT_CONFIG"))) {
            for (String filename : FILES) {
                Path found = findUp(directory, filename);
                if (found != null) {
                    paths.add(found.toAbsolutePath().normalize().toString());
                    break;
                }
            }
        }

        for (String file : globalFiles()) {
            Path candidate = Path.of(file).toAbsolutePath().normalize();
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                paths.add(candidate.toString());
                break;
            }
        }
        return paths;
    }

    public Set<String> loaded(List<MessageV2.WithParts> messages) {
        Set<String> paths = new HashSet<>();
        if (messages == null) {
            return paths;
        }
        for (MessageV2.WithParts message : messages) {
            if (message == null || message.parts == null) {
                continue;
            }
            for (PromptPart part : message.parts) {
                if (!(part instanceof MessageV2.ToolPart toolPart)) {
                    continue;
                }
                if (!"read".equals(toolPart.tool) || toolPart.state == null || !"completed".equals(toolPart.state.status)) {
                    continue;
                }
                if (toolPart.state.time != null && Boolean.TRUE.equals(toolPart.state.time.compacted)) {
                    continue;
                }
                Object loaded = toolPart.state.metadata.get("loaded");
                if (!(loaded instanceof List<?> loadedList)) {
                    continue;
                }
                for (Object item : loadedList) {
                    if (item instanceof String path && !path.isBlank()) {
                        paths.add(Path.of(path).toAbsolutePath().normalize().toString());
                    }
                }
            }
        }
        return paths;
    }

    public String find(String dir) {
        Path base = normalizeWorkspaceRoot(dir);
        if (base == null) {
            return null;
        }
        for (String file : FILES) {
            Path candidate = base.resolve(file);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize().toString();
            }
        }
        return null;
    }

    public List<InstructionResult> resolve(List<MessageV2.WithParts> messages, String filepath, String workspaceRoot) {
        Path file = safeAbsolutePath(filepath);
        Path root = normalizeWorkspaceRoot(workspaceRoot);
        if (file == null || root == null) {
            return List.of();
        }
        Path current = Files.isDirectory(file) ? file : file.getParent();
        if (current == null || !current.startsWith(root)) {
            return List.of();
        }

        Set<String> system = systemPaths(workspaceRoot);
        Set<String> already = loaded(messages);
        List<InstructionResult> resolved = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        while (current != null && current.startsWith(root)) {
            for (String filename : FILES) {
                Path candidate = current.resolve(filename).toAbsolutePath().normalize();
                String candidatePath = candidate.toString();
                if (!Files.exists(candidate) || !Files.isRegularFile(candidate)) {
                    continue;
                }
                if (system.contains(candidatePath) || already.contains(candidatePath) || !seen.add(candidatePath)) {
                    continue;
                }
                String content = readContent(candidate);
                if (content != null && !content.isBlank()) {
                    resolved.add(new InstructionResult(candidatePath, content));
                }
            }
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
        return resolved;
    }

    private List<String> globalFiles() {
        List<String> files = new ArrayList<>();
        String configDir = System.getenv("OPENCODE_CONFIG_DIR");
        if (configDir != null && !configDir.isBlank()) {
            files.add(Paths.get(configDir, "AGENTS.md").toString());
        }
        if (!"true".equalsIgnoreCase(System.getenv("OPENCODE_DISABLE_CLAUDE_CODE_PROMPT"))) {
            files.add(Paths.get(System.getProperty("user.home"), ".claude", "CLAUDE.md").toString());
        }
        return files;
    }

    private Path findUp(Path start, String filename) {
        Path current = start;
        while (current != null) {
            Path candidate = current.resolve(filename);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            current = current.getParent();
        }
        return null;
    }

    private Path normalizeWorkspaceRoot(String workspaceRoot) {
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            return null;
        }
        try {
            Path base = Path.of(workspaceRoot).toAbsolutePath().normalize();
            return Files.isDirectory(base) ? base : base.getParent();
        } catch (Exception ignored) {
            return null;
        }
    }

    private Path safeAbsolutePath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            return Path.of(path).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readContent(Path path) {
        try {
            return Files.readString(path).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    public static final class InstructionResult {
        public final String path;
        public final String content;

        public InstructionResult(String path, String content) {
            this.path = path;
            this.content = content;
        }
    }
}