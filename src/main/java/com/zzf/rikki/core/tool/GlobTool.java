package com.zzf.rikki.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.rikki.project.ProjectContext;
import com.zzf.rikki.shell.ShellService;
import com.zzf.rikki.shell.ShellService.ExecuteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class GlobTool implements Tool {

    private final ProjectContext projectContext;
    private final ObjectMapper objectMapper;
    private final ShellService shellService;
    private final ResourceLoader resourceLoader;

    @Override
    public String getId() {
        return "glob";
    }

    @Override
    public String getDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/glob.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load glob tool description", e);
        }
        return "Fast file pattern matching tool.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");

        properties.putObject("pattern").put("type", "string")
                .put("description", "The glob pattern to match files against");
        properties.putObject("path").put("type", "string")
                .put("description", "The directory to search in. If not specified, current working directory is used.");

        schema.putArray("required").add("pattern");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String pattern = args.get("pattern").asText();
                String searchPathStr = args.has("path")
                        ? args.get("path").asText()
                        : ToolPathResolver.resolveWorkspaceRoot(projectContext, ctx);
                Path searchPath = ToolPathResolver.resolvePath(projectContext, ctx, searchPathStr);

                List<Map<String, Object>> files = globWithRipgrep(searchPath, pattern);
                if (files.isEmpty()) {
                    files = globFallback(searchPath, pattern);
                }

                files.sort((a, b) -> Long.compare((long) b.get("mtime"), (long) a.get("mtime")));
                int limit = 100;
                boolean truncated = files.size() > limit;
                List<Map<String, Object>> finalFiles = truncated ? files.subList(0, limit) : files;

                StringBuilder output = new StringBuilder();
                if (finalFiles.isEmpty()) {
                    output.append("No files found");
                } else {
                    for (Map<String, Object> file : finalFiles) {
                        output.append(file.get("path")).append("\n");
                    }
                    if (truncated) {
                        output.append("\n(Results are truncated. Consider using a more specific path or pattern.)");
                    }
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("count", finalFiles.size());
                metadata.put("truncated", truncated);

                return Result.builder()
                        .title(ToolPathResolver.safeRelativePath(projectContext, ctx, searchPath))
                        .output(output.toString().trim())
                        .metadata(metadata)
                        .build();
            } catch (Exception e) {
                log.error("Failed to execute glob tool", e);
                throw new RuntimeException(e.getMessage());
            }
        });
    }

    private List<Map<String, Object>> globWithRipgrep(Path searchPath, String pattern) {
        List<String> cmdParts = new ArrayList<>();
        cmdParts.add("rg");
        cmdParts.add("--files");
        cmdParts.add("--hidden");
        cmdParts.add("--no-ignore");
        cmdParts.add("--no-ignore-vcs");
        cmdParts.add("--glob");
        cmdParts.add("\"" + pattern + "\"");
        cmdParts.add("\"" + searchPath + "\"");

        ExecuteResult result = shellService.execute(String.join(" ", cmdParts), null, null);
        if (result.getExitCode() != 0 && result.text().trim().isEmpty()) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> files = new ArrayList<>();
        String[] lines = result.text().trim().split("\n");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Path resolved = ToolPathResolver.resolveAgainst(searchPath, line);
            if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                continue;
            }
            Map<String, Object> f = new HashMap<>();
            f.put("path", resolved.toString());
            try {
                f.put("mtime", Files.getLastModifiedTime(resolved).toMillis());
            } catch (IOException ignored) {
                f.put("mtime", 0L);
            }
            files.add(f);
        }
        return files;
    }

    private List<Map<String, Object>> globFallback(Path searchPath, String pattern) {
        List<Map<String, Object>> files = new ArrayList<>();
        java.nio.file.PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        try (Stream<Path> walk = Files.walk(searchPath)) {
            walk.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .forEach(path -> {
                        Path relative = searchPath.relativize(path);
                        if (!matcher.matches(relative) && !matcher.matches(relative.getFileName())) {
                            return;
                        }
                        Map<String, Object> f = new HashMap<>();
                        f.put("path", path.toString());
                        try {
                            f.put("mtime", Files.getLastModifiedTime(path).toMillis());
                        } catch (IOException ignored) {
                            f.put("mtime", 0L);
                        }
                        files.add(f);
                    });
        } catch (Exception e) {
            log.warn("Glob fallback failed for {}: {}", searchPath, e.getMessage());
        }
        return files;
    }
}
