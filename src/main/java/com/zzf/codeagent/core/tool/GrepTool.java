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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GrepTool 实现 (对齐 opencode/src/tool/grep.ts)
 */
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
        return loadDescription();
    }

    private String loadDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/grep.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load grep tool description", e);
        }
        return "Fast content search tool."; // Fallback
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        
        properties.putObject("pattern").put("type", "string").put("description", "The regex pattern to search for in file contents");
        properties.putObject("path").put("type", "string").put("description", "The directory to search in. Defaults to the current working directory.");
        properties.putObject("include").put("type", "string").put("description", "File pattern to include in the search (e.g. \"*.js\", \"*.{ts,tsx}\")");

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

                // 准备 ripgrep 命令 (假设环境中已安装 rg)
                List<String> cmdParts = new ArrayList<>();
                cmdParts.add("rg");
                cmdParts.add("-nH");
                cmdParts.add("--hidden");
                cmdParts.add("--follow");
                cmdParts.add("--no-messages");
                cmdParts.add("--field-match-separator=|");
                cmdParts.add("--regexp");
                cmdParts.add(pattern);
                
                if (include != null && !include.isEmpty()) {
                    cmdParts.add("--glob");
                    cmdParts.add(include);
                }
                
                cmdParts.add(searchPath.toString());

                String command = String.join(" ", cmdParts);
                ExecuteResult result = shellService.execute(command, null, null);

                // Ripgrep exit codes: 0 = matches, 1 = no matches, 2 = errors
                if (result.getExitCode() == 1 || (result.getExitCode() == 2 && result.text().trim().isEmpty())) {
                    return Result.builder()
                            .title(pattern)
                            .output("No files found")
                            .metadata(Map.of("matches", 0, "truncated", false))
                            .build();
                }

                if (result.getExitCode() != 0 && result.getExitCode() != 2) {
                    throw new RuntimeException("ripgrep failed: " + result.getStderr());
                }

                String[] lines = result.text().trim().split("\n");
                List<Map<String, Object>> matches = new ArrayList<>();
                
                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    String[] parts = line.split("\\|");
                    if (parts.length < 3) continue;

                    String filePath = parts[0];
                    String lineNumStr = parts[1];
                    String lineText = line.substring(filePath.length() + lineNumStr.length() + 2);

                    Path resolvedPath = ToolPathResolver.resolveAgainst(searchPath, filePath);
                    File file = resolvedPath.toFile();
                    if (!file.exists()) continue;

                    Map<String, Object> match = new HashMap<>();
                    match.put("path", resolvedPath.toString());
                    match.put("lineNum", Integer.parseInt(lineNumStr));
                    match.put("lineText", lineText);
                    match.put("modTime", file.lastModified());
                    matches.add(match);
                }

                // 按修改时间排序
                matches.sort((a, b) -> Long.compare((long)b.get("modTime"), (long)a.get("modTime")));

                int limit = 100;
                boolean truncated = matches.size() > limit;
                List<Map<String, Object>> finalMatches = truncated ? matches.subList(0, limit) : matches;

                StringBuilder output = new StringBuilder();
                output.append("Found ").append(finalMatches.size()).append(" matches\n");

                String currentFile = "";
                for (Map<String, Object> match : finalMatches) {
                    String matchPath = (String) match.get("path");
                    if (!currentFile.equals(matchPath)) {
                        if (!currentFile.isEmpty()) output.append("\n");
                        currentFile = matchPath;
                        output.append(matchPath).append(":\n");
                    }
                    String lineText = (String) match.get("lineText");
                    if (lineText.length() > 2000) lineText = lineText.substring(0, 2000) + "...";
                    output.append("  Line ").append(match.get("lineNum")).append(": ").append(lineText).append("\n");
                }

                if (truncated) {
                    output.append("\n(Results are truncated. Consider using a more specific path or pattern.)\n");
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
}
