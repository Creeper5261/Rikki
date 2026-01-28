package com.zzf.codeagent.core.tools;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Optional;
import com.zzf.codeagent.core.tool.PendingChangesManager;
import com.zzf.codeagent.core.tool.PendingChangesManager.PendingChange;

public final class FileSystemToolService {
    private static final long MAX_FILE_BYTES_DEFAULT = 10L * 1024L * 1024L; // 10MB to match openSourceRF
    private static final int MAX_READ_CHARS_DEFAULT = 20_000;
    private static final int MAX_LIST_RESULTS_DEFAULT = 4000;
    private static final int MAX_GREP_MATCHES_DEFAULT = 200;
    private static final int MAX_GREP_FILES_DEFAULT = 200;
    private static final int MAX_VIEW_WINDOW_DEFAULT = 200;
    private static final int MAX_VIEW_WINDOW_LIMIT = 2000;
    private static final int MAX_REPO_MAP_CHARS_DEFAULT = 20000;
    private static final int MAX_EDIT_HISTORY = 20;
    private static final Pattern IMPORT_FROM_PATTERN = Pattern.compile("\\bfrom\\s+([\\w\\./]+)\\s+import\\b");
    private static final Pattern IMPORT_SIMPLE_PATTERN = Pattern.compile("\\bimport\\s+([\\w\\./]+)");
    private static final Pattern JS_IMPORT_PATTERN = Pattern.compile("\\bimport\\s+(?:[\\s\\S]*?\\s+from\\s+)?[\"']([^\"']+)[\"']");
    private static final Pattern REQUIRE_PATTERN = Pattern.compile("\\brequire\\s*\\(\\s*[\"']([^\"']+)[\"']\\s*\\)");
    private static final Pattern INCLUDE_PATTERN = Pattern.compile("#include\\s+[<\"]([^\">]+)[\">]");
    private static final Pattern USING_PATTERN = Pattern.compile("\\busing\\s+([\\w\\.]+)\\s*;");
    private static final Pattern RUST_USE_PATTERN = Pattern.compile("\\buse\\s+([\\w\\.:]+)\\s*;");
    private static final Pattern GO_IMPORT_PATTERN = Pattern.compile("\\bimport\\s*(?:\\(|)(\"[^\"]+\"|'[^']+')");

    private static final Set<String> INDEXABLE_EXTS = Set.of(
            "java", "kt", "kts",
            "xml",
            "yml", "yaml",
            "properties",
            "gradle",
            "md", "txt",
            "sql",
            "json",
            "py", "js", "ts", "tsx", "jsx", "html", "css", "scss", "less",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sh", "bat", "ps1",
            "dockerfile", "conf", "ini", "toml"
    );

    private final Path workspaceRoot;
    private final String sessionId;
    private final String publicWorkspaceRoot;
    private final boolean directWrite;
    private final Map<Path, Deque<FileSnapshot>> editHistory = new ConcurrentHashMap<Path, Deque<FileSnapshot>>();

    public FileSystemToolService(Path workspaceRoot) {
        this(workspaceRoot, null, workspaceRoot == null ? null : workspaceRoot.toAbsolutePath().normalize().toString(), true);
    }

    public FileSystemToolService(Path workspaceRoot, String sessionId, String publicWorkspaceRoot) {
        this(workspaceRoot, sessionId, publicWorkspaceRoot, true);
    }

    public FileSystemToolService(Path workspaceRoot, String sessionId, String publicWorkspaceRoot, boolean directWrite) {
        this.workspaceRoot = workspaceRoot == null ? null : workspaceRoot.toAbsolutePath().normalize();
        this.sessionId = sessionId == null || sessionId.isEmpty() ? null : sessionId;
        this.publicWorkspaceRoot = publicWorkspaceRoot == null || publicWorkspaceRoot.isEmpty() ? null : publicWorkspaceRoot;
        this.directWrite = directWrite;
    }

