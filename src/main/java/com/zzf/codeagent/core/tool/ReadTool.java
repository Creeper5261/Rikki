package com.zzf.codeagent.core.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zzf.codeagent.project.ProjectContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * ReadTool 实现 (对齐 opencode/src/tool/read.ts)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadTool implements Tool {

    private final ProjectContext projectContext;
    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;

    private static final int DEFAULT_READ_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int MAX_BYTES = 50 * 1024;

    @Override
    public String getId() {
        return "read";
    }

    @Override
    public String getDescription() {
        return loadDescription();
    }

    private String loadDescription() {
        try {
            Resource resource = resourceLoader.getResource("classpath:prompts/tool/read.txt");
            if (resource.exists()) {
                return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("Failed to load read tool description", e);
        }
        return "Reads a file from the local filesystem."; // Fallback
    }

    @Override
    public JsonNode getParametersSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        
        properties.putObject("filePath").put("type", "string").put("description", "The absolute path to the file to read");
        properties.putObject("offset").put("type", "integer").put("description", "The line number to start reading from (0-based)");
        properties.putObject("limit").put("type", "integer").put("description", "The number of lines to read (defaults to 2000)");

        schema.putArray("required").add("filePath");
        return schema;
    }

    @Override
    public CompletableFuture<Result> execute(JsonNode args, Context ctx) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String filePathStr = args.get("filePath").asText();
                int offset = args.has("offset") ? args.get("offset").asInt() : 0;
                int limit = args.has("limit") ? args.get("limit").asInt() : DEFAULT_READ_LIMIT;

                Path filePath = ToolPathResolver.resolvePath(projectContext, ctx, filePathStr);

                if (!Files.exists(filePath)) {
                    // 尝试搜索类似文件名建议
                    File dir = filePath.getParent().toFile();
                    String base = filePath.getFileName().toString().toLowerCase();
                    File[] entries = dir.listFiles();
                    List<String> suggestions = new ArrayList<>();
                    if (entries != null) {
                        for (File entry : entries) {
                            String entryName = entry.getName().toLowerCase();
                            if (entryName.contains(base) || base.contains(entryName)) {
                                suggestions.add(entry.getAbsolutePath());
                            }
                            if (suggestions.size() >= 3) break;
                        }
                    }

                    String errorMsg = "File not found: " + filePathStr;
                    if (!suggestions.isEmpty()) {
                        errorMsg += "\n\nDid you mean one of these?\n" + String.join("\n", suggestions);
                    }
                    throw new RuntimeException(errorMsg);
                }

                if (isBinaryFile(filePath)) {
                    throw new RuntimeException("Cannot read binary file: " + filePathStr);
                }

                List<String> allLines = Files.readAllLines(filePath);
                List<String> raw = new ArrayList<>();
                int bytes = 0;
                boolean truncatedByBytes = false;

                int end = Math.min(allLines.size(), offset + limit);
                for (int i = offset; i < end; i++) {
                    String line = allLines.get(i);
                    if (line.length() > MAX_LINE_LENGTH) {
                        line = line.substring(0, MAX_LINE_LENGTH) + "...";
                    }
                    int size = line.getBytes("UTF-8").length + (raw.isEmpty() ? 0 : 1);
                    if (bytes + size > MAX_BYTES) {
                        truncatedByBytes = true;
                        break;
                    }
                    raw.add(line);
                    bytes += size;
                }

                StringBuilder output = new StringBuilder();
                output.append("<file>\n");
                for (int i = 0; i < raw.size(); i++) {
                    output.append(String.format("%05d| %s\n", i + offset + 1, raw.get(i)));
                }

                int totalLines = allLines.size();
                int lastReadLine = offset + raw.size();
                boolean hasMoreLines = totalLines > lastReadLine;
                boolean truncated = hasMoreLines || truncatedByBytes;

                if (truncatedByBytes) {
                    output.append(String.format("\n\n(Output truncated at %d bytes. Use 'offset' parameter to read beyond line %d)", MAX_BYTES, lastReadLine));
                } else if (hasMoreLines) {
                    output.append(String.format("\n\n(File has more lines. Use 'offset' parameter to read beyond line %d)", lastReadLine));
                } else {
                    output.append(String.format("\n\n(End of file - total %d lines)", totalLines));
                }
                output.append("\n</file>");

                Map<String, Object> metadata = new HashMap<>();
                metadata.put("truncated", truncated);
                metadata.put("preview", raw.stream().limit(20).collect(Collectors.joining("\n")));

                // Add structured file_view for UI
                Map<String, Object> fileView = new HashMap<>();
                fileView.put("filePath", filePath.toString());
                fileView.put("content", raw.stream().collect(Collectors.joining("\n")));
                fileView.put("startLine", offset + 1);
                fileView.put("endLine", offset + raw.size());
                fileView.put("totalLines", totalLines);
                metadata.put("file_view", fileView);

                return Result.builder()
                        .title(ToolPathResolver.safeRelativePath(projectContext, ctx, filePath))
                        .output(output.toString())
                        .metadata(metadata)
                        .build();

            } catch (Exception e) {
                log.error("Failed to execute read tool", e);
                throw new RuntimeException(e.getMessage());
            }
        });
    }

    private boolean isBinaryFile(Path path) throws Exception {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".zip") || name.endsWith(".tar") || name.endsWith(".gz") || 
            name.endsWith(".exe") || name.endsWith(".dll") || name.endsWith(".so") ||
            name.endsWith(".class") || name.endsWith(".jar") || name.endsWith(".war") ||
            name.endsWith(".7z") || name.endsWith(".bin") || name.endsWith(".dat")) {
            return true;
        }

        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length == 0) return false;

        int nonPrintableCount = 0;
        int checkLen = Math.min(bytes.length, 4096);
        for (int i = 0; i < checkLen; i++) {
            if (bytes[i] == 0) return true;
            if (bytes[i] < 9 || (bytes[i] > 13 && bytes[i] < 32)) {
                nonPrintableCount++;
            }
        }
        return (double) nonPrintableCount / checkLen > 0.3;
    }
}
