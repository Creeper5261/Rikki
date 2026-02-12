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
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * GlobTool 实现 (对齐 opencode/src/tool/glob.ts)
 */
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
        return loadDescription();
    }

    private String loadDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/glob.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load glob tool description", e);
        }
        return "Fast file pattern matching tool."; // Fallback
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        
        properties.putObject("pattern").put("type", "string").put("description", "The glob pattern to match files against");
        properties.putObject("path").put("type", "string").put("description", "The directory to search in. If not specified, the current working directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \"undefined\" or \"null\" - simply omit it for the default behavior. Must be a valid directory path if provided.");

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

                // 使用 ripgrep 进行文件查找 (rg --files -g pattern)
                List<String> cmdParts = new ArrayList<>();
                cmdParts.add("rg");
                cmdParts.add("--files");
                cmdParts.add("--glob");
                cmdParts.add("\"" + pattern + "\"");
                cmdParts.add("\"" + searchPath.toString() + "\"");

                String command = String.join(" ", cmdParts);
                ExecuteResult result = shellService.execute(command, null, null);

                if (result.getExitCode() != 0 && !result.text().trim().isEmpty()) {
                    // 如果退出码非0但有输出，可能是部分成功
                } else if (result.getExitCode() != 0) {
                    return Result.builder()
                            .title(projectContext.getWorktree())
                            .output("No files found")
                            .metadata(Map.of("count", 0, "truncated", false))
                            .build();
                }

                String[] lines = result.text().trim().split("\n");
                List<Map<String, Object>> files = new ArrayList<>();
                int limit = 100;
                boolean truncated = false;

                for (String line : lines) {
                    if (line.isEmpty()) continue;
                    if (files.size() >= limit) {
                        truncated = true;
                        break;
                    }
                    Path resolved = ToolPathResolver.resolveAgainst(searchPath, line);
                    File file = resolved.toFile();
                    if (file.exists()) {
                        Map<String, Object> f = new HashMap<>();
                        f.put("path", resolved.toString());
                        f.put("mtime", file.lastModified());
                        files.add(f);
                    }
                }

                // 按修改时间排序
                files.sort((a, b) -> Long.compare((long)b.get("mtime"), (long)a.get("mtime")));

                StringBuilder output = new StringBuilder();
                if (files.isEmpty()) {
                    output.append("No files found");
                } else {
                    for (Map<String, Object> f : files) {
                        output.append(f.get("path")).append("\n");
                    }
                    if (truncated) {
                        output.append("\n(Results are truncated. Consider using a more specific path or pattern.)");
                    }
                }

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("count", files.size());
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
}