    private String getOverlayContent(Path file) throws IOException {
        if (directWrite) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        try {
            String relPath = workspaceRoot.relativize(file).toString().replace('\\', '/');
            Optional<PendingChange> pending = PendingChangesManager.getInstance().getPendingChange(relPath, publicWorkspaceRoot, sessionId);
            if (pending.isPresent()) {
                if ("DELETE".equals(pending.get().type)) {
                     throw new java.nio.file.NoSuchFileException(file.toString());
                }
                return pending.get().newContent;
            }
        } catch (IllegalArgumentException e) {
            // Not under workspace root, fall through to disk
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private boolean isOverlayExists(Path file) {
        if (file == null) return false;
        if (directWrite) {
            return Files.exists(file);
        }
        try {
            String relPath = workspaceRoot.relativize(file).toString().replace('\\', '/');
            Optional<PendingChange> pending = PendingChangesManager.getInstance().getPendingChange(relPath, publicWorkspaceRoot, sessionId);
            if (pending.isPresent()) {
                return !"DELETE".equals(pending.get().type);
            }
        } catch (IllegalArgumentException e) {
            // Not under workspace root
        }
        return Files.exists(file);
    }

    public interface FileChangeListener {
        void onFileChanged(Path path);
    }

    private FileChangeListener fileChangeListener;

    public void setFileChangeListener(FileChangeListener listener) {
        this.fileChangeListener = listener;
    }

    private void notifyChange(Path path) {
        if (this.fileChangeListener != null && path != null) {
            try {
                this.fileChangeListener.onFileChanged(path);
            } catch (Exception e) {
                // ignore
            }
        }
    }

    public ListFilesResult listFiles(String path, String glob, Integer maxResults, Integer maxDepth) {
        int limit = maxResults == null || maxResults.intValue() <= 0 ? MAX_LIST_RESULTS_DEFAULT : Math.min(maxResults.intValue(), 20_000);
        int depth = maxDepth == null || maxDepth.intValue() <= 0 ? 25 : Math.min(maxDepth.intValue(), 80);

        Path root = resolveUnderWorkspace(path == null ? "" : path);
        if (root == null) {
            return new ListFilesResult(Collections.emptyList(), true, "path_outside_workspace");
        }
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return new ListFilesResult(Collections.emptyList(), false, "path_not_a_directory");
        }

        String useGlob = glob == null || glob.trim().isEmpty() ? "**/*" : glob.trim();
        PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + useGlob);

        List<String> out = new ArrayList<String>();
        Set<String> addedPaths = new HashSet<>();
        
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (shouldSkipDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    int relDepth = root.relativize(dir).getNameCount();
                    if (relDepth > depth) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (out.size() >= limit) {
                        return FileVisitResult.TERMINATE;
                    }
                    Path rel = root.relativize(file);
                    if (!matcher.matches(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs == null || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relUnix = rel.toString().replace('\\', '/');
                    if (!isIndexablePath(relUnix)) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    // Check overlay existence (handle deleted files)
                    if (!isOverlayExists(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    out.add(relUnix);
                    addedPaths.add(relUnix);
                    return FileVisitResult.CONTINUE;
                }
            });
            
            // Merge pending new files
            if (!directWrite && out.size() < limit) {
                List<PendingChange> pendingChanges = PendingChangesManager.getInstance().getChanges(publicWorkspaceRoot, sessionId);
                for (PendingChange pc : pendingChanges) {
                    if ("DELETE".equals(pc.type)) continue;
                    
                    // Check if inside root
                    Path absPath = workspaceRoot.resolve(pc.path);
                    if (!absPath.startsWith(root)) continue;
                    
                    Path rel = root.relativize(absPath);
                    String s = rel.toString().replace('\\', '/');
                    if (addedPaths.contains(s)) continue; // Already added
                    
                    Path parent = rel.getParent();
                    int parentDepth = (parent == null) ? 0 : parent.getNameCount();
                    if (parentDepth > depth) continue;
                    
                    if (matcher.matches(rel)) {
                         if (!isIndexablePath(pc.path)) continue;
                         out.add(s);
                         addedPaths.add(s);
                         if (out.size() >= limit) break;
                    }
                }
            }
            
        } catch (IOException e) {
            return new ListFilesResult(out, out.size() >= limit, "io_error:" + e.getClass().getSimpleName());
        }
        
        Collections.sort(out);
        return new ListFilesResult(out, out.size() >= limit, null);
    }

    public GrepResult grep(String pattern, String root, String fileGlob, Integer maxMatches, Integer maxFiles, Integer contextLines) {
        int matchLimit = maxMatches == null || maxMatches.intValue() <= 0 ? MAX_GREP_MATCHES_DEFAULT : Math.min(maxMatches.intValue(), 2000);
        int fileLimit = maxFiles == null || maxFiles.intValue() <= 0 ? MAX_GREP_FILES_DEFAULT : Math.min(maxFiles.intValue(), 2000);
        int ctx = contextLines == null || contextLines.intValue() < 0 ? 0 : Math.min(contextLines.intValue(), 5);

        Path rootDir = resolveUnderWorkspace(root == null ? "" : root);
        if (rootDir == null) {
            return new GrepResult(Collections.emptyList(), true, "root_outside_workspace");
        }
        if (!Files.exists(rootDir) || !Files.isDirectory(rootDir)) {
            return new GrepResult(Collections.emptyList(), false, "root_not_a_directory");
        }

        Pattern re;
        try {
            re = Pattern.compile(pattern == null ? "" : pattern);
        } catch (Exception e) {
            return new GrepResult(Collections.emptyList(), false, "bad_regex:" + e.getMessage());
        }

        String useGlob = fileGlob == null || fileGlob.trim().isEmpty()
                ? "**/*.{java,kt,kts,xml,yml,yaml,properties,gradle,md,txt,sql,json}"
                : fileGlob.trim();
        PathMatcher matcher = rootDir.getFileSystem().getPathMatcher("glob:" + useGlob);

        List<GrepMatch> matches = new ArrayList<GrepMatch>();
        final int[] filesSearched = new int[]{0};
        Set<String> processedPaths = new HashSet<>();
        
        try {
            Files.walkFileTree(rootDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (shouldSkipDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matches.size() >= matchLimit) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (filesSearched[0] >= fileLimit) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs == null || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    Path rel = rootDir.relativize(file);
                    if (!matcher.matches(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    String relUnix = rel.toString().replace('\\', '/');
                    if (!isIndexablePath(relUnix)) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    // Check overlay existence (handle deleted files)
                    if (!isOverlayExists(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    // Mark as processed
                    String workspaceRel = workspaceRoot.relativize(file).toString().replace('\\', '/');
                    processedPaths.add(workspaceRel);
                    
                    String overlayContent = null;
                    try {
                        String relPath = workspaceRoot.relativize(file).toString().replace('\\', '/');
                        Optional<PendingChangesManager.PendingChange> pending = PendingChangesManager.getInstance().getPendingChange(relPath, publicWorkspaceRoot, sessionId);
                        if (pending.isPresent()) {
                             overlayContent = pending.get().newContent;
                             if (overlayContent == null) return FileVisitResult.CONTINUE; 
                        } else {
                            if (attrs.size() > MAX_FILE_BYTES_DEFAULT) {
                                return FileVisitResult.CONTINUE;
                            }
                        }
                    } catch (Exception e) {
                        // ignore
                    }

                    filesSearched[0]++;
                    try {
                        scanOneFile(file, relUnix, re, matchLimit, ctx, matches, overlayContent);
                    } catch (MalformedInputException e) {
                        return FileVisitResult.CONTINUE;
                    } catch (IOException e) {
                        return FileVisitResult.CONTINUE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            
            // Scan pending new files
            if (matches.size() < matchLimit && filesSearched[0] < fileLimit) {
                List<PendingChangesManager.PendingChange> pendingChanges = PendingChangesManager.getInstance().getChanges(publicWorkspaceRoot, sessionId);
                for (PendingChangesManager.PendingChange pc : pendingChanges) {
                    if ("DELETE".equals(pc.type)) continue;
                    if (processedPaths.contains(pc.path)) continue;
                    
                    Path absPath = workspaceRoot.resolve(pc.path);
                    if (!absPath.startsWith(rootDir)) continue;
                    
                    Path rel = rootDir.relativize(absPath);
                    if (!matcher.matches(rel)) continue;
                    
                    if (!isIndexablePath(pc.path)) continue;
                    
                    filesSearched[0]++;
                    String relUnix = rel.toString().replace('\\', '/');
                    try {
                         scanOneFile(absPath, relUnix, re, matchLimit, ctx, matches, pc.newContent);
                         if (matches.size() >= matchLimit) break;
                    } catch (IOException e) {
                         // ignore
                    }
                }
            }
            
        } catch (IOException e) {
            return new GrepResult(matches, matches.size() >= matchLimit, "io_error:" + e.getClass().getSimpleName());
        }
        boolean truncated = matches.size() >= matchLimit || filesSearched[0] >= fileLimit;
        return new GrepResult(matches, truncated, null);
    }

    public ReadFileResult readFile(String path, Integer startLine, Integer endLine, Integer maxChars) {
        int max = maxChars == null || maxChars.intValue() <= 0 ? MAX_READ_CHARS_DEFAULT : Math.min(maxChars.intValue(), 120_000);
        int start = startLine == null || startLine.intValue() <= 0 ? 1 : startLine.intValue();
        int end = endLine == null || endLine.intValue() <= 0 ? start + 200 : Math.max(start, endLine.intValue());
        end = Math.min(end, start + 2000);

        Path file = resolveFileUnderWorkspace(path == null ? "" : path);
        if (file == null) {
            return new ReadFileResult("", start, end, true, "", "path_outside_workspace");
        }

        String relUnix;
        try {
            relUnix = workspaceRoot.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            relUnix = file.getFileName() == null ? "" : file.getFileName().toString();
        }

        Optional<PendingChange> pending = PendingChangesManager.getInstance().getPendingChange(relUnix, publicWorkspaceRoot, sessionId);
        if (pending.isPresent()) {
             if ("DELETE".equals(pending.get().type)) {
                 return new ReadFileResult(path, start, end, false, "", "not_a_file");
             }
             if (!isIndexablePath(relUnix)) {
                 return new ReadFileResult(path, start, end, false, "", "file_not_whitelisted");
             }
             String content = pending.get().newContent;
             StringBuilder sb = new StringBuilder();
             boolean truncated = false;
             String[] lines = content.split("\n", -1);
             int lineNo = 0;
             for (String line : lines) {
                 lineNo++;
                 if (lineNo < start) continue;
                 if (lineNo > end) break;
                 // Note: split() removes the newline chars, so 'line' is without \n.
                 // But split("\n", -1) on "a\n" gives ["a", ""]. 
                 // readLine() on "a\n" gives "a", then null.
                 // We need to match readLine behavior mostly.
                 // If last line is empty in split, it means file ended with \n.
                 // BufferedReader loop would not return the last empty line if it's just EOF.
                 // But usually we want to see it?
                 // Let's stick to simple iteration.
                 if (lineNo == lines.length && line.isEmpty()) {
                     // If it's the last element and empty, it might be artifact of split if file ends with newline.
                     // But if file is "a\nb", split is ["a", "b"].
                     // If "a\n", split is ["a", ""].
                     // readLine would return "a".
                     // So we should skip the last empty line if it matches EOF?
                     // Let's keep it for now, barely hurts.
                 }
                 String row = lineNo + "→" + line + "\n";
                 if (sb.length() + row.length() > max) {
                     truncated = true;
                     break;
                 }
                 sb.append(row);
             }
             return new ReadFileResult(path, start, end, truncated, sb.toString(), null);
        }

        if (!Files.exists(file) || Files.isDirectory(file)) {
            return new ReadFileResult(path, start, end, false, "", "not_a_file");
        }
        // relUnix calculated above, no need to recalc
        if (!isIndexablePath(relUnix)) {
            return new ReadFileResult(path, start, end, false, "", "file_not_whitelisted");
        }
        if (isBinary(file)) {
            return new ReadFileResult(path, start, end, false, "", "file_is_binary");
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES_DEFAULT) {
                return new ReadFileResult(path, start, end, false, "", "file_too_large");
            }
        } catch (IOException e) {
            return new ReadFileResult(path, start, end, false, "", "io_error:" + e.getClass().getSimpleName());
        }

        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        int lineNo = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (lineNo < start) {
                    continue;
                }
                if (lineNo > end) {
                    break;
                }
                String row = lineNo + "→" + line + "\n";
                if (sb.length() + row.length() > max) {
                    truncated = true;
                    break;
                }
                sb.append(row);
            }
        } catch (Exception e) {
            return new ReadFileResult(path, start, end, false, "", "io_error:" + e.getClass().getSimpleName());
        }
        return new ReadFileResult(path, start, end, truncated, sb.toString(), null);
    }

    public FileViewResult viewFile(String path, Integer startLine, Integer window, Integer maxChars) {
        int win = window == null || window.intValue() <= 0 ? MAX_VIEW_WINDOW_DEFAULT : Math.min(window.intValue(), MAX_VIEW_WINDOW_LIMIT);
        int max = maxChars == null || maxChars.intValue() <= 0 ? MAX_READ_CHARS_DEFAULT : Math.min(maxChars.intValue(), 120_000);
        Path file = resolveFileUnderWorkspace(path == null ? "" : path);
        if (file == null) {
            return new FileViewResult("", 1, 0, 0, false, "", "path_outside_workspace", false, false, win);
        }
        if (!Files.exists(file) || Files.isDirectory(file)) {
            return new FileViewResult(path, 1, 0, 0, false, "", "not_a_file", false, false, win);
        }
        String relUnix;
        try {
            relUnix = workspaceRoot.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            relUnix = file.getFileName() == null ? "" : file.getFileName().toString();
        }
        if (!isIndexablePath(relUnix)) {
            return new FileViewResult(path, 1, 0, 0, false, "", "file_not_whitelisted", false, false, win);
        }
        try {
            if (Files.size(file) > MAX_FILE_BYTES_DEFAULT) {
                return new FileViewResult(path, 1, 0, 0, false, "", "file_too_large", false, false, win);
            }
        } catch (Exception e) {
            return new FileViewResult(path, 1, 0, 0, false, "", "io_error:" + e.getClass().getSimpleName(), false, false, win);
        }
        if (isBinary(file)) {
            return new FileViewResult(path, 1, 0, 0, false, "", "file_is_binary", false, false, win);
        }
        int totalLines = countLines(file);
        if (totalLines <= 0) {
            return new FileViewResult(path, 1, 0, 0, false, "", null, false, false, win);
        }
        int start = startLine == null || startLine.intValue() <= 0 ? 1 : startLine.intValue();
        start = Math.max(1, Math.min(start, totalLines));
        if (start + win - 1 > totalLines) {
            start = Math.max(1, totalLines - win + 1);
        }
        int end = Math.min(totalLines, start + win - 1);
        ReadWindow windowResult = readWindow(file, start, end, max);
        boolean hasMoreAbove = start > 1;
        boolean hasMoreBelow = end < totalLines;
        return new FileViewResult(path, start, end, totalLines, windowResult.truncated, windowResult.content, windowResult.error, hasMoreAbove, hasMoreBelow, win);
    }

    public FileSearchResult searchInFile(String path, String pattern, Integer maxMatches, Integer maxLines) {
        int matchLimit = maxMatches == null || maxMatches.intValue() <= 0 ? 200 : Math.min(maxMatches.intValue(), 2000);
        int lineLimit = maxLines == null || maxLines.intValue() <= 0 ? 100 : Math.min(maxLines.intValue(), 500);
        Path file = resolveFileUnderWorkspace(path == null ? "" : path);
        if (file == null) {
            return new FileSearchResult("", 0, 0, Collections.emptyList(), true, "path_outside_workspace");
        }
        if (!Files.exists(file) || Files.isDirectory(file)) {
            return new FileSearchResult(path, 0, 0, Collections.emptyList(), false, "not_a_file");
        }
        String relUnix;
        try {
            relUnix = workspaceRoot.relativize(file).toString().replace('\\', '/');
        } catch (Exception e) {
            relUnix = file.getFileName() == null ? "" : file.getFileName().toString();
        }
        if (!isIndexablePath(relUnix)) {
            return new FileSearchResult(path, 0, 0, Collections.emptyList(), false, "file_not_whitelisted");
        }
        Pattern re;
        try {
            re = Pattern.compile(pattern == null ? "" : pattern);
        } catch (Exception e) {
            return new FileSearchResult(path, 0, 0, Collections.emptyList(), false, "bad_regex:" + e.getMessage());
        }
        List<FileSearchMatch> hits = new ArrayList<FileSearchMatch>();
        int totalMatches = 0;
        int matchedLines = 0;
        boolean truncated = false;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                Matcher m = re.matcher(line);
                if (m.find()) {
                    totalMatches++;
                    if (matchedLines < lineLimit) {
                        hits.add(new FileSearchMatch(lineNo, line));
                        matchedLines++;
                    } else {
                        truncated = true;
                        break;
                    }
                    if (totalMatches >= matchLimit) {
                        truncated = true;
                        break;
                    }
                }
            }
        } catch (Exception e) {
            return new FileSearchResult(path, totalMatches, matchedLines, hits, truncated, "io_error:" + e.getClass().getSimpleName());
        }
        return new FileSearchResult(path, totalMatches, matchedLines, hits, truncated, null);
    }

    public RepoMapResult repoMap(String path, Integer maxDepth, Integer maxFiles, Integer maxChars) {
        return repoMap(path, maxDepth, maxFiles, maxChars, null);
    }

    public RepoMapResult repoMap(String path, Integer maxDepth, Integer maxFiles, Integer maxChars, Set<String> focusPaths) {
        return generateMap(path, maxDepth, maxFiles, maxChars, focusPaths, true);
    }

    public RepoMapResult structureMap(String path, Integer maxDepth, Integer maxFiles, Integer maxChars) {
        return generateMap(path, maxDepth, maxFiles, maxChars, null, false);
    }

    private RepoMapResult generateMap(String path, Integer maxDepth, Integer maxFiles, Integer maxChars, Set<String> focusPaths, boolean useRanking) {
        int depth = maxDepth == null || maxDepth.intValue() <= 0 ? 6 : Math.min(maxDepth.intValue(), 20);
        int limit = maxFiles == null || maxFiles.intValue() <= 0 ? 2000 : Math.min(maxFiles.intValue(), 20000);
        int max = maxChars == null || maxChars.intValue() <= 0 ? MAX_REPO_MAP_CHARS_DEFAULT : Math.min(maxChars.intValue(), 200000);
        Path root = resolveUnderWorkspace(path == null ? "" : path);
        if (root == null) {
            return new RepoMapResult("", true, 0, "path_outside_workspace");
        }
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return new RepoMapResult("", false, 0, "path_not_a_directory");
        }
        List<String> filePaths = new ArrayList<String>();
        List<String> dirPaths = new ArrayList<String>();
        boolean truncated = false;
        final int[] fileCount = new int[]{0};
        final int[] totalCount = new int[]{0};
        Set<String> processedPaths = new HashSet<>();

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (shouldSkipDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    int relDepth = root.relativize(dir).getNameCount();
                    if (relDepth > depth) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!dir.equals(root)) {
                        Path rel = root.relativize(dir);
                        String relUnix = rel.toString().replace('\\', '/');
                        dirPaths.add(relUnix + "/");
                        totalCount[0]++;
                        if (totalCount[0] >= limit) {
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (totalCount[0] >= limit) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attrs == null || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    int relDepth = root.relativize(file).getNameCount();
                    if (relDepth > depth) {
                        return FileVisitResult.CONTINUE;
                    }
                    Path rel = root.relativize(file);
                    String relUnix = rel.toString().replace('\\', '/');
                    if (!isIndexablePath(relUnix)) {
                        return FileVisitResult.CONTINUE;
                    }

                    // Check overlay existence (handle deleted files)
                    if (!isOverlayExists(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    processedPaths.add(workspaceRoot.relativize(file).toString().replace('\\', '/'));

                    filePaths.add(relUnix);
                    fileCount[0]++;
                    totalCount[0]++;
                    return FileVisitResult.CONTINUE;
                }
            });
            
            // Merge pending new files
            if (totalCount[0] < limit) {
                List<PendingChangesManager.PendingChange> pendingChanges = PendingChangesManager.getInstance().getChanges(publicWorkspaceRoot, sessionId);
                for (PendingChangesManager.PendingChange pc : pendingChanges) {
                    if ("DELETE".equals(pc.type)) continue;
                    if (processedPaths.contains(pc.path)) continue;
                    
                    Path absPath = workspaceRoot.resolve(pc.path);
                    if (!absPath.startsWith(root)) continue;
                    
                    Path rel = root.relativize(absPath);
                    int relDepth = rel.getNameCount();
                    if (relDepth > depth) continue;
                    
                    String relUnix = rel.toString().replace('\\', '/');
                    if (!isIndexablePath(pc.path)) continue;
                    
                    filePaths.add(relUnix);
                    fileCount[0]++;
                    totalCount[0]++;
                    if (totalCount[0] >= limit) break;
                }
            }

        } catch (IOException e) {
            return new RepoMapResult("", true, fileCount[0], "io_error:" + e.getClass().getSimpleName());
        }
        if (totalCount[0] >= limit) {
            truncated = true;
        }
        
        Map<String, Double> ranks;
        if (useRanking) {
            Set<String> focusRelPaths = normalizeFocusPaths(root, focusPaths);
            ranks = rankFiles(root, filePaths, focusRelPaths);
        } else {
            ranks = Collections.emptyMap();
        }

        TreeNode rootNode = new TreeNode("");
        dirPaths.sort(Comparator.naturalOrder());
        for (String p : dirPaths) {
            addPath(rootNode, p);
        }
        for (String p : filePaths) {
            addPath(rootNode, p);
        }
        applyScores(rootNode, "", ranks);
        StringBuilder sb = new StringBuilder();
        boolean[] cut = new boolean[]{false};
        renderTree(rootNode, sb, 0, depth, max, cut);
        if (cut[0]) {
            truncated = true;
        }
        return new RepoMapResult(sb.toString(), truncated, fileCount[0], null);
    }

    public EditFileResult editFile(String path, String oldText, String newText) {
        return editFile(path, oldText, newText, false);
    }

    /**
     * Directly applies content to file (write to disk), bypassing PendingChangesManager.
     * Used by ApplyPendingDiffTool.
     */
    public EditFileResult applyToFile(String path, String content, boolean isDelete) {
        Path file = resolveFileUnderWorkspace(path == null ? "" : path);
        if (file == null) {
            return new EditFileResult(path, false, "path_outside_workspace", false);
        }
        
        try {
            if (isDelete) {
                if (!Files.exists(file)) {
                    // Already gone, consider success
                    return new EditFileResult(path, true, null, false);
                }
                if (Files.isDirectory(file)) {
                    try (java.util.stream.Stream<Path> s = Files.list(file)) {
                        if (s.findAny().isPresent()) {
                            return new EditFileResult(path, false, "directory_not_empty", false);
                        }
                    }
                    pushHistory(file, new FileSnapshot(true, true, null));
                    Files.delete(file);
                    notifyChange(file);
                    return new EditFileResult(path, true, null, false);
                }
                
                String oldContent = Files.readString(file, StandardCharsets.UTF_8);
                pushHistory(file, new FileSnapshot(true, false, oldContent));
                Files.delete(file);
                notifyChange(file);
                return new EditFileResult(path, true, null, false);
            } else {
                // Write/Create
                if (Files.exists(file)) {
                    if (isBinary(file)) {
                        return new EditFileResult(path, false, "file_is_binary", false);
                    }
                    String oldContent = Files.readString(file, StandardCharsets.UTF_8);
                    pushHistory(file, new FileSnapshot(true, false, oldContent));
                } else {
                    if (file.getParent() != null && !Files.exists(file.getParent())) {
                        Files.createDirectories(file.getParent());
                    }
                    pushHistory(file, new FileSnapshot(false, false, null));
                }
                
                if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES_DEFAULT) {
                    return new EditFileResult(path, false, "file_too_large", false);
                }
                
                Files.writeString(file, content, StandardCharsets.UTF_8, 
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                notifyChange(file);
                return new EditFileResult(path, true, null, false);
            }
        } catch (Exception e) {
            return new EditFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), false);
        }
    }

    public EditFileResult editFile(String path, String oldText, String newText, Boolean preview) {
        boolean dryRun = preview != null && preview.booleanValue();
        Path file = resolveFileUnderWorkspace(path == null ? "" : path);
        if (file == null) {
            return new EditFileResult(path, false, "path_outside_workspace", dryRun);
        }
        if (!isOverlayExists(file) || Files.isDirectory(file)) {
            return new EditFileResult(path, false, "file_not_found", dryRun);
        }
        if (isBinary(file)) {
            return new EditFileResult(path, false, "file_is_binary", dryRun);
        }
        try {
            String content = getOverlayContent(file);
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES_DEFAULT) {
                return new EditFileResult(path, false, "file_too_large", dryRun);
            }
            if (oldText == null || oldText.isEmpty()) {
                return new EditFileResult(path, false, "old_text_required", dryRun, content, null);
            }
            if (!content.contains(oldText)) {
                return new EditFileResult(path, false, "old_text_not_found", dryRun, content, null);
            }
            int idx = content.indexOf(oldText);
            String updated = content.substring(0, idx) + newText + content.substring(idx + oldText.length());
            
            if (!dryRun) {
                String relPath = workspaceRoot.relativize(file).toString().replace('\\', '/');
                submitPendingChange(relPath, "EDIT", updated, content);
            }
            return new EditFileResult(path, true, null, dryRun, content, updated, true);
        } catch (Exception e) {
            return new EditFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), dryRun);
        }
    }

    public EditFileResult createFile(String path, String fileText) {
        return createFile(path, fileText, false);
    }

    public EditFileResult createFile(String path, String fileText, Boolean preview) {
        boolean dryRun = preview != null && preview.booleanValue();
        if (fileText == null) {
            return new EditFileResult(path, false, "file_text_required", dryRun);
        }
        if (fileText.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES_DEFAULT) {
            return new EditFileResult(path, false, "file_too_large", dryRun);
        }
        Path p = resolveUnderWorkspace(path == null ? "" : path);
        if (p == null) {
            return new EditFileResult(path, false, "path_outside_workspace", dryRun);
        }
        if (isOverlayExists(p)) {
            return new EditFileResult(path, false, "file_already_exists", dryRun);
        }
        Path parent = p.getParent();
        if (parent != null && Files.exists(parent) && !Files.isDirectory(parent)) {
            return new EditFileResult(path, false, "parent_not_directory", dryRun);
        }
        try {
            if (!dryRun) {
                String relPath = workspaceRoot.relativize(p).toString().replace('\\', '/');
                submitPendingChange(relPath, "CREATE", fileText, null);
            }
            return new EditFileResult(path, true, null, dryRun, null, fileText, false);
        } catch (Exception e) {
            return new EditFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), dryRun);
        }
    }

    public EditFileResult overwriteFile(String path, String content, Boolean preview) {
        boolean dryRun = preview != null && preview.booleanValue();
        if (content == null) {
            return new EditFileResult(path, false, "content_required", dryRun);
        }
        if (content.length() > MAX_FILE_BYTES_DEFAULT) {
            return new EditFileResult(path, false, "file_too_large", dryRun);
        }
        Path p = resolveUnderWorkspace(path == null ? "" : path);
        if (p == null) {
            return new EditFileResult(path, false, "path_outside_workspace", dryRun);
        }
        if (!isOverlayExists(p) || Files.isDirectory(p)) {
            // For overwrite, we usually expect file to exist, but if not, we can create it?
            // Let's stick to "Overwrite" meaning replacing existing content.
            // If it doesn't exist, maybe fail or create?
            // "editFile" fails if not found.
            // "createFile" fails if exists.
            // "overwriteFile" should probably behave like "writeString(TRUNCATE)".
            // Let's allow creating if parent exists.
            if (!isOverlayExists(p)) {
                 Path parent = p.getParent();
                 if (parent == null || !Files.exists(parent)) {
                     return new EditFileResult(path, false, "parent_not_found", dryRun);
                 }
            }
        }
        if (isBinary(p)) {
            return new EditFileResult(path, false, "file_is_binary", dryRun);
        }
        try {
            String oldContent = isOverlayExists(p) ? getOverlayContent(p) : "";
            if (!dryRun) {
                String relPath = workspaceRoot.relativize(p).toString().replace('\\', '/');
                submitPendingChange(relPath, "EDIT", content, oldContent);
            }
            return new EditFileResult(path, true, null, dryRun, oldContent, content, true);
        } catch (Exception e) {
            return new EditFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), dryRun);
        }
    }

    public EditFileResult insertIntoFile(String path, Integer insertLine, String newText) {
        return insertIntoFile(path, insertLine, newText, false);
    }

    public EditFileResult insertIntoFile(String path, Integer insertLine, String newText, Boolean preview) {
        boolean dryRun = preview != null && preview.booleanValue();
        if (insertLine == null || insertLine.intValue() <= 0) {
            return new EditFileResult(path, false, "insert_line_invalid", dryRun);
        }
        if (newText == null) {
            return new EditFileResult(path, false, "new_text_required", dryRun);
        }
        Path file = resolveFileUnderWorkspace(path == null ? "" : path);
        if (file == null) {
            return new EditFileResult(path, false, "path_outside_workspace", dryRun);
        }
        if (!isOverlayExists(file) || Files.isDirectory(file)) {
            return new EditFileResult(path, false, "file_not_found", dryRun);
        }
        if (isBinary(file)) {
            return new EditFileResult(path, false, "file_is_binary", dryRun);
        }
        try {
            String content = getOverlayContent(file);
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES_DEFAULT) {
                return new EditFileResult(path, false, "file_too_large", dryRun);
            }
            String[] lines = content.split("\n", -1);
            List<String> list = new ArrayList<String>(Arrays.asList(lines));
            int index = Math.min(insertLine.intValue(), list.size());
            String[] insertLines = newText.split("\n", -1);
            list.addAll(index, Arrays.asList(insertLines));
            String updated = String.join("\n", list);
            
            if (!dryRun) {
                String relPath = workspaceRoot.relativize(file).toString().replace('\\', '/');
                submitPendingChange(relPath, "EDIT", updated, content);
            }
            return new EditFileResult(path, true, null, dryRun, content, updated, true);
        } catch (Exception e) {
            return new EditFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), dryRun);
        }
    }

    public EditFileResult replaceLines(String path, Integer startLine, Integer endLine, String newContent, Boolean preview) {
        boolean dryRun = preview != null && preview.booleanValue();
        if (startLine == null || startLine.intValue() < 1) {
            return new EditFileResult(path, false, "start_line_invalid", dryRun);
        }
        if (endLine == null || endLine.intValue() < startLine) {
            return new EditFileResult(path, false, "end_line_invalid", dryRun);
        }
        String replacement = newContent == null ? "" : newContent;

        Path file = resolveFileUnderWorkspace(path == null ? "" : path);
        if (file == null) {
            return new EditFileResult(path, false, "path_outside_workspace", dryRun);
        }
        if (!isOverlayExists(file) || Files.isDirectory(file)) {
            return new EditFileResult(path, false, "file_not_found", dryRun);
        }
        if (isBinary(file)) {
            return new EditFileResult(path, false, "file_is_binary", dryRun);
        }
        try {
            String content = getOverlayContent(file);
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES_DEFAULT) {
                return new EditFileResult(path, false, "file_too_large", dryRun);
            }
            String[] lines = content.split("\n", -1);
            List<String> list = new ArrayList<String>(Arrays.asList(lines));
            
            int startIdx = startLine - 1;
            int endIdx = endLine - 1;
            
            if (startIdx >= list.size()) {
                 return new EditFileResult(path, false, "start_line_out_of_bounds", dryRun);
            }
            endIdx = Math.min(endIdx, list.size() - 1);
            
            List<String> prefix = new ArrayList<String>(list.subList(0, startIdx));
            List<String> suffix = new ArrayList<String>(list.subList(endIdx + 1, list.size()));
            
            List<String> newLines = new ArrayList<String>();
            if (!replacement.isEmpty()) {
                String[] replacementLines = replacement.split("\n", -1);
                newLines.addAll(Arrays.asList(replacementLines));
            }
            
            List<String> result = new ArrayList<String>(prefix);
            result.addAll(newLines);
            result.addAll(suffix);
            
            String updated = String.join("\n", result);
            
            if (!dryRun) {
                String relPath = workspaceRoot.relativize(file).toString().replace('\\', '/');
                submitPendingChange(relPath, "EDIT", updated, content);
            }
            return new EditFileResult(path, true, null, dryRun, content, updated, true);
            
        } catch (Exception e) {
             return new EditFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), dryRun);
        }
    }

    public EditFileResult undoEdit(String path) {
        Path p = resolveUnderWorkspace(path == null ? "" : path);
        if (p == null) {
            return new EditFileResult(path, false, "path_outside_workspace");
        }
        
        // In Sandbox mode, "Undo" primarily means removing the last pending change for this file.
        String relPath = workspaceRoot.relativize(p).toString().replace('\\', '/');
        Optional<PendingChange> pending = PendingChangesManager.getInstance().getPendingChange(relPath, publicWorkspaceRoot, sessionId);
        if (pending.isPresent()) {
            PendingChangesManager.getInstance().removeChange(pending.get().id);
            return new EditFileResult(path, true, null, false, null, null, true);
        }
        
        FileSnapshot snap = popHistory(p);
        if (snap == null) {
            return new EditFileResult(path, false, "no_edit_history");
        }
        try {
            if (snap.directory) {
                if (snap.existed) {
                    Files.createDirectories(p);
                } else {
                    Files.deleteIfExists(p);
                }
            } else {
                if (snap.existed) {
                    Files.writeString(p, snap.content == null ? "" : snap.content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                } else {
                    Files.deleteIfExists(p);
                }
            }
            notifyChange(p);
            return new EditFileResult(path, true, null, false, null, null, snap.existed);
        } catch (Exception e) {
            return new EditFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), false, null, null, true);
        }
    }

    public EditFileResult deletePath(String path, Boolean preview) {
        Path p = resolveUnderWorkspace(path == null ? "" : path);
        if (p == null) {
            return new EditFileResult(path, false, "path_outside_workspace", false, null, null, false);
        }
        if (workspaceRoot != null && p.equals(workspaceRoot)) {
            return new EditFileResult(path, false, "cannot_delete_workspace_root", false, null, null, true);
        }
        
        // If it's a file, we can use PendingChangesManager
        // If it's a directory, we need to decide.
        // For now, let's try to handle files via pending, and directories we might block or allow if empty.
        
        boolean dryRun = preview != null && preview.booleanValue();
        try {
            if (Files.isDirectory(p)) {
                // Check if directory is empty on disk
                try (java.util.stream.Stream<Path> s = Files.list(p)) {
                    // Check if all files on disk are pending deletion
                    boolean allDeleted = true;
                    for (Path child : s.collect(java.util.stream.Collectors.toList())) {
                        String childRel = workspaceRoot.relativize(child).toString().replace('\\', '/');
                        Optional<PendingChange> pending = PendingChangesManager.getInstance().getPendingChange(childRel, publicWorkspaceRoot, sessionId);
                        if (pending.isEmpty() || !"DELETE".equals(pending.get().type)) {
                             allDeleted = false;
                             break;
                        }
                    }
                    if (!allDeleted) {
                        return new EditFileResult(path, false, "directory_not_empty", dryRun, null, null, true);
                    }
                }
                
                // Check if there are any pending creates inside this directory?
                // PendingChangesManager stores relative paths.
                // We can check if any pending change starts with this directory path.
                // This is expensive if we have many changes, but safe.
                String relDir = workspaceRoot.relativize(p).toString().replace('\\', '/') + "/";
                boolean hasPendingChildren = PendingChangesManager.getInstance().getChanges(publicWorkspaceRoot, sessionId).stream()
                        .anyMatch(c -> (normalizePath(c.path) + "/").startsWith(relDir) && !"DELETE".equals(c.type));
                
                if (hasPendingChildren) {
                     return new EditFileResult(path, false, "directory_has_pending_changes", dryRun, null, null, true);
                }

                if (!dryRun) {
                    String relPath = workspaceRoot.relativize(p).toString().replace('\\', '/');
                    submitPendingChange(relPath, "DELETE", null, null);
                }
                return new EditFileResult(path, true, null, dryRun, null, null, true);
            }
            
            // It's a file (or symlink)
            if (!isOverlayExists(p)) {
                 return new EditFileResult(path, false, "path_not_found", false, null, null, false);
            }
            
            if (isBinary(p)) {
                return new EditFileResult(path, false, "file_is_binary", dryRun, null, null, true);
            }
            String content = getOverlayContent(p);
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES_DEFAULT) {
                return new EditFileResult(path, false, "file_too_large", dryRun, null, null, true);
            }
            if (!dryRun) {
                String relPath = workspaceRoot.relativize(p).toString().replace('\\', '/');
                submitPendingChange(relPath, "DELETE", null, content);
            }
            return new EditFileResult(path, true, null, dryRun, content, null, true);
        } catch (Exception e) {
            return new EditFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), dryRun, null, null, true);
        }
    }
    
    private String normalizePath(String path) {
        if (path == null) return "";
        return path.replace('\\', '/').trim();
    }

    private void submitPendingChange(String relPath, String type, String newContent, String currentOverlayContent) {
        if (directWrite) {
            boolean isDelete = "DELETE".equals(type);
            EditFileResult applied = applyToFile(relPath, newContent, isDelete);
            if (!applied.success) {
                throw new IllegalStateException(applied.error == null ? "apply_failed" : applied.error);
            }
            return;
        }
        String originalContent = currentOverlayContent;
        Optional<PendingChange> existing = PendingChangesManager.getInstance().getPendingChange(relPath, publicWorkspaceRoot, sessionId);
        if (existing.isPresent()) {
            originalContent = existing.get().oldContent;
            // Optimization: If we are deleting a pending CREATE, we could just remove it.
            if ("CREATE".equals(existing.get().type) && "DELETE".equals(type)) {
                PendingChangesManager.getInstance().removeChange(existing.get().id);
                return;
            }
        }
        
        PendingChange change = new PendingChange(
                java.util.UUID.randomUUID().toString(),
                relPath,
                type,
                originalContent,
                newContent,
                null,
                System.currentTimeMillis(),
                publicWorkspaceRoot,
                sessionId
        );
        PendingChangesManager.getInstance().addChange(change);
    }

    public EditFileResult createDirectory(String path, Boolean preview) {
        Path p = resolveUnderWorkspace(path == null ? "" : path);
        if (p == null) {
            return new EditFileResult(path, false, "path_outside_workspace", false, null, null, false);
        }
        if (Files.exists(p)) {
            return new EditFileResult(path, false, "path_already_exists", false, null, null, true);
        }
        boolean dryRun = preview != null && preview.booleanValue();
        if (!dryRun) {
            try {
                Files.createDirectories(p);
                notifyChange(p);
                return new EditFileResult(path, true, null, false, null, null, false);
            } catch (Exception e) {
                return new EditFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), dryRun, null, null, false);
            }
        }
        return new EditFileResult(path, true, null, true, null, null, false);
    }

    public EditFileResult movePath(String sourcePath, String destPath, Boolean preview) {
        Path src = resolveUnderWorkspace(sourcePath == null ? "" : sourcePath);
        if (src == null) {
            return new EditFileResult(sourcePath, false, "source_outside_workspace", false, null, null, false);
        }
        Path dst = resolveUnderWorkspace(destPath == null ? "" : destPath);
        if (dst == null) {
            return new EditFileResult(destPath, false, "dest_outside_workspace", false, null, null, false);
        }
        
        // Check source existence in overlay
        if (!isOverlayExists(src)) {
             return new EditFileResult(sourcePath, false, "source_not_found", false, null, null, false);
        }
        
        // Check dest existence in overlay
        if (isOverlayExists(dst)) {
             return new EditFileResult(destPath, false, "dest_already_exists", false, null, null, true);
        }
        
        Path parent = dst.getParent();
        if (parent == null || !Files.exists(parent)) {
             // We could check pending creates for parent?
             // For now, assume parent must exist on disk or be handled by apply.
             // But applyToFile handles parent creation.
             // So strict check here might be too strict if parent is also being created?
             // But standard move usually requires parent.
             // Let's keep it loose or check disk.
             if (parent != null && !Files.exists(parent)) {
                 // return new EditFileResult(destPath, false, "dest_parent_not_found", false, null, null, false);
                 // Relaxed for Sandbox: applyToFile will create parent dirs.
             }
        }
        
        boolean dryRun = preview != null && preview.booleanValue();
        try {
             boolean isDir = Files.isDirectory(src) && !PendingChangesManager.getInstance().getPendingChange(
                     workspaceRoot.relativize(src).toString().replace('\\', '/'), publicWorkspaceRoot, sessionId).isPresent();
             
             if (isDir) {
            if (!dryRun) {
                // Directory move supported via recursion
                ListFilesResult files = listFiles(sourcePath, "**/*", 10000, 50);
                if (files.error != null) {
                    return new EditFileResult(destPath, false, "list_files_failed:" + files.error, dryRun);
                }
                
                for (String relFile : files.files) {
                    Path srcFile = src.resolve(relFile);
                    Path destFile = dst.resolve(relFile);
                    
                    String relSrcFile = workspaceRoot.relativize(srcFile).toString().replace('\\', '/');
                    String relDestFile = workspaceRoot.relativize(destFile).toString().replace('\\', '/');
                    
                    try {
                        String content = getOverlayContent(srcFile);
                        submitPendingChange(relDestFile, "CREATE", content, null);
                        submitPendingChange(relSrcFile, "DELETE", null, content);
                    } catch (Exception e) {
                        return new EditFileResult(destPath, false, "move_failed_for_file:" + relFile + ":" + e.getMessage(), dryRun);
                    }
                }
            }
            return new EditFileResult(destPath, true, null, dryRun, null, null, false);
        }

             // File Move
             if (isBinary(src)) {
                 // Check if it's binary on disk. 
                 // If it's a new pending file, it's text.
                 // We need to check carefully.
             }
             
             String content = getOverlayContent(src);
             if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES_DEFAULT) {
                 return new EditFileResult(sourcePath, false, "file_too_large", dryRun);
             }

             if (!dryRun) {
                 // 1. Create Destination
                 String relDst = workspaceRoot.relativize(dst).toString().replace('\\', '/');
                 submitPendingChange(relDst, "CREATE", content, null);
                 
                 // 2. Delete Source
                 String relSrc = workspaceRoot.relativize(src).toString().replace('\\', '/');
                 submitPendingChange(relSrc, "DELETE", null, content);
             }
             return new EditFileResult(destPath, true, null, dryRun, null, null, false);
        } catch (Exception e) {
             return new EditFileResult(destPath, false, "io_error:" + e.getClass().getSimpleName(), dryRun, null, null, false);
        }
    }

    public PatchApplyResult applyPatch(String diffText, Boolean preview) {
        boolean dryRun = preview != null && preview.booleanValue();
        if (diffText == null || diffText.trim().isEmpty()) {
            return new PatchApplyResult(false, "diff_required", 0, 0, 0, 0, Collections.emptyList(), "files=0", dryRun);
        }
        List<PatchFile> patches = parseUnifiedDiff(diffText);
        if (patches.isEmpty()) {
            return new PatchApplyResult(false, "diff_parse_failed", 0, 0, 0, 0, Collections.emptyList(), "files=0", dryRun);
        }
        List<PatchFileResult> results = new ArrayList<PatchFileResult>();
        int filesApplied = 0;
        int totalAdded = 0;
        int totalRemoved = 0;
        for (PatchFile pf : patches) {
            String path = pf.newPath;
            boolean isDelete = isDevNull(pf.newPath);
            boolean isCreate = isDevNull(pf.oldPath) && !isDevNull(pf.newPath);
            if (isDelete) {
                path = pf.oldPath;
            }
            if (path == null || path.trim().isEmpty()) {
                results.add(new PatchFileResult("", false, "path_missing", false, false, 0, 0));
                continue;
            }
            Path target = resolveUnderWorkspace(path);
            if (target == null) {
                results.add(new PatchFileResult(path, false, "path_outside_workspace", isCreate, isDelete, 0, 0));
                continue;
            }
            try {
                if (isDelete) {
                    if (!isOverlayExists(target)) {
                        results.add(new PatchFileResult(path, false, "path_not_found", false, true, 0, 0));
                        continue;
                    }
                    if (Files.isDirectory(target)) {
                         // existing logic handles directory delete somewhat, but let's stick to simple file checks
                    } else {
                         if (isBinary(target)) {
                             results.add(new PatchFileResult(path, false, "file_is_binary", false, true, 0, 0));
                             continue;
                         }
                         if (Files.size(target) > MAX_FILE_BYTES_DEFAULT) {
                             results.add(new PatchFileResult(path, false, "file_too_large", false, true, 0, 0));
                             continue;
                         }
                    }
                    String content = getOverlayContent(target);
                    if (!dryRun) {
                        String relPath = workspaceRoot.relativize(target).toString().replace('\\', '/');
                        submitPendingChange(relPath, "DELETE", null, content);
                    }
                    results.add(new PatchFileResult(path, true, null, false, true, 0, 0, content, null));
                    filesApplied++;
                    continue;
                }
                if (!isCreate && (!isOverlayExists(target) || Files.isDirectory(target))) {
                    results.add(new PatchFileResult(path, false, "file_not_found", false, false, 0, 0));
                    continue;
                }
                if (!isCreate) {
                     if (isBinary(target)) {
                         results.add(new PatchFileResult(path, false, "file_is_binary", false, false, 0, 0));
                         continue;
                     }
                     if (Files.size(target) > MAX_FILE_BYTES_DEFAULT) {
                         results.add(new PatchFileResult(path, false, "file_too_large", false, false, 0, 0));
                         continue;
                     }
                }
                if (isCreate) {
                    Path parent = target.getParent();
                    if (parent == null || (!Files.exists(parent) && !isOverlayExists(parent))) {
                        // Relax parent check slightly for sandbox?
                        // If parent is on disk, ok.
                    }
                }
                String content = isCreate ? "" : getOverlayContent(target);
                List<String> oldLines = Arrays.asList(content.split("\n", -1));
                ApplyPatchResult applied = applyHunks(oldLines, pf.hunks);
                if (!applied.success) {
                    results.add(new PatchFileResult(path, false, applied.error, isCreate, false, 0, 0, content, null));
                    continue;
                }
                String updated = String.join("\n", applied.lines);
                if (!dryRun) {
                    String relPath = workspaceRoot.relativize(target).toString().replace('\\', '/');
                    submitPendingChange(relPath, isCreate ? "CREATE" : "EDIT", updated, content);
                }
                results.add(new PatchFileResult(path, true, null, isCreate, false, pf.linesAdded, pf.linesRemoved, content, updated));
                filesApplied++;
                totalAdded += pf.linesAdded;
                totalRemoved += pf.linesRemoved;
            } catch (Exception e) {
                results.add(new PatchFileResult(path, false, "io_error:" + e.getClass().getSimpleName(), isCreate, isDelete, 0, 0));
            }
        }
        boolean success = filesApplied == results.size();
        String error = success ? null : "partial_failure";
        String summary = "files=" + results.size() + " applied=" + filesApplied + " added=" + totalAdded + " removed=" + totalRemoved + " preview=" + dryRun;
        return new PatchApplyResult(success, error, results.size(), filesApplied, totalAdded, totalRemoved, results, summary, dryRun);
    }

    public BatchReplaceResult batchReplace(String rootPath, String glob, String oldText, String newText, Integer maxFiles, Integer maxReplacements, Boolean preview) {
        if (oldText == null || oldText.isEmpty()) {
            return new BatchReplaceResult(false, "old_text_required", 0, 0, 0, false, Collections.emptyList(), "files=0", preview != null && preview.booleanValue());
        }
        if (newText == null) {
            return new BatchReplaceResult(false, "new_text_required", 0, 0, 0, false, Collections.emptyList(), "files=0", preview != null && preview.booleanValue());
        }
        Path root = resolveUnderWorkspace(rootPath == null ? "" : rootPath);
        if (root == null) {
            return new BatchReplaceResult(false, "path_outside_workspace", 0, 0, 0, false, Collections.emptyList(), "files=0", preview != null && preview.booleanValue());
        }
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            // It might be that root does not exist on disk but exists in overlay (if we created a dir structure in pending?)
            // But we don't support pending dirs yet.
            // So check disk.
            return new BatchReplaceResult(false, "path_not_a_directory", 0, 0, 0, false, Collections.emptyList(), "files=0", preview != null && preview.booleanValue());
        }
        String useGlob = glob == null || glob.trim().isEmpty() ? "**/*" : glob.trim();
        PathMatcher matcher = root.getFileSystem().getPathMatcher("glob:" + useGlob);
        int fileLimit = maxFiles == null || maxFiles.intValue() <= 0 ? 2000 : Math.min(maxFiles.intValue(), 20000);
        int replLimit = maxReplacements == null || maxReplacements.intValue() <= 0 ? 20000 : Math.min(maxReplacements.intValue(), 200000);
        boolean dryRun = preview != null && preview.booleanValue();
        List<BatchReplaceItem> items = new ArrayList<BatchReplaceItem>();
        final int[] filesScanned = new int[] {0};
        final int[] filesChanged = new int[] {0};
        final int[] replacements = new int[] {0};
        final boolean[] truncated = new boolean[] {false};
        
        Set<String> processedPaths = new java.util.HashSet<>();
        
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (shouldSkipDir(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (filesScanned[0] >= fileLimit) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    Path rel = root.relativize(file);
                    if (!matcher.matches(rel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs == null || !attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    String relUnix = rel.toString().replace('\\', '/');
                    // Check absolute relative to workspace for tracking
                    String workspaceRel = workspaceRoot.relativize(file).toString().replace('\\', '/');
                    
                    if (!isIndexablePath(workspaceRel)) {
                        return FileVisitResult.CONTINUE;
                    }
                    
                    // Check overlay existence (handle deleted files)
                    if (!isOverlayExists(file)) {
                        processedPaths.add(workspaceRel); // Mark as processed so we don't try to find it in pending loop (though deleted pending won't show up anyway)
                        return FileVisitResult.CONTINUE;
                    }
                    
                    processedPaths.add(workspaceRel);
                    filesScanned[0]++;
                    try {
                        if (isBinary(file)) {
                            items.add(new BatchReplaceItem(relUnix, 0, 0, 0, "file_is_binary"));
                            return FileVisitResult.CONTINUE;
                        }
                        
                        String content = getOverlayContent(file);
                        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_BYTES_DEFAULT) {
                            items.add(new BatchReplaceItem(relUnix, 0, 0, 0, "file_too_large"));
                            return FileVisitResult.CONTINUE;
                        }
                        
                        int count = countOccurrences(content, oldText);
                        if (count <= 0) {
                            return FileVisitResult.CONTINUE;
                        }
                        int beforeLines = countLinesFromContent(content);
                        String updated = content.replace(oldText, newText);
                        int afterLines = countLinesFromContent(updated);
                        if (!dryRun) {
                            submitPendingChange(workspaceRel, "EDIT", updated, content);
                        }
                        items.add(new BatchReplaceItem(relUnix, count, beforeLines, afterLines, null, content, updated));
                        filesChanged[0]++;
                        replacements[0] += count;
                        if (replacements[0] >= replLimit) {
                            truncated[0] = true;
                            return FileVisitResult.TERMINATE;
                        }
                    } catch (Exception e) {
                        items.add(new BatchReplaceItem(relUnix, 0, 0, 0, "io_error:" + e.getClass().getSimpleName()));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            
            // Scan pending new files that are NOT on disk (or skipped by walkFileTree)
            if (!truncated[0]) {
                List<PendingChange> pendingChanges = PendingChangesManager.getInstance().getChanges(publicWorkspaceRoot, sessionId);
                for (PendingChange pc : pendingChanges) {
                    if ("DELETE".equals(pc.type)) continue;
                    if (processedPaths.contains(pc.path)) continue; // Already processed
                    
                    // Check if inside root
                    Path absPath = workspaceRoot.resolve(pc.path);
                    if (!absPath.startsWith(root)) continue;
                    
                    Path rel = root.relativize(absPath);
                    if (!matcher.matches(rel)) continue;
                    
                    if (!isIndexablePath(pc.path)) continue;
                    
                    if (filesScanned[0] >= fileLimit) {
                        truncated[0] = true;
                        break;
                    }
                    
                    filesScanned[0]++;
                    // Process pending file
                    String content = pc.newContent;
                    // Binary check? Assume text for pending.
                    int count = countOccurrences(content, oldText);
                    if (count <= 0) continue;
                    
                    int beforeLines = countLinesFromContent(content);
                    String updated = content.replace(oldText, newText);
                    int afterLines = countLinesFromContent(updated);
                    
                    if (!dryRun) {
                         submitPendingChange(pc.path, "EDIT", updated, content);
                    }
                    String relUnix = rel.toString().replace('\\', '/');
                    items.add(new BatchReplaceItem(relUnix, count, beforeLines, afterLines, null, content, updated));
                    filesChanged[0]++;
                    replacements[0] += count;
                    if (replacements[0] >= replLimit) {
                        truncated[0] = true;
                        break;
                    }
                }
            }
            
        } catch (IOException e) {
            return new BatchReplaceResult(false, "io_error:" + e.getClass().getSimpleName(), filesScanned[0], filesChanged[0], replacements[0], truncated[0], items, "files=" + filesScanned[0], dryRun);
        }
        String summary = "files=" + filesScanned[0] + " changed=" + filesChanged[0] + " replacements=" + replacements[0] + " preview=" + dryRun;
        return new BatchReplaceResult(true, null, filesScanned[0], filesChanged[0], replacements[0], truncated[0], items, summary, dryRun);
    }

    public Integer findFirstLineContaining(String path, String needle) {
        if (needle == null || needle.isEmpty()) {
            return null;
        }
        Path file = resolveFileUnderWorkspace(path == null ? "" : path);
        if (file == null) {
            return null;
        }
        // Check overlay existence (handles pending deletes)
        if (!isOverlayExists(file)) {
            return null;
        }
        if (Files.isDirectory(file)) {
            return null;
        }

        String relPath = workspaceRoot.relativize(file).toString().replace('\\', '/');
        Optional<PendingChangesManager.PendingChange> pending = PendingChangesManager.getInstance().getPendingChange(relPath, publicWorkspaceRoot, sessionId);

        try (BufferedReader br = pending.isPresent() && pending.get().newContent != null 
                ? new BufferedReader(new java.io.StringReader(pending.get().newContent))
                : Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (line.contains(needle)) {
                    return Integer.valueOf(lineNo);
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static int countOccurrences(String content, String needle) {
        if (content == null || content.isEmpty() || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while (idx <= content.length()) {
            int hit = content.indexOf(needle, idx);
            if (hit < 0) {
                break;
            }
            count++;
            idx = hit + needle.length();
        }
        return count;
    }

    private static int countLinesFromContent(String content) {
        if (content == null || content.isEmpty()) {
            return 0;
        }
        int count = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private boolean isBinary(Path file) {
        try {
            if (Files.size(file) == 0) return false;
            try (java.io.InputStream is = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read = is.read(buffer);
                if (read <= 0) return false;
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == 0) return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isDevNull(String path) {
        if (path == null) {
            return false;
        }
        String p = path.trim();
        return "/dev/null".equals(p) || "dev/null".equals(p);
    }

    private List<PatchFile> parseUnifiedDiff(String diffText) {
        String normalized = diffText.replace("\r\n", "\n");
        List<String> lines = Arrays.asList(normalized.split("\n", -1));
        List<PatchFile> files = new ArrayList<PatchFile>();
        PatchFile current = null;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("diff --git ")) {
                current = new PatchFile();
                parseDiffGitLine(current, line);
                files.add(current);
                continue;
            }
            if (line.startsWith("--- ")) {
                if (current == null) {
                    current = new PatchFile();
                    files.add(current);
                }
                current.oldPath = normalizeDiffPath(extractPathFromHeader(line, "--- "));
                continue;
            }
            if (line.startsWith("+++ ")) {
                if (current == null) {
                    current = new PatchFile();
                    files.add(current);
                }
                current.newPath = normalizeDiffPath(extractPathFromHeader(line, "+++ "));
                continue;
            }
            if (line.startsWith("@@ ")) {
                if (current == null) {
                    current = new PatchFile();
                    files.add(current);
                }
                PatchHunk hunk = parseHunkHeader(line);
                if (hunk == null) {
                    continue;
                }
                current.hunks.add(hunk);
                for (i = i + 1; i < lines.size(); i++) {
                    String h = lines.get(i);
                    if (h.startsWith("@@ ") || h.startsWith("diff --git ") || h.startsWith("--- ") || h.startsWith("+++ ")) {
                        i = i - 1;
                        break;
                    }
                    if (h.startsWith("\\ No newline at end of file")) {
                        continue;
                    }
                    char t = h.isEmpty() ? ' ' : h.charAt(0);
                    if (t != ' ' && t != '+' && t != '-') {
                        continue;
                    }
                    String text = h.isEmpty() ? "" : h.substring(1);
                    hunk.lines.add(new PatchLine(t, text));
                    if (t == '+') {
                        current.linesAdded++;
                    } else if (t == '-') {
                        current.linesRemoved++;
                    }
                }
            }
        }
        List<PatchFile> out = new ArrayList<PatchFile>();
        for (PatchFile pf : files) {
            if (!pf.hunks.isEmpty()) {
                out.add(pf);
            }
        }
        return out;
    }

    private void parseDiffGitLine(PatchFile pf, String line) {
        String[] parts = line.trim().split("\\s+");
        if (parts.length >= 4) {
            pf.oldPath = normalizeDiffPath(parts[2]);
            pf.newPath = normalizeDiffPath(parts[3]);
        }
    }

    private String extractPathFromHeader(String line, String prefix) {
        String p = line.substring(prefix.length()).trim();
        int tab = p.indexOf('\t');
        int space = p.indexOf(' ');
        int cut = -1;
        if (tab >= 0 && space >= 0) {
            cut = Math.min(tab, space);
        } else if (tab >= 0) {
            cut = tab;
        } else if (space >= 0) {
            cut = space;
        }
        if (cut > 0) {
            p = p.substring(0, cut);
        }
        return p.trim();
    }

    private String normalizeDiffPath(String path) {
        if (path == null) {
            return null;
        }
        String p = path.trim();
        if (p.startsWith("a/") || p.startsWith("b/")) {
            p = p.substring(2);
        }
        return p;
    }

    private PatchHunk parseHunkHeader(String line) {
        Matcher m = Pattern.compile("^@@\\s+-(\\d+)(?:,(\\d+))?\\s+\\+(\\d+)(?:,(\\d+))?\\s+@@.*$").matcher(line);
        if (!m.matches()) {
            return null;
        }
        int oldStart = Integer.parseInt(m.group(1));
        int oldCount = m.group(2) == null || m.group(2).isEmpty() ? 1 : Integer.parseInt(m.group(2));
        int newStart = Integer.parseInt(m.group(3));
        int newCount = m.group(4) == null || m.group(4).isEmpty() ? 1 : Integer.parseInt(m.group(4));
        return new PatchHunk(oldStart, oldCount, newStart, newCount);
    }

    private ApplyPatchResult applyHunks(List<String> oldLines, List<PatchHunk> hunks) {
        List<String> out = new ArrayList<String>();
        int cursor = 0;
        for (PatchHunk h : hunks) {
            int target = Math.max(0, h.oldStart - 1);
            while (cursor < target && cursor < oldLines.size()) {
                out.add(oldLines.get(cursor));
                cursor++;
            }
            for (PatchLine pl : h.lines) {
                if (pl.type == ' ') {
                    if (cursor >= oldLines.size() || !linesMatch(oldLines.get(cursor), pl.text)) {
                        return ApplyPatchResult.error("context_mismatch");
                    }
                    out.add(oldLines.get(cursor));
                    cursor++;
                } else if (pl.type == '-') {
                    if (cursor >= oldLines.size() || !linesMatch(oldLines.get(cursor), pl.text)) {
                        return ApplyPatchResult.error("delete_mismatch");
                    }
                    cursor++;
                } else if (pl.type == '+') {
                    out.add(pl.text);
                }
            }
        }
        while (cursor < oldLines.size()) {
            out.add(oldLines.get(cursor));
            cursor++;
        }
        return ApplyPatchResult.ok(out);
    }

    private boolean linesMatch(String a, String b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        return normalizeWhitespace(a).equals(normalizeWhitespace(b));
    }

    private String normalizeWhitespace(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    public static boolean isIndexableExt(String ext) {
        if (ext == null || ext.trim().isEmpty()) {
            return false;
        }
        return INDEXABLE_EXTS.contains(ext.trim().toLowerCase());
    }

    public static boolean isIndexablePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return false;
        }
        String s = path.trim().replace('\\', '/');
        int lastSlash = s.lastIndexOf('/');
        String name = lastSlash >= 0 ? s.substring(lastSlash + 1) : s;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        String ext = name.substring(dot + 1);
        return isIndexableExt(ext);
    }

    public static boolean shouldSkipDir(Path dir) {
        if (dir == null || dir.getFileName() == null) {
            return false;
        }
        String name = dir.getFileName().toString();
        return ".git".equalsIgnoreCase(name)
                || ".idea".equalsIgnoreCase(name)
                || ".gradle".equalsIgnoreCase(name)
                || ".codeagent".equalsIgnoreCase(name)
                || "node_modules".equalsIgnoreCase(name)
                || "build".equalsIgnoreCase(name)
                || "out".equalsIgnoreCase(name)
                || "target".equalsIgnoreCase(name)
                || "dist".equalsIgnoreCase(name)
                || ".vs".equalsIgnoreCase(name);
    }

    private Path resolveUnderWorkspace(String pathOrEmpty) {
        Path base = workspaceRoot;
        if (base == null || base.toString().trim().isEmpty()) {
            return null;
        }
        String s = pathOrEmpty == null ? "" : pathOrEmpty.trim();
        Path p;
        if (s.isEmpty()) {
            p = base;
        } else {
            Path raw = Path.of(s);
            p = raw.isAbsolute() ? raw : base.resolve(raw);
        }
        p = p.toAbsolutePath().normalize();
        if (!p.startsWith(base)) {
            return null;
        }
        return p;
    }

    private Path resolveFileUnderWorkspace(String pathOrEmpty) {
        Path p = resolveUnderWorkspace(pathOrEmpty);
        if (p == null) {
            return null;
        }
        if (Files.isDirectory(p)) {
            return null;
        }
        return p;
    }

    private void pushHistory(Path path, FileSnapshot snap) {
        if (path == null || snap == null) {
            return;
        }
        Deque<FileSnapshot> stack = editHistory.computeIfAbsent(path, k -> new ArrayDeque<FileSnapshot>());
        if (stack.size() >= MAX_EDIT_HISTORY) {
            stack.removeFirst();
        }
        stack.addLast(snap);
    }

    private FileSnapshot popHistory(Path path) {
        if (path == null) {
            return null;
        }
        Deque<FileSnapshot> stack = editHistory.get(path);
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return stack.removeLast();
    }

    private static void scanOneFile(Path file, String relUnix, Pattern re, int matchLimit, int contextLines, List<GrepMatch> out, String overlayContent) throws IOException {
        List<String> prev = contextLines <= 0 ? Collections.emptyList() : new ArrayList<String>();
        
        Reader reader;
        if (overlayContent != null) {
            reader = new StringReader(overlayContent);
        } else {
            reader = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8);
        }

        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                Matcher m = re.matcher(line);
                if (m.find()) {
                    List<String> before = contextLines <= 0 ? Collections.emptyList() : new ArrayList<String>(prev);
                    out.add(new GrepMatch(relUnix, lineNo, line, before, Collections.emptyList()));
                    if (out.size() >= matchLimit) {
                        return;
                    }
                }
                if (contextLines > 0) {
                    if (prev.size() >= contextLines) {
                        prev.remove(0);
                    }
                    prev.add(line);
                }
            }
        }
    }

    private int countLines(Path file) {
        int lines = 0;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            while (br.readLine() != null) {
                lines++;
            }
        } catch (Exception e) {
            return -1;
        }
        return lines;
    }

    private ReadWindow readWindow(Path file, int start, int end, int maxChars) {
        try {
            String relUnix = workspaceRoot.relativize(file).toString().replace('\\', '/');
            Optional<PendingChange> pending = PendingChangesManager.getInstance().getPendingChange(relUnix, publicWorkspaceRoot, sessionId);
            if (pending.isPresent()) {
                 if ("DELETE".equals(pending.get().type)) {
                     return new ReadWindow("", false, "not_a_file");
                 }
                 String content = pending.get().newContent;
                 StringBuilder sb = new StringBuilder();
                 boolean truncated = false;
                 String[] lines = content.split("\n", -1);
                 int lineNo = 0;
                 for (String line : lines) {
                     lineNo++;
                     if (lineNo < start) continue;
                     if (lineNo > end) break;
                     String row = lineNo + "→" + line + "\n";
                     if (sb.length() + row.length() > maxChars) {
                         truncated = true;
                         break;
                     }
                     sb.append(row);
                 }
                 return new ReadWindow(sb.toString(), truncated, null);
            }
        } catch (Exception e) {
            // ignore
        }

        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNo = 0;
            while ((line = br.readLine()) != null) {
                lineNo++;
                if (lineNo < start) {
                    continue;
                }
                if (lineNo > end) {
                    break;
                }
                String row = lineNo + "→" + line + "\n";
                if (sb.length() + row.length() > maxChars) {
                    truncated = true;
                    break;
                }
                sb.append(row);
            }
        } catch (Exception e) {
            return new ReadWindow("", true, "io_error:" + e.getClass().getSimpleName());
        }
        return new ReadWindow(sb.toString(), truncated, null);
    }

    private static void addPath(TreeNode root, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        String clean = path.replace('\\', '/');
        boolean isDir = clean.endsWith("/");
        String[] parts = clean.split("/");
        TreeNode cur = root;
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i];
            if (name == null || name.isEmpty()) {
                continue;
            }
            boolean leaf = i == parts.length - 1;
            TreeNode child = cur.children.get(name);
            if (child == null) {
                child = new TreeNode(name);
                cur.children.put(name, child);
            }
            if (leaf && !isDir) {
                child.isFile = true;
            }
            cur = child;
        }
    }

    private static void renderTree(TreeNode node, StringBuilder sb, int depth, int maxDepth, int maxChars, boolean[] truncated) {
        if (node == null) {
            return;
        }
        if (depth > 0) {
            if (depth > maxDepth) {
                return;
            }
            for (int i = 1; i < depth; i++) {
                sb.append("  ");
            }
            sb.append(node.name);
            if (!node.isFile) {
                sb.append("/");
            }
            sb.append("\n");
            if (sb.length() >= maxChars) {
                truncated[0] = true;
                return;
            }
        }
        if (node.children.isEmpty()) {
            return;
        }
        List<TreeNode> children = new ArrayList<TreeNode>(node.children.values());
        children.sort((a, b) -> {
            int cmp = Double.compare(b.score, a.score);
            if (cmp != 0) {
                return cmp;
            }
            return a.name.compareTo(b.name);
        });
        for (TreeNode child : children) {
            renderTree(child, sb, depth + 1, maxDepth, maxChars, truncated);
            if (truncated[0]) {
                return;
            }
        }
    }

    private Map<String, Double> rankFiles(Path root, List<String> filePaths, Set<String> focusRelPaths) {
        Map<String, Double> ranks = new HashMap<String, Double>();
        if (filePaths == null || filePaths.isEmpty()) {
            return ranks;
        }
        Map<String, List<String>> baseNameMap = new HashMap<String, List<String>>();
        Map<String, List<String>> noExtMap = new HashMap<String, List<String>>();
        Set<String> fileSet = new HashSet<String>(filePaths);
        for (String rel : filePaths) {
            String base = baseNameWithoutExt(rel);
            if (!base.isEmpty()) {
                baseNameMap.computeIfAbsent(base, k -> new ArrayList<String>()).add(rel);
            }
            String noExt = removeExtension(rel);
            if (!noExt.isEmpty()) {
                noExtMap.computeIfAbsent(noExt, k -> new ArrayList<String>()).add(rel);
            }
        }
        Map<String, Map<String, Integer>> edges = new HashMap<String, Map<String, Integer>>();
        for (String rel : filePaths) {
            Set<String> deps = extractDependencies(root, rel, baseNameMap, noExtMap, fileSet);
            if (deps.isEmpty()) {
                continue;
            }
            Map<String, Integer> out = edges.computeIfAbsent(rel, k -> new HashMap<String, Integer>());
            for (String dep : deps) {
                if (dep.equals(rel)) {
                    continue;
                }
                out.put(dep, out.getOrDefault(dep, 0) + 1);
            }
        }
        ranks.putAll(pageRank(filePaths, edges));
        boostImportantFiles(ranks, filePaths);
        boostFocusFiles(ranks, focusRelPaths);
        return ranks;
    }

    private Set<String> extractDependencies(Path root, String relPath, Map<String, List<String>> baseNameMap, Map<String, List<String>> noExtMap, Set<String> fileSet) {
        Set<String> deps = new HashSet<String>();
        if (relPath == null || relPath.isEmpty()) {
            return deps;
        }
        Path abs = root.resolve(relPath);
        try {
            if (Files.size(abs) > MAX_FILE_BYTES_DEFAULT) {
                return deps;
            }
        } catch (Exception e) {
            return deps;
        }
        String parent = "";
        int idx = relPath.lastIndexOf('/');
        if (idx >= 0) {
            parent = relPath.substring(0, idx);
        }
        try (BufferedReader br = Files.newBufferedReader(abs, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                collectDepsFromLine(line, deps, parent, relPath, baseNameMap, noExtMap, fileSet);
            }
        } catch (Exception e) {
            return deps;
        }
        return deps;
    }

    private void collectDepsFromLine(String line, Set<String> deps, String parent, String relPath, Map<String, List<String>> baseNameMap, Map<String, List<String>> noExtMap, Set<String> fileSet) {
        if (line == null || line.isEmpty()) {
            return;
        }
        collectDeps(IMPORT_FROM_PATTERN, line, deps, parent, relPath, baseNameMap, noExtMap, fileSet);
        if (!line.contains(" from ")) {
            collectDeps(IMPORT_SIMPLE_PATTERN, line, deps, parent, relPath, baseNameMap, noExtMap, fileSet);
        }
        collectDeps(JS_IMPORT_PATTERN, line, deps, parent, relPath, baseNameMap, noExtMap, fileSet);
        collectDeps(REQUIRE_PATTERN, line, deps, parent, relPath, baseNameMap, noExtMap, fileSet);
        collectDeps(INCLUDE_PATTERN, line, deps, parent, relPath, baseNameMap, noExtMap, fileSet);
        collectDeps(USING_PATTERN, line, deps, parent, relPath, baseNameMap, noExtMap, fileSet);
        collectDeps(RUST_USE_PATTERN, line, deps, parent, relPath, baseNameMap, noExtMap, fileSet);
        collectDeps(GO_IMPORT_PATTERN, line, deps, parent, relPath, baseNameMap, noExtMap, fileSet);
    }

    private void collectDeps(Pattern pattern, String line, Set<String> deps, String parent, String relPath, Map<String, List<String>> baseNameMap, Map<String, List<String>> noExtMap, Set<String> fileSet) {
        Matcher m = pattern.matcher(line);
        while (m.find()) {
            String raw = m.group(1);
            if (raw == null) {
                continue;
            }
            String dep = raw.replace("\"", "").replace("'", "").trim();
            if (dep.isEmpty()) {
                continue;
            }
            deps.addAll(resolveDependency(dep, parent, relPath, baseNameMap, noExtMap, fileSet));
        }
    }

    private Set<String> resolveDependency(String dep, String parent, String relPath, Map<String, List<String>> baseNameMap, Map<String, List<String>> noExtMap, Set<String> fileSet) {
        Set<String> out = new HashSet<String>();
        String clean = dep.trim();
        if (clean.isEmpty()) {
            return out;
        }
        clean = clean.replaceAll("[;,)]+$", "");
        clean = clean.replace("::", ".");
        if (clean.startsWith(".") || clean.startsWith("/") || clean.contains("/")) {
            String candidate = clean;
            if (candidate.startsWith("./") || candidate.startsWith("../")) {
                Path base = parent == null || parent.isEmpty() ? Path.of("") : Path.of(parent);
                candidate = base.resolve(candidate).normalize().toString().replace('\\', '/');
            } else if (candidate.startsWith("/")) {
                candidate = candidate.substring(1);
            }
            String noExt = removeExtension(candidate);
            if (fileSet.contains(candidate)) {
                out.add(candidate);
                return out;
            }
            List<String> mapped = noExtMap.get(noExt);
            if (mapped != null) {
                out.addAll(mapped);
                return out;
            }
        }
        if (clean.contains(".")) {
            String pathLike = clean.replace('.', '/');
            List<String> mapped = noExtMap.get(pathLike);
            if (mapped != null) {
                out.addAll(mapped);
                return out;
            }
            String base = baseName(clean);
            List<String> byBase = baseNameMap.get(base);
            if (byBase != null) {
                out.addAll(byBase);
                return out;
            }
        }
        String base = baseName(clean);
        List<String> byBase = baseNameMap.get(base);
        if (byBase != null) {
            out.addAll(byBase);
        }
        return out;
    }

    private Map<String, Double> pageRank(List<String> nodes, Map<String, Map<String, Integer>> edges) {
        Map<String, Double> ranks = new HashMap<String, Double>();
        int n = nodes.size();
        if (n == 0) {
            return ranks;
        }
        double init = 1.0d / n;
        for (String node : nodes) {
            ranks.put(node, init);
        }
        double damping = 0.85d;
        for (int i = 0; i < 20; i++) {
            Map<String, Double> next = new HashMap<String, Double>();
            for (String node : nodes) {
                next.put(node, (1.0d - damping) / n);
            }
            for (String src : nodes) {
                Map<String, Integer> out = edges.get(src);
                double srcRank = ranks.getOrDefault(src, init);
                if (out == null || out.isEmpty()) {
                    double share = damping * srcRank / n;
                    for (String node : nodes) {
                        next.put(node, next.get(node) + share);
                    }
                } else {
                    int total = 0;
                    for (int weight : out.values()) {
                        total += weight;
                    }
                    if (total <= 0) {
                        continue;
                    }
                    for (Map.Entry<String, Integer> entry : out.entrySet()) {
                        String dst = entry.getKey();
                        int weight = entry.getValue();
                        double add = damping * srcRank * weight / total;
                        next.put(dst, next.getOrDefault(dst, 0.0d) + add);
                    }
                }
            }
            ranks = next;
        }
        return ranks;
    }

    private void boostImportantFiles(Map<String, Double> ranks, List<String> filePaths) {
        if (ranks == null || filePaths == null) {
            return;
        }
        Set<String> special = Set.of(
                "README.md", "readme.md", "package.json", "build.gradle", "build.gradle.kts",
                "settings.gradle", "settings.gradle.kts", "pom.xml", "pyproject.toml",
                "requirements.txt", "Cargo.toml", "go.mod", "gradlew", "gradlew.bat"
        );
        for (String rel : filePaths) {
            String name = fileName(rel);
            if (special.contains(name)) {
                ranks.put(rel, ranks.getOrDefault(rel, 0.0d) + 0.05d);
            }
        }
    }

    private void boostFocusFiles(Map<String, Double> ranks, Set<String> focusRelPaths) {
        if (ranks == null || focusRelPaths == null || focusRelPaths.isEmpty()) {
            return;
        }
        for (String rel : focusRelPaths) {
            if (rel == null || rel.isEmpty()) {
                continue;
            }
            ranks.put(rel, ranks.getOrDefault(rel, 0.0d) + 0.2d);
        }
    }

    private Set<String> normalizeFocusPaths(Path root, Set<String> focusPaths) {
        if (root == null || focusPaths == null || focusPaths.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new HashSet<String>();
        for (String p : focusPaths) {
            if (p == null || p.trim().isEmpty()) {
                continue;
            }
            Path raw = Path.of(p.trim());
            Path abs = raw.isAbsolute() ? raw : root.resolve(raw);
            abs = abs.toAbsolutePath().normalize();
            if (!abs.startsWith(root)) {
                continue;
            }
            String rel = root.relativize(abs).toString().replace('\\', '/');
            if (!rel.isEmpty()) {
                out.add(rel);
            }
        }
        return out;
    }

    private static double applyScores(TreeNode node, String prefix, Map<String, Double> ranks) {
        double max = 0.0d;
        for (TreeNode child : node.children.values()) {
            String childPath = prefix.isEmpty() ? child.name : prefix + "/" + child.name;
            double childScore = applyScores(child, childPath, ranks);
            if (childScore > max) {
                max = childScore;
            }
        }
        if (node.isFile) {
            Double score = ranks.get(prefix);
            if (score != null && score > max) {
                max = score;
            }
        }
        node.score = max;
        return max;
    }

    private static String baseName(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String clean = path.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        String name = slash >= 0 ? clean.substring(slash + 1) : clean;
        return name;
    }

    private static String fileName(String path) {
        return baseName(path);
    }

    private static String baseNameWithoutExt(String path) {
        String name = baseName(path);
        int dot = name.lastIndexOf('.');
        if (dot <= 0) {
            return name;
        }
        return name.substring(0, dot);
    }

    private static String removeExtension(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String clean = path.replace('\\', '/');
        int slash = clean.lastIndexOf('/');
        int dot = clean.lastIndexOf('.');
        if (dot <= 0 || (slash >= 0 && dot < slash)) {
            return clean;
        }
        return clean.substring(0, dot);
    }

    public static final class ListFilesResult {
        public final List<String> files;
        public final boolean truncated;
        public final String error;

        public ListFilesResult(List<String> files, boolean truncated, String error) {
            this.files = files == null ? Collections.emptyList() : files;
            this.truncated = truncated;
            this.error = error;
        }
    }

    public static final class GrepResult {
        public final List<GrepMatch> matches;
        public final boolean truncated;
        public final String error;

        public GrepResult(List<GrepMatch> matches, boolean truncated, String error) {
            this.matches = matches == null ? Collections.emptyList() : matches;
            this.truncated = truncated;
            this.error = error;
        }
    }

    public static final class GrepMatch {
        public final String filePath;
        public final int line;
        public final String text;
        public final List<String> before;
        public final List<String> after;

        public GrepMatch(String filePath, int line, String text, List<String> before, List<String> after) {
            this.filePath = filePath;
            this.line = line;
            this.text = text;
            this.before = before == null ? Collections.emptyList() : before;
            this.after = after == null ? Collections.emptyList() : after;
        }
    }

    public static final class ReadFileResult {
        public final String filePath;
        public final int startLine;
        public final int endLine;
        public final boolean truncated;
        public final String content;
        public final String error;

        public ReadFileResult(String filePath, int startLine, int endLine, boolean truncated, String content, String error) {
            this.filePath = filePath;
            this.startLine = startLine;
            this.endLine = endLine;
            this.truncated = truncated;
            this.content = content;
            this.error = error;
        }
    }

    public static final class FileViewResult {
        public final String filePath;
        public final int startLine;
        public final int endLine;
        public final int totalLines;
        public final boolean truncated;
        public final String content;
        public final String error;
        public final boolean hasMoreAbove;
        public final boolean hasMoreBelow;
        public final int window;

        public FileViewResult(String filePath, int startLine, int endLine, int totalLines, boolean truncated, String content, String error, boolean hasMoreAbove, boolean hasMoreBelow, int window) {
            this.filePath = filePath;
            this.startLine = startLine;
            this.endLine = endLine;
            this.totalLines = totalLines;
            this.truncated = truncated;
            this.content = content;
            this.error = error;
            this.hasMoreAbove = hasMoreAbove;
            this.hasMoreBelow = hasMoreBelow;
            this.window = window;
        }
    }

    public static final class FileSearchResult {
        public final String filePath;
        public final int matches;
        public final int lines;
        public final List<FileSearchMatch> hits;
        public final boolean truncated;
        public final String error;

        public FileSearchResult(String filePath, int matches, int lines, List<FileSearchMatch> hits, boolean truncated, String error) {
            this.filePath = filePath;
            this.matches = matches;
            this.lines = lines;
            this.hits = hits == null ? Collections.emptyList() : hits;
            this.truncated = truncated;
            this.error = error;
        }
    }

    public static final class FileSearchMatch {
        public final int line;
        public final String text;

        public FileSearchMatch(int line, String text) {
            this.line = line;
            this.text = text;
        }
    }

    public static final class PatchApplyResult {
        public final boolean success;
        public final String error;
        public final int files;
        public final int filesApplied;
        public final int linesAdded;
        public final int linesRemoved;
        public final List<PatchFileResult> results;
        public final String summary;
        public final boolean preview;

        public PatchApplyResult(boolean success, String error, int files, int filesApplied, int linesAdded, int linesRemoved, List<PatchFileResult> results, String summary, boolean preview) {
            this.success = success;
            this.error = error;
            this.files = files;
            this.filesApplied = filesApplied;
            this.linesAdded = linesAdded;
            this.linesRemoved = linesRemoved;
            this.results = results == null ? Collections.emptyList() : results;
            this.summary = summary == null ? "" : summary;
            this.preview = preview;
        }
    }

    public static final class PatchFileResult {
        public final String filePath;
        public final boolean success;
        public final String error;
        public final boolean created;
        public final boolean deleted;
        public final int linesAdded;
        public final int linesRemoved;
        public final String oldContent;
        public final String newContent;

        public PatchFileResult(String filePath, boolean success, String error, boolean created, boolean deleted, int linesAdded, int linesRemoved) {
            this(filePath, success, error, created, deleted, linesAdded, linesRemoved, null, null);
        }

        public PatchFileResult(String filePath, boolean success, String error, boolean created, boolean deleted, int linesAdded, int linesRemoved, String oldContent, String newContent) {
            this.filePath = filePath;
            this.success = success;
            this.error = error;
            this.created = created;
            this.deleted = deleted;
            this.linesAdded = linesAdded;
            this.linesRemoved = linesRemoved;
            this.oldContent = oldContent;
            this.newContent = newContent;
        }
    }

    public static final class BatchReplaceResult {
        public final boolean success;
        public final String error;
        public final int filesScanned;
        public final int filesChanged;
        public final int replacements;
        public final boolean truncated;
        public final List<BatchReplaceItem> items;
        public final String summary;
        public final boolean preview;

        public BatchReplaceResult(boolean success, String error, int filesScanned, int filesChanged, int replacements, boolean truncated, List<BatchReplaceItem> items, String summary, boolean preview) {
            this.success = success;
            this.error = error;
            this.filesScanned = filesScanned;
            this.filesChanged = filesChanged;
            this.replacements = replacements;
            this.truncated = truncated;
            this.items = items == null ? Collections.emptyList() : items;
            this.summary = summary == null ? "" : summary;
            this.preview = preview;
        }
    }

    public static final class BatchReplaceItem {
        public final String filePath;
        public final int replacements;
        public final int beforeLines;
        public final int afterLines;
        public final String error;
        public final String oldContent;
        public final String newContent;

        public BatchReplaceItem(String filePath, int replacements, int beforeLines, int afterLines, String error) {
            this(filePath, replacements, beforeLines, afterLines, error, null, null);
        }

        public BatchReplaceItem(String filePath, int replacements, int beforeLines, int afterLines, String error, String oldContent, String newContent) {
            this.filePath = filePath;
            this.replacements = replacements;
            this.beforeLines = beforeLines;
            this.afterLines = afterLines;
            this.error = error;
            this.oldContent = oldContent;
            this.newContent = newContent;
        }
    }

    private static final class PatchFile {
        private String oldPath;
        private String newPath;
        private final List<PatchHunk> hunks = new ArrayList<PatchHunk>();
        private int linesAdded;
        private int linesRemoved;
    }

    private static final class PatchHunk {
        private final int oldStart;
        private final int oldCount;
        private final int newStart;
        private final int newCount;
        private final List<PatchLine> lines = new ArrayList<PatchLine>();

        private PatchHunk(int oldStart, int oldCount, int newStart, int newCount) {
            this.oldStart = oldStart;
            this.oldCount = oldCount;
            this.newStart = newStart;
            this.newCount = newCount;
        }
    }

    private static final class PatchLine {
        private final char type;
        private final String text;

        private PatchLine(char type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    private static final class ApplyPatchResult {
        private final boolean success;
        private final String error;
        private final List<String> lines;

        private ApplyPatchResult(boolean success, String error, List<String> lines) {
            this.success = success;
            this.error = error;
            this.lines = lines == null ? Collections.emptyList() : lines;
        }

        private static ApplyPatchResult ok(List<String> lines) {
            return new ApplyPatchResult(true, null, lines);
        }

        private static ApplyPatchResult error(String error) {
            return new ApplyPatchResult(false, error, Collections.emptyList());
        }
    }

    public static final class RepoMapResult {
        public final String content;
        public final boolean truncated;
        public final int totalFiles;
        public final String error;

        public RepoMapResult(String content, boolean truncated, int totalFiles, String error) {
            this.content = content == null ? "" : content;
            this.truncated = truncated;
            this.totalFiles = totalFiles;
            this.error = error;
        }
    }

    private static final class ReadWindow {
        public final String content;
        public final boolean truncated;
        public final String error;

        private ReadWindow(String content, boolean truncated, String error) {
            this.content = content == null ? "" : content;
            this.truncated = truncated;
            this.error = error;
        }
    }

    private static final class TreeNode {
        public final String name;
        public final Map<String, TreeNode> children = new HashMap<String, TreeNode>();
        public boolean isFile;
        public double score;

        private TreeNode(String name) {
            this.name = name == null ? "" : name;
        }
    }

    public static final class EditFileResult {
        public final String filePath;
        public final boolean success;
        public final String error;
        public final boolean preview;
        public final String oldContent;
        public final String newContent;
        public final boolean prevExist;

        public EditFileResult(String filePath, boolean success, String error, boolean preview) {
            this(filePath, success, error, preview, null, null, false);
        }

        public EditFileResult(String filePath, boolean success, String error) {
            this(filePath, success, error, false, null, null, false);
        }

        public EditFileResult(String filePath, boolean success, String error, boolean preview, String oldContent, String newContent) {
            this(filePath, success, error, preview, oldContent, newContent, oldContent != null);
        }

        public EditFileResult(String filePath, boolean success, String error, boolean preview, boolean prevExist) {
            this(filePath, success, error, preview, null, null, prevExist);
        }

        public EditFileResult(String filePath, boolean success, String error, boolean preview, String oldContent, String newContent, boolean prevExist) {
            this.filePath = filePath;
            this.success = success;
            this.error = error;
            this.preview = preview;
            this.oldContent = oldContent;
            this.newContent = newContent;
            this.prevExist = prevExist;
        }
    }

    private static final class FileSnapshot {
        public final boolean existed;
        public final boolean directory;
        public final String content;

        private FileSnapshot(boolean existed, boolean directory, String content) {
            this.existed = existed;
            this.directory = directory;
            this.content = content;
        }
    }
}
