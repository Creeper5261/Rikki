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
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 指令解析器 (对齐 OpenCode InstructionPrompt)
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class InstructionPrompt {

    private final ConfigManager configManager;
    private final ProjectContext projectContext;
    private final FilesystemUtil filesystemUtil;

    // 常见的指令文件名
    private static final List<String> FILES = List.of(
            "AGENTS.md",
            "CLAUDE.md",
            "CONTEXT.md"
    );

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * 获取全局指令文件
     */
    private List<String> globalFiles() {
        List<String> files = new ArrayList<>();
        
        // 1. Config Dir AGENTS.md
        String configDir = System.getenv("OPENCODE_CONFIG_DIR");
        if (configDir != null) {
            files.add(Paths.get(configDir, "AGENTS.md").toString());
        } else {
            // Default config dir if not set (mirroring Global.Path.config logic if needed)
            // For now, follow TS logic strictly: path.join(Global.Path.config, "AGENTS.md")
            // Assuming we have a way to get global config path, but let's stick to env for now.
        }
        
        // 2. ~/.claude/CLAUDE.md (unless disabled)
        String disableClaude = System.getenv("OPENCODE_DISABLE_CLAUDE_CODE_PROMPT");
        if (!"true".equalsIgnoreCase(disableClaude)) {
            String home = System.getProperty("user.home");
            files.add(Paths.get(home, ".claude", "CLAUDE.md").toString());
        }
        
        return files;
    }

    /**
     * 解析相对路径指令
     */
    private List<String> resolveRelative(String instruction) {
        String disableProjectConfig = System.getenv("OPENCODE_DISABLE_PROJECT_CONFIG");
        String configDir = System.getenv("OPENCODE_CONFIG_DIR");

        if (!"true".equalsIgnoreCase(disableProjectConfig)) {
            return filesystemUtil.globUp(instruction, projectContext.getDirectory(), projectContext.getWorktree());
        }

        if (configDir == null) {
            log.warn("Skipping relative instruction \"{}\" - no OPENCODE_CONFIG_DIR set while project config is disabled", instruction);
            return Collections.emptyList();
        }

        return filesystemUtil.globUp(instruction, configDir, configDir);
    }

    /**
     * 获取所有生效的指令路径 (本地文件或 URL)
     */
    public CompletableFuture<Set<String>> systemPaths() {
        return CompletableFuture.supplyAsync(() -> {
            Set<String> paths = new LinkedHashSet<>();
            String disableProjectConfig = System.getenv("OPENCODE_DISABLE_PROJECT_CONFIG");

            // 1. 查找项目目录向上直到工作树根目录的指令文件
            if (!"true".equalsIgnoreCase(disableProjectConfig)) {
                for (String filename : FILES) {
                    List<String> found = filesystemUtil.findUp(filename, projectContext.getDirectory(), projectContext.getWorktree());
                    if (!found.isEmpty()) {
                        paths.addAll(found);
                        break; // OpenCode: matches.length > 0 break inner loop? 
                        // OpenCode source: 
                        // for (const file of FILES) { 
                        //   const matches = await findUp... 
                        //   if (matches.length > 0) { matches.forEach... break } 
                        // }
                        // So yes, break after finding first type of file.
                    }
                }
            }

            // 2. Global files
            for (String file : globalFiles()) {
                if (filesystemUtil.exists(file)) {
                    paths.add(Paths.get(file).toAbsolutePath().toString());
                    break;
                }
            }

            // 3. Config instructions
            List<String> configInstructions = configManager.getConfig().getInstructions();
            if (configInstructions != null) {
                for (String instruction : configInstructions) {
                    if (instruction.startsWith("http://") || instruction.startsWith("https://")) {
                        continue; // Handled in system()
                    }
                    
                    String resolvedInstruction = instruction;
                    if (instruction.startsWith("~/")) {
                        resolvedInstruction = Paths.get(System.getProperty("user.home"), instruction.substring(2)).toString();
                    }

                    if (Paths.get(resolvedInstruction).isAbsolute()) {
                        Path absPath = Paths.get(resolvedInstruction);
                        paths.addAll(filesystemUtil.glob(absPath.getParent().toString(), absPath.getFileName().toString()));
                    } else {
                        paths.addAll(resolveRelative(resolvedInstruction));
                    }
                }
            }

            return paths;
        });
    }

    /**
     * 加载所有指令内容
     */
    public CompletableFuture<List<String>> system() {
        return systemPaths().thenCompose(paths -> {
            List<CompletableFuture<String>> futures = new ArrayList<>();

            // Files
            for (String path : paths) {
                futures.add(readContent(path).thenApply(content -> 
                    content != null ? "Instructions from: " + path + "\n" + content : ""
                ));
            }

            // URLs from config
            List<String> configInstructions = configManager.getConfig().getInstructions();
            if (configInstructions != null) {
                for (String instruction : configInstructions) {
                    if (instruction.startsWith("http://") || instruction.startsWith("https://")) {
                        futures.add(fetchUrl(instruction).thenApply(content -> 
                            content != null ? "Instructions from: " + instruction + "\n" + content : ""
                        ));
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

    /**
     * Check if instructions are already loaded in messages (via read tool)
     */
    public Set<String> loaded(List<MessageV2.WithParts> messages) {
        Set<String> paths = new HashSet<>();
        for (MessageV2.WithParts msg : messages) {
            for (PromptPart part : msg.getParts()) {
                if (part instanceof MessageV2.ToolPart) {
                    MessageV2.ToolPart toolPart = (MessageV2.ToolPart) part;
                    if ("read".equals(toolPart.getTool()) && 
                        toolPart.getState() != null && 
                        "completed".equals(toolPart.getState().getStatus())) {
                        
                        // Check if compacted
                        if (toolPart.getState().getTime() != null && 
                            Boolean.TRUE.equals(toolPart.getState().getTime().getCompacted())) {
                            continue;
                        }
                        
                        // Check metadata loaded
                        Map<String, Object> metadata = toolPart.getState().getMetadata();
                        if (metadata != null && metadata.containsKey("loaded")) {
                            Object loadedObj = metadata.get("loaded");
                            if (loadedObj instanceof List) {
                                List<?> loadedList = (List<?>) loadedObj;
                                for (Object item : loadedList) {
                                    if (item instanceof String) {
                                        paths.add((String) item);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return paths;
    }

    /**
     * Find instruction file in specific directory
     */
    public CompletableFuture<String> find(String dir) {
        return CompletableFuture.supplyAsync(() -> {
            for (String file : FILES) {
                Path p = Paths.get(dir, file);
                if (Files.exists(p)) return p.toAbsolutePath().toString();
            }
            return null;
        });
    }

    /**
     * Resolve instructions relative to a file being worked on
     */
    public CompletableFuture<List<InstructionResult>> resolve(List<MessageV2.WithParts> messages, String filepath) {
        return systemPaths().thenCompose(system -> {
            Set<String> already = loaded(messages);
            
            Path current = Paths.get(filepath).toAbsolutePath().getParent();
            Path root = Paths.get(projectContext.getDirectory()).toAbsolutePath();
            
            if (current == null || !current.startsWith(root)) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            return resolveRecursive(current, root, system, already, new ArrayList<>());
        });
    }

    private CompletableFuture<List<InstructionResult>> resolveRecursive(Path current, Path root, Set<String> system, Set<String> already, List<InstructionResult> results) {
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
    
    @lombok.Data
    @lombok.AllArgsConstructor
    public static class InstructionResult {
        private String filepath;
        private String content;
    }
}
