package com.zzf.codeagent.session;

import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.provider.ModelInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 系统提示词生成 (对齐 OpenCode SystemPrompt)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPrompt {

    private final ProjectContext projectContext;
    private final ResourceLoader resourceLoader;

    public List<String> provider(ModelInfo model) {
        String modelId = model.getId().toLowerCase();
        String promptFile;

        if (modelId.contains("gpt-5")) {
            promptFile = "codex_header.txt";
        } else if (modelId.contains("gpt-") || modelId.contains("o1") || modelId.contains("o3")) {
            promptFile = "beast.txt";
        } else if (modelId.contains("gemini-")) {
            promptFile = "gemini.txt";
        } else if (modelId.contains("claude")) {
            promptFile = "anthropic.txt";
        } else {
            // Default to anthropic_without_todo (qwen.txt in OpenCode)
            promptFile = "qwen.txt";
        }

        String content = loadPrompt("session/" + promptFile);
        List<String> result = new ArrayList<>();
        if (content != null && !content.isEmpty()) {
            result.add(content);
        }
        return result;
    }

    public List<String> environment(ModelInfo model, String directory) {
        List<String> env = new ArrayList<>();
        String workspaceRoot = resolveWorkspaceRoot(directory);
        env.add("You are powered by the model named " + model.getId() + ". The exact model ID is " + model.getProviderID() + "/" + model.getId());
        env.add("Here is some useful information about the environment you are running in:");
        env.add("<env>");
        env.add("  Working directory: " + workspaceRoot);
        env.add("  Is directory a git repo: " + (isGitRepo(workspaceRoot) ? "yes" : "no"));
        env.add("  Platform: " + System.getProperty("os.name"));
        env.add("  Today's date: " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEE MMM dd yyyy")));
        env.add("</env>");
        env.add("Do not output the contents of the <env> block in your response. It is for your information only.");
        env.add("<files>");
        env.addAll(buildWorkspaceFileIndex(workspaceRoot));
        env.add("</files>");
        env.add("All files under the working directory are available to tools. Use read/glob/grep to inspect concrete contents when needed.");
        
        return List.of(String.join("\n", env));
    }

    private String resolveWorkspaceRoot(String directory) {
        String raw = directory;
        if (raw == null || raw.isBlank()) {
            raw = projectContext.getDirectory();
        }
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("user.dir");
        }
        return Paths.get(raw).toAbsolutePath().normalize().toString();
    }

    private boolean isGitRepo(String directory) {
        try {
            return Files.exists(Paths.get(directory).resolve(".git"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private List<String> buildWorkspaceFileIndex(String workspaceRoot) {
        Path root = Paths.get(workspaceRoot).toAbsolutePath().normalize();
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return List.of();
        }

        int limit = Integer.getInteger("codeagent.prompt.fileListLimit", 200);
        List<String> result = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !path.startsWith(root.resolve(".git")))
                    .sorted(Comparator.comparing(Path::toString))
                    .collect(Collectors.toList());

            int end = Math.min(files.size(), Math.max(limit, 0));
            for (int i = 0; i < end; i++) {
                Path relative = root.relativize(files.get(i));
                result.add("  " + relative.toString().replace('\\', '/'));
            }
            if (files.size() > end) {
                result.add("  ... (+" + (files.size() - end) + " more files omitted)");
            }
        } catch (IOException e) {
            log.debug("Failed to build workspace file index for {}", workspaceRoot, e);
        }

        if (result.isEmpty()) {
            result.add("  (no files indexed)");
        }
        return result;
    }

    private String loadPrompt(String path) {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/" + path);
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            } else {
                log.warn("Prompt resource not found: {}", path);
            }
        } catch (IOException e) {
            log.error("Failed to load prompt resource: {}", path, e);
        }
        return null;
    }
}
