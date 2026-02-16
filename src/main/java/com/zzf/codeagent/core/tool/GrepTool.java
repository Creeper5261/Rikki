package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import com.zzf.codeagent.shell.ShellService;
import com.zzf.codeagent.shell.ShellService.ExecuteResult;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class GrepTool implements Tool {

    private final ProjectContext projectContext;
    private final ObjectMapper objectMapper;
    private final ShellService shellService;
    private final ResourceLoader resourceLoader;

    @Override
    public String getId() {
        return "grep";
    }

    @Override
    public String getDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/grep.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load grep tool description", e);
        }
        return "Fast content search tool.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("pattern").put("type", "string")
                .put("description", "The regex pattern to search for in file contents");
        properties.putObject("path").put("type", "string")
                .put("description", "The directory to search in. Defaults to the current working directory.");
        properties.putObject("include").put("type", "string")
                .put("description", "File pattern to include in the search.");
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
                String include = args.has("include") ? args.get("include").asText() : null;
                Path searchPath = ToolPathResolver.resolvePath(projectContext, ctx, searchPathStr);

                List<Map<String, Object>> matches = grepWithRipgrep(searchPath, pattern, include);
                if (matches.isEmpty()) {
                    matches = grepFallback(searchPath, pattern, include);
                }

                matches.sort((a, b) -> Long.compare((long) b.get("modTime"), (long) a.get("modTime")));
                int limit = 100;
                boolean truncated = matches.size() > limit;
                List<Map<String, Object>> finalMatches = truncated ? matches.subList(0, limit) : matches;

                StringBuilder output = new StringBuilder();
                output.append("Found ").append(finalMatches.size()).append(" matches\n");

                String currentFile = "";
                for (Map<String, Object> match : finalMatches) {
                    String matchPath = (String) match.get("path");
                    if (!currentFile.equals(matchPath)) {
                        if (!currentFile.isEmpty()) {
                            output.append("\n");
                        }
                        currentFile = matchPath;
                        output.append(matchPath).append(":\n");
                    }
                    String lineText = String.valueOf(match.get("lineText"));
                    if (lineText.length() > 2000) {
                        lineText = lineText.substring(0, 2000) + "...";
                    }
                    output.append("  Line ").append(match.get("lineNum")).append(": ").append(lineText).append("\n");
                }

                if (truncated) {
                    output.append("\n(Results are truncated. Consider using a more specific path or pattern.)");
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("matches", finalMatches.size());
                metadata.put("truncated", truncated);

                return Result.builder()
                        .title(pattern)
                        .output(output.toString().trim())
                        .metadata(metadata)
                        .build();
            } catch (Exception e) {
                log.error("Failed to execute grep tool", e);
                throw new RuntimeException(e.getMessage());
            }
        });
    }

    private List<Map<String, Object>> grepWithRipgrep(Path searchPath, String pattern, String include) {
        List<String> cmdParts = new ArrayList<>();
        cmdParts.add("rg");
        cmdParts.add("-nH");
        cmdParts.add("--hidden");
        cmdParts.add("--follow");
        cmdParts.add("--no-ignore");
        cmdParts.add("--no-ignore-vcs");
        cmdParts.add("--no-messages");
        cmdParts.add("--field-match-separator=|");
        cmdParts.add("--regexp");
        cmdParts.add(pattern);
        if (include != null && !include.isBlank()) {
            cmdParts.add("--glob");
            cmdParts.add(include);
        }
        cmdParts.add(searchPath.toString());

        ExecuteResult result = shellService.execute(String.join(" ", cmdParts), null, null);
        if (result.getExitCode() == 1 || (result.getExitCode() != 0 && result.text().trim().isEmpty())) {
            return new ArrayList<>();
        }

        List<Map<String, Object>> matches = new ArrayList<>();
        String[] lines = result.text().trim().split("\n");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|");
            if (parts.length < 3) {
                continue;
            }
            String filePath = parts[0];
            String lineNumStr = parts[1];
            String lineText = line.substring(filePath.length() + lineNumStr.length() + 2);

            Path resolvedPath = ToolPathResolver.resolveAgainst(searchPath, filePath);
            if (!Files.exists(resolvedPath) || !Files.isRegularFile(resolvedPath)) {
                continue;
            }

            Map<String, Object> match = new HashMap<>();
            match.put("path", resolvedPath.toString());
            match.put("lineNum", Integer.parseInt(lineNumStr));
            match.put("lineText", lineText);
            try {
                match.put("modTime", Files.getLastModifiedTime(resolvedPath).toMillis());
            } catch (IOException ignored) {
                match.put("modTime", 0L);
            }
            matches.add(match);
        }
        return matches;
    }

    private List<Map<String, Object>> grepFallback(Path searchPath, String regex, String include) {
        List<Map<String, Object>> matches = new ArrayList<>();
        Pattern pattern = Pattern.compile(regex);
        java.nio.file.PathMatcher includeMatcher = null;
        if (include != null && !include.isBlank()) {
            includeMatcher = FileSystems.getDefault().getPathMatcher("glob:" + include);
        }

        try (Stream<Path> walk = Files.walk(searchPath)) {
            List<Path> files = walk.filter(Files::isRegularFile).collect(Collectors.toList());
            for (Path file : files) {
                Path relative = searchPath.relativize(file);
                if (includeMatcher != null
                        && !includeMatcher.matches(relative)
                        && !includeMatcher.matches(relative.getFileName())) {
                    continue;
                }
                if (isLikelyBinary(file)) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (!pattern.matcher(line).find()) {
                        continue;
                    }
                    Map<String, Object> match = new HashMap<>();
                    match.put("path", file.toString());
                    match.put("lineNum", i + 1);
                    match.put("lineText", line);
                    try {
                        match.put("modTime", Files.getLastModifiedTime(file).toMillis());
                    } catch (IOException ignored) {
                        match.put("modTime", 0L);
                    }
                    matches.add(match);
                    if (matches.size() >= 500) {
                        return matches;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Grep fallback failed for {}: {}", searchPath, e.getMessage());
        }
        return matches;
    }

    private boolean isLikelyBinary(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".jar")
                || name.endsWith(".class")
                || name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".gif")
                || name.endsWith(".pdf")
                || name.endsWith(".zip")
                || name.endsWith(".exe")
                || name.endsWith(".dll");
    }
}
