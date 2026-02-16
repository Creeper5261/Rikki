package com.zzf.codeagent.session;

import com.zzf.codeagent.config.ConfigManager;
import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.session.model.MessageV2;
import com.zzf.codeagent.session.model.PromptPart;
import com.zzf.codeagent.util.FilesystemUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class InstructionPrompt {

    private final ConfigManager configManager;
    private final ProjectContext projectContext;
    private final FilesystemUtil filesystemUtil;

    private static final List<String> FILES = List.of(
            "AGENTS.md",
            "CLAUDE.md",
            "CONTEXT.md"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private List<String> globalFiles() {
        List<String> files = new ArrayList<>();

        String configDir = System.getenv("OPENCODE_CONFIG_DIR");
        if (configDir != null && !configDir.isBlank()) {
            files.add(Paths.get(configDir, "AGENTS.md").toString());
        }

        String disableClaude = System.getenv("OPENCODE_DISABLE_CLAUDE_CODE_PROMPT");
        if (!"true".equalsIgnoreCase(disableClaude)) {
            String home = System.getProperty("user.home");
            files.add(Paths.get(home, ".claude", "CLAUDE.md").toString());
        }

        return files;
    }

    private List<String> resolveRelative(String instruction, String directory, String worktree) {
        String disableProjectConfig = System.getenv("OPENCODE_DISABLE_PROJECT_CONFIG");
        String configDir = System.getenv("OPENCODE_CONFIG_DIR");

        if (!"true".equalsIgnoreCase(disableProjectConfig)) {
            return filesystemUtil.globUp(instruction, directory, worktree);
        }

        if (configDir == null || configDir.isBlank()) {
            log.warn("Skipping relative instruction \"{}\" - no OPENCODE_CONFIG_DIR set while project config is disabled", instruction);
            return Collections.emptyList();
        }

        return filesystemUtil.globUp(instruction, configDir, configDir);
    }

    public CompletableFuture<Set<String>> systemPaths(String workspaceRoot) {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> paths = new LinkedHashSet<>();
            String disableProjectConfig = System.getenv("OPENCODE_DISABLE_PROJECT_CONFIG");
            String directory = normalizeWorkspaceRoot(workspaceRoot);
            String worktree = directory;

            if (!"true".equalsIgnoreCase(disableProjectConfig)) {
                for (String filename : FILES) {
                    List<String> found = filesystemUtil.findUp(filename, directory, worktree);
                    if (!found.isEmpty()) {
                        paths.addAll(found);
                        break;
                    }
                }
            }

            for (String file : globalFiles()) {
                if (filesystemUtil.exists(file)) {
                    paths.add(Paths.get(file).toAbsolutePath().toString());
                    break;
                }
            }

            List<String> configInstructions = configManager.getConfig().getInstructions();
            if (configInstructions != null) {
                for (String instruction : configInstructions) {
                    if (instruction.startsWith("http://") || instruction.startsWith("https://")) {
                        continue;
                    }

                    String resolvedInstruction = instruction;
                    if (instruction.startsWith("~/")) {
                        resolvedInstruction = Paths.get(System.getProperty("user.home"), instruction.substring(2)).toString();
                    }

                    if (Paths.get(resolvedInstruction).isAbsolute()) {
                        Path absPath = Paths.get(resolvedInstruction);
                        Path parent = absPath.getParent();
                        if (parent != null) {
                            paths.addAll(filesystemUtil.glob(parent.toString(), absPath.getFileName().toString()));
                        }
                    } else {
                        paths.addAll(resolveRelative(resolvedInstruction, directory, worktree));
                    }
                }
            }

            return paths;
        });
    }

    public CompletableFuture<Set<String>> systemPaths() {
        return systemPaths(projectContext.getDirectory());
    }

    public CompletableFuture<List<String>> system(String workspaceRoot) {
        return systemPaths(workspaceRoot).thenCompose(paths -> {
            List<CompletableFuture<String>> futures = new ArrayList<>();

            for (String path : paths) {
                futures.add(readContent(path).thenApply(content ->
                        content != null ? "Instructions from: " + path + "\n" + content : ""));
            }

            List<String> configInstructions = configManager.getConfig().getInstructions();
            if (configInstructions != null) {
                for (String instruction : configInstructions) {
                    if (instruction.startsWith("http://") || instruction.startsWith("https://")) {
                        futures.add(fetchUrl(instruction).thenApply(content ->
                                content != null ? "Instructions from: " + instruction + "\n" + content : ""));
                    }
                }
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream()
                            .map(CompletableFuture::join)
                            .filter(s -> s != null && !s.isEmpty())
                            .collect(Collectors.toList()));
        });
    }

    public CompletableFuture<List<String>> system() {
        return system(projectContext.getDirectory());
    }

    private CompletableFuture<String> readContent(String path) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return Files.readString(Paths.get(path));
            } catch (IOException e) {
                return null;
            }
        });
    }

    private CompletableFuture<String> fetchUrl(String url) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 ? response.body() : null;
            } catch (Exception e) {
                return null;
            }
        });
    }

    public Set<String> loaded(List<MessageV2.WithParts> messages) {
        Set<String> paths = new HashSet<>();
        for (MessageV2.WithParts msg : messages) {
            for (PromptPart part : msg.getParts()) {
                if (!(part instanceof MessageV2.ToolPart)) {
                    continue;
                }
                MessageV2.ToolPart toolPart = (MessageV2.ToolPart) part;
                if (!"read".equals(toolPart.getTool())
                        || toolPart.getState() == null
                        || !"completed".equals(toolPart.getState().getStatus())) {
                    continue;
                }

                if (toolPart.getState().getTime() != null
                        && Boolean.TRUE.equals(toolPart.getState().getTime().getCompacted())) {
                    continue;
                }

                Map<String, Object> metadata = toolPart.getState().getMetadata();
                if (metadata == null || !metadata.containsKey("loaded")) {
                    continue;
                }
                Object loadedObj = metadata.get("loaded");
                if (!(loadedObj instanceof List<?>)) {
                    continue;
                }
                List<?> loadedList = (List<?>) loadedObj;
                for (Object item : loadedList) {
                    if (item instanceof String) {
                        paths.add((String) item);
                    }
                }
            }
        }
        return paths;
    }

    public CompletableFuture<String> find(String dir) {
        return CompletableFuture.supplyAsync(() -> {
            for (String file : FILES) {
                Path p = Paths.get(dir, file);
                if (Files.exists(p)) {
                    return p.toAbsolutePath().toString();
                }
            }
            return null;
        });
    }

    public CompletableFuture<List<InstructionResult>> resolve(List<MessageV2.WithParts> messages,
                                                              String filepath,
                                                              String workspaceRoot) {
        return systemPaths(workspaceRoot).thenCompose(system -> {
            Set<String> already = loaded(messages);
            Path current = Paths.get(filepath).toAbsolutePath().getParent();
            Path root = Paths.get(normalizeWorkspaceRoot(workspaceRoot)).toAbsolutePath();

            if (current == null || !current.startsWith(root)) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            return resolveRecursive(current, root, system, already, new ArrayList<>());
        });
    }

    public CompletableFuture<List<InstructionResult>> resolve(List<MessageV2.WithParts> messages, String filepath) {
        return resolve(messages, filepath, projectContext.getDirectory());
    }

    private CompletableFuture<List<InstructionResult>> resolveRecursive(Path current,
                                                                        Path root,
                                                                        Set<String> system,
                                                                        Set<String> already,
                                                                        List<InstructionResult> results) {
        return find(current.toString()).thenCompose(found -> {
            CompletableFuture<Void> nextStep;
            if (found != null && !system.contains(found) && !already.contains(found)) {
                nextStep = readContent(found).thenAccept(content -> {
                    if (content != null) {
                        results.add(new InstructionResult(found, "Instructions from: " + found + "\n" + content));
                    }
                });
            } else {
                nextStep = CompletableFuture.completedFuture(null);
            }

            return nextStep.thenCompose(v -> {
                if (current.equals(root)) {
                    return CompletableFuture.completedFuture(results);
                }
                Path parent = current.getParent();
                if (parent == null || !parent.startsWith(root)) {
                    return CompletableFuture.completedFuture(results);
                }
                return resolveRecursive(parent, root, system, already, results);
            });
        });
    }

    private String normalizeWorkspaceRoot(String workspaceRoot) {
        String candidate = workspaceRoot;
        if (candidate == null || candidate.isBlank()) {
            candidate = projectContext.getDirectory();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = System.getProperty("user.dir");
        }
        return Paths.get(candidate).toAbsolutePath().normalize().toString();
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class InstructionResult {
        private String filepath;
        private String content;
    }
}
