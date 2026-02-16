package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Component
@RequiredArgsConstructor
public class ListTool implements Tool {

    private final ProjectContext projectContext;
    private final ObjectMapper objectMapper;
    private final ShellService shellService;
    private final ResourceLoader resourceLoader;

    private static final int LIMIT = 500;

    @Override
    public String getId() {
        return "ls";
    }

    @Override
    public String getDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/ls.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load ls tool description", e);
        }
        return "Lists files and directories in a given path.";
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("path")
                .put("type", "string")
                .put("description", "The absolute path to the directory to list (must be absolute, not relative)");
        properties.putObject("ignore")
                .put("type", "array")
                .set("items", objectMapper.createObjectNode().put("type", "string"));
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String searchPathStr = args.has("path")
                        ? args.get("path").asText()
                        : ToolPathResolver.resolveWorkspaceRoot(projectContext, ctx);
                List<String> userIgnore = new ArrayList<>();
                if (args.has("ignore") && args.get("ignore").isArray()) {
                    ArrayNode ignoreArray = (ArrayNode) args.get("ignore");
                    for (JsonNode node : ignoreArray) {
                        userIgnore.add(node.asText());
                    }
                }

                Path searchPath = ToolPathResolver.resolvePath(projectContext, ctx, searchPathStr);
                List<String> files = listWithRipgrep(searchPath, userIgnore);
                if (files.isEmpty()) {
                    files = listFilesFallback(searchPath, LIMIT);
                }

                Set<String> dirs = new HashSet<>();
                Map<String, List<String>> filesByDir = new HashMap<>();

                for (String filePathStr : files) {
                    Path filePath = ToolPathResolver.resolveAgainst(searchPath, filePathStr);
                    Path relativePath;
                    try {
                        relativePath = searchPath.relativize(filePath);
                    } catch (Exception e) {
                        relativePath = filePath.getFileName() != null
                                ? Paths.get(filePath.getFileName().toString())
                                : filePath;
                    }
                    Path parent = relativePath.getParent();
                    String dirKey = parent == null ? "." : parent.toString().replace("\\", "/");

                    String[] parts = ".".equals(dirKey) ? new String[0] : dirKey.split("/");
                    for (int i = 0; i <= parts.length; i++) {
                        String dirPath = i == 0 ? "." : String.join("/", java.util.Arrays.copyOfRange(parts, 0, i));
                        dirs.add(dirPath);
                    }

                    filesByDir.computeIfAbsent(dirKey, k -> new ArrayList<>()).add(filePath.getFileName().toString());
                }

                StringBuilder output = new StringBuilder();
                output.append(searchPath.toString().replace("\\", "/")).append("/\n");
                output.append(renderDir(".", 0, dirs, filesByDir));

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("count", files.size());
                metadata.put("truncated", files.size() >= LIMIT);

                Map<String, Object> fileView = new HashMap<>();
                fileView.put("type", "directory");
                fileView.put("path", searchPath.toString());
                fileView.put("items", files);
                metadata.put("file_view", fileView);

                return Result.builder()
                        .title(ToolPathResolver.safeRelativePath(projectContext, ctx, searchPath))
                        .output(output.toString().trim())
                        .metadata(metadata)
                        .build();
            } catch (Exception e) {
                log.error("Failed to execute list tool", e);
                throw new RuntimeException(e.getMessage());
            }
        });
    }

    private List<String> listWithRipgrep(Path searchPath, List<String> userIgnore) {
        List<String> cmdParts = new ArrayList<>();
        cmdParts.add("rg");
        cmdParts.add("--files");
        cmdParts.add("--hidden");
        cmdParts.add("--no-ignore");
        cmdParts.add("--no-ignore-vcs");
        for (String p : userIgnore) {
            cmdParts.add("--glob");
            cmdParts.add("\"!" + p + "\"");
        }
        cmdParts.add("\"" + searchPath + "\"");

        ExecuteResult result = shellService.execute(String.join(" ", cmdParts), null, null);
        if (result.getExitCode() != 0 && result.text().trim().isEmpty()) {
            return Collections.emptyList();
        }

        List<String> files = new ArrayList<>();
        String[] lines = result.text().trim().split("\n");
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            files.add(line);
            if (files.size() >= LIMIT) {
                break;
            }
        }
        return files;
    }

    private List<String> listFilesFallback(Path searchPath, int limit) {
        List<String> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(searchPath)) {
            walk.filter(Files::isRegularFile)
                    .sorted()
                    .limit(limit)
                    .forEach(path -> files.add(path.toString()));
        } catch (Exception e) {
            log.warn("List fallback scan failed for {}: {}", searchPath, e.getMessage());
        }
        return files;
    }

    private String renderDir(String dirPath, int depth, Set<String> dirs, Map<String, List<String>> filesByDir) {
        StringBuilder sb = new StringBuilder();
        String indent = "  ".repeat(depth);

        if (depth > 0) {
            sb.append(indent).append(Paths.get(dirPath).getFileName().toString()).append("/\n");
        }

        String childIndent = "  ".repeat(depth + 1);
        List<String> children = dirs.stream()
                .filter(d -> {
                    if (d.equals(dirPath)) {
                        return false;
                    }
                    String parent = d.contains("/") ? d.substring(0, d.lastIndexOf("/")) : ".";
                    return parent.equals(dirPath);
                })
                .sorted()
                .collect(Collectors.toList());

        for (String child : children) {
            sb.append(renderDir(child, depth + 1, dirs, filesByDir));
        }

        List<String> files = filesByDir.getOrDefault(dirPath, new ArrayList<>());
        Collections.sort(files);
        for (String file : files) {
            sb.append(childIndent).append(file).append("\n");
        }

        return sb.toString();
    }
}
