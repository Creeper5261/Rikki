package com.zzf.rikki.idea.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.vfs.LocalFileSystem;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class LiteFileTools {
    private static final int DEFAULT_READ_LIMIT = 2_000;
    private static final int MAX_LINE_LEN = 2_000;
    private static final int MAX_BYTES = 50 * 1024;
    private static final int MAX_OUTPUT = 8_000;
    private static final List<String> BINARY_EXTS = List.of(
            "zip", "tar", "gz", "exe", "dll", "so", "class", "jar", "war", "7z", "bin", "dat",
            "png", "jpg", "jpeg", "gif", "bmp", "ico", "pdf", "mp3", "mp4", "avi"
    );

    public LiteFileTools(ObjectMapper mapper) {
    }

    public String read(JsonNode args, String workspaceRoot) throws Exception {
        String pathStr = args.path("filePath").asText("");
        int offset = Math.max(args.path("offset").asInt(0), 0);
        int limit = Math.max(args.path("limit").asInt(DEFAULT_READ_LIMIT), 1);
        File file = resolve(workspaceRoot, pathStr);
        if (!file.exists()) {
            throw new RuntimeException("File not found: " + pathStr);
        }
        if (isBinary(file)) {
            throw new RuntimeException("Cannot read binary file: " + pathStr);
        }
        List<String> allLines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder("<file>\n");
        int bytes = 0;
        boolean truncatedByBytes = false;
        int end = Math.min(allLines.size(), offset + limit);
        for (int i = offset; i < end; i++) {
            String line = allLines.get(i);
            if (line.length() > MAX_LINE_LEN) {
                line = line.substring(0, MAX_LINE_LEN) + "...";
            }
            int size = line.getBytes(StandardCharsets.UTF_8).length + 1;
            if (bytes + size > MAX_BYTES) {
                truncatedByBytes = true;
                break;
            }
            builder.append(String.format("%05d| %s%n", i + 1, line));
            bytes += size;
        }
        int totalLines = allLines.size();
        if (truncatedByBytes) {
            builder.append("\n\n(Truncated at ").append(MAX_BYTES).append(" bytes. Use 'offset' to continue.)");
        } else if (totalLines > offset + limit) {
            builder.append("\n\n(More lines exist. Use 'offset' to read beyond line ").append(offset + limit).append(".)");
        } else {
            builder.append("\n\n(End of file - ").append(totalLines).append(" lines total)");
        }
        builder.append("\n</file>");
        return builder.toString();
    }

    public String write(JsonNode args, String workspaceRoot) throws Exception {
        String pathStr = args.path("filePath").asText("");
        String content = args.path("content").asText("");
        File file = resolve(workspaceRoot, pathStr);
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
        refreshVfsSync(file.getAbsolutePath());
        return "Written: " + pathStr + " (" + content.lines().count() + " lines)";
    }

    public String edit(JsonNode args, String workspaceRoot) throws Exception {
        String pathStr = args.path("filePath").asText("");
        String oldString = args.path("oldString").asText("");
        String newString = args.path("newString").asText("");
        boolean replaceAll = args.path("replaceAll").asBoolean(false);
        File file = resolve(workspaceRoot, pathStr);
        if (!file.exists()) {
            if (!oldString.isEmpty()) {
                throw new RuntimeException("File not found: " + pathStr + ". To create, leave oldString empty.");
            }
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            Files.writeString(file.toPath(), newString, StandardCharsets.UTF_8);
            refreshVfsSync(file.getAbsolutePath());
            return "Created: " + pathStr;
        }
        if (oldString.equals(newString)) {
            throw new RuntimeException("oldString and newString must differ");
        }
        String original = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        List<String> matches = findMatches(original, oldString);
        if (matches.isEmpty()) {
            throw new RuntimeException("oldString not found in file (exact or trimmed match)");
        }
        if (!replaceAll && matches.size() > 1) {
            throw new RuntimeException("oldString found " + matches.size() + " times - provide more context to uniquely identify the match");
        }
        String updated = original;
        if (replaceAll) {
            for (String match : matches) {
                updated = updated.replace(match, newString);
            }
        } else {
            updated = updated.replace(matches.get(0), newString);
        }
        Files.writeString(file.toPath(), updated, StandardCharsets.UTF_8);
        refreshVfsSync(file.getAbsolutePath());
        return "Updated: " + pathStr;
    }

    public String delete(JsonNode args, String workspaceRoot) throws Exception {
        String pathStr = args.path("filePath").asText("");
        File file = resolve(workspaceRoot, pathStr);
        if (!file.exists()) {
            throw new RuntimeException("File not found: " + pathStr);
        }
        if (!file.delete()) {
            throw new RuntimeException("Failed to delete: " + pathStr);
        }
        refreshVfsSync(file.getAbsolutePath());
        return "Deleted: " + pathStr;
    }

    public String glob(JsonNode args, String workspaceRoot) throws Exception {
        String pattern = args.path("pattern").asText("");
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("pattern required");
        }
        File searchRoot = resolve(workspaceRoot, args.path("path").asText(workspaceRoot));
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        List<PathWithTime> results = new ArrayList<>();
        try (var walk = Files.walk(searchRoot.toPath())) {
            walk.filter(path -> {
                Path rel = searchRoot.toPath().relativize(path);
                return matcher.matches(rel) || matcher.matches(path.getFileName());
            }).forEach(path -> results.add(new PathWithTime(path.toFile().lastModified(), path.toString())));
        }
        results.sort(Comparator.comparingLong(PathWithTime::mtime).reversed());
        List<String> lines = results.stream().limit(100).map(PathWithTime::path).toList();
        return lines.isEmpty() ? "No files matched pattern: " + pattern : String.join("\n", lines);
    }

    public String grep(JsonNode args, String workspaceRoot) throws Exception {
        String patternStr = args.path("pattern").asText("");
        if (patternStr.isBlank()) {
            throw new IllegalArgumentException("pattern required");
        }
        File searchRoot = resolve(workspaceRoot, args.path("path").asText(workspaceRoot));
        String include = args.path("include").asText("");
        var includeMatcher = include.isBlank() ? null : FileSystems.getDefault().getPathMatcher("glob:" + include);
        Pattern compiledRegex;
        try {
            compiledRegex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException ignored) {
            compiledRegex = Pattern.compile(Pattern.quote(patternStr));
        }
        final Pattern regex = compiledRegex;
        List<PathWithTime> results = new ArrayList<>();
        try (var walk = Files.walk(searchRoot.toPath())) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                try {
                    File file = path.toFile();
                    if (isBinary(file)) {
                        return;
                    }
                    if (includeMatcher != null && !includeMatcher.matches(path.getFileName())) {
                        return;
                    }
                    try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                        String line;
                        int lineNum = 0;
                        while ((line = reader.readLine()) != null) {
                            lineNum++;
                            if (regex.matcher(line).find()) {
                                results.add(new PathWithTime(file.lastModified(), path + ":" + lineNum + ": " + line));
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        }
        results.sort(Comparator.comparingLong(PathWithTime::mtime).reversed());
        List<String> lines = results.stream().limit(100).map(PathWithTime::path).toList();
        String output = lines.isEmpty() ? "No matches for pattern: " + patternStr : String.join("\n", lines);
        return output.length() > MAX_OUTPUT ? output.substring(0, MAX_OUTPUT) : output;
    }

    public String list(JsonNode args, String workspaceRoot) {
        File dir = resolve(workspaceRoot, args.path("path").asText(workspaceRoot));
        if (!dir.isDirectory()) {
            throw new RuntimeException("Not a directory: " + dir.getPath());
        }
        List<String> ignorePatterns = new ArrayList<>();
        if (args.has("ignore") && args.path("ignore").isArray()) {
            args.path("ignore").forEach(node -> ignorePatterns.add(node.asText("")));
        }
        ignorePatterns.addAll(List.of(".git", "node_modules", ".gradle", "build", "out", ".idea", "__pycache__"));
        StringBuilder builder = new StringBuilder();
        walk(dir, "", ignorePatterns, builder);
        return builder.length() == 0 ? "Empty directory" : builder.toString().trim();
    }

    private void walk(File dir, String indent, List<String> ignorePatterns, StringBuilder builder) {
        if (builder.length() > MAX_OUTPUT) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        List<File> sorted = new ArrayList<>(List.of(children));
        sorted.sort(Comparator.<File, Boolean>comparing(file -> !file.isDirectory()).thenComparing(File::getName));
        for (File child : sorted) {
            if (shouldIgnore(child.getName(), ignorePatterns)) {
                continue;
            }
            builder.append(indent).append(child.getName());
            if (child.isDirectory()) {
                builder.append("/");
            }
            builder.append("\n");
            if (child.isDirectory()) {
                walk(child, indent + "  ", ignorePatterns, builder);
            }
        }
    }

    private boolean shouldIgnore(String name, List<String> ignorePatterns) {
        for (String pattern : ignorePatterns) {
            if (name.equals(pattern)) {
                return true;
            }
            if (name.startsWith(".") && ".*".equals(pattern)) {
                return true;
            }
        }
        return false;
    }

    private List<String> findMatches(String content, String find) {
        if (find.isEmpty()) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        String[] origLines = content.split("\n", -1);
        String[] searchLinesRaw = find.split("\n", -1);
        List<String> searchLines = new ArrayList<>(List.of(searchLinesRaw));
        if (!searchLines.isEmpty() && searchLines.get(searchLines.size() - 1).isEmpty()) {
            searchLines.remove(searchLines.size() - 1);
        }
        if (searchLines.isEmpty() || origLines.length < searchLines.size()) {
            return results;
        }
        for (int i = 0; i <= origLines.length - searchLines.size(); i++) {
            boolean matches = true;
            for (int j = 0; j < searchLines.size(); j++) {
                if (!origLines[i + j].trim().equals(searchLines.get(j).trim())) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                int start = 0;
                for (int k = 0; k < i; k++) {
                    start += origLines[k].length() + 1;
                }
                int end = start;
                for (int k = 0; k < searchLines.size(); k++) {
                    end += origLines[i + k].length();
                    if (k < searchLines.size() - 1) {
                        end += 1;
                    }
                }
                results.add(content.substring(start, end));
            }
        }
        return results;
    }

    private void refreshVfsSync(String absolutePath) {
        try {
            String normalized = absolutePath.replace('\\', '/');
            ApplicationManager.getApplication().invokeAndWait(() -> {
                var file = LocalFileSystem.getInstance().refreshAndFindFileByPath(normalized);
                if (file != null) {
                    file.refresh(false, false);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private File resolve(String workspaceRoot, String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(workspaceRoot, path);
    }

    private boolean isBinary(File file) {
        String ext = file.getName().contains(".") ? file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (BINARY_EXTS.contains(ext)) {
            return true;
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            return false;
        }
        int len = Math.min(bytes.length, 4096);
        if (len == 0) {
            return false;
        }
        int suspicious = 0;
        for (int i = 0; i < len; i++) {
            byte value = bytes[i];
            if (value == 0) {
                return true;
            }
            if (value < 9 || (value >= 14 && value <= 31)) {
                suspicious++;
            }
        }
        return suspicious > len * 0.3;
    }

    private record PathWithTime(long mtime, String path) {
    }
}
