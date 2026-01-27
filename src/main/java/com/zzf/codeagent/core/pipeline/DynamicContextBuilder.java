package com.zzf.codeagent.core.pipeline;

import com.zzf.codeagent.core.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Dynamically builds a focused IDE context (file tree) based on active files.
 * Instead of showing the entire project structure, it highlights the "Active Context"
 * and collapses irrelevant directories, significantly reducing token usage.
 * <p>
 * Strategy:
 * 1. Start with a list of "Active Files" (e.g. opened files, search hits).
 * 2. Traverse the file system from workspace root.
 * 3. For each directory:
 *    - If it contains an Active File (or is a parent of one), expand it.
 *    - If it contains key configuration files (pom.xml, package.json), show them.
 *    - Otherwise, collapse it (e.g. "src/test/java/...").
 */
public class DynamicContextBuilder {
    private static final Logger logger = LoggerFactory.getLogger(DynamicContextBuilder.class);
    private static final int MAX_DEPTH = 8;
    private static final Set<String> KEY_CONFIG_FILES = Set.of(
            "pom.xml", "build.gradle", "package.json", "requirements.txt", 
            "Dockerfile", "docker-compose.yml", "README.md", ".gitignore",
            "Cargo.toml", "go.mod", ".env", "tsconfig.json", "webpack.config.js"
    );

    private static final Set<String> IMPORTANT_HIDDEN_DIRS = Set.of(
            ".github", ".vscode", ".idea", ".git"
    );

    private final String workspaceRoot;

    public DynamicContextBuilder(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String build(List<String> activeFilePaths) {
        if (workspaceRoot == null || workspaceRoot.isEmpty()) return "";
        
        Set<String> activeSet = new HashSet<>();
        if (activeFilePaths != null) {
            for (String p : activeFilePaths) {
                if (p != null && !p.trim().isEmpty()) {
                    // Normalize to relative path if possible, or just filename matching
                    // Here we assume paths are either relative to root or absolute.
                    // Let's normalize to absolute for comparison.
                    activeSet.add(resolve(p));
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Project Structure (Focused View):\n");
        
        try {
            Path root = Paths.get(workspaceRoot);
            renderDirectory(root, 0, sb, activeSet);
        } catch (Exception e) {
            logger.warn("context.dynamic.fail err={}", e.toString());
            return "Failed to build dynamic context: " + e.getMessage();
        }
        
        return sb.toString();
    }

    private void renderDirectory(Path dir, int depth, StringBuilder sb, Set<String> activeSet) {
        if (depth > MAX_DEPTH) return;

        File[] files = dir.toFile().listFiles();
        if (files == null) return;

        // Sort: Directories first, then files
        Arrays.sort(files, (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File f : files) {
            boolean isKeyConfig = KEY_CONFIG_FILES.contains(f.getName());
            boolean isImportantHiddenDir = f.isDirectory() && IMPORTANT_HIDDEN_DIRS.contains(f.getName());
            
            if (f.getName().startsWith(".") && !isKeyConfig && !isImportantHiddenDir) continue; // Skip other hidden files

            boolean isActive = activeSet.contains(f.getAbsolutePath());
            boolean isParentOfActive = isParentOfActive(f, activeSet);
            // boolean isKeyConfig = KEY_CONFIG_FILES.contains(f.getName()); // Removed redefinition
            
            // Fix: Check if sibling of an active file (i.e. parent is parent of active)
            // But 'isParentOfActive' is checked against 'f'.
            // If 'dir' (current directory) contains an active file, we should show all its children?
            // The logic: 
            // - If 'dir' is expanded, we iterate its children 'files'.
            // - For each child 'f', we decide to show/recurse.
            
            // Logic Update:
            // If we are expanding 'dir', it means 'dir' is on the active path or is root.
            // If 'dir' is directly containing an active file, we should show all files in 'dir' (siblings).
            boolean parentIsActiveContainer = isParentOfActive(dir.toFile(), activeSet);

            // Show if:
            // 1. It is an active file
            // 2. It is a directory that contains active files (isParentOfActive)
            // 3. It is a key config file
            // 4. It is a directory at root level (depth 0)
            // 5. It is a sibling of an active file (parent is active container)
            
            boolean shouldShow = isActive || isParentOfActive || isKeyConfig || (depth == 0) || parentIsActiveContainer;

            if (shouldShow) {
                String indent = "  ".repeat(depth);
                String marker = isActive ? " (*)" : "";
                sb.append(indent).append("- ").append(f.getName()).append(marker).append("\n");

                if (f.isDirectory()) {
                    renderDirectory(f.toPath(), depth + 1, sb, activeSet);
                }
            } else if (f.isDirectory() && depth < 2) {
                 // Collapsed directory hint (only at top levels to avoid noise)
                 // String indent = "  ".repeat(depth);
                 // sb.append(indent).append("- ").append(f.getName()).append("/...\n");
            }
        }
    }

    private boolean isParentOfActive(File dir, Set<String> activeSet) {
        if (!dir.isDirectory()) return false;
        String dirPath = dir.getAbsolutePath();
        for (String active : activeSet) {
            if (active.startsWith(dirPath + File.separator)) {
                return true;
            }
        }
        return false;
    }

    private String resolve(String p) {
        try {
            Path path = Paths.get(p);
            if (path.isAbsolute()) {
                return path.normalize().toString();
            }
            return Paths.get(workspaceRoot, p).normalize().toAbsolutePath().toString();
        } catch (Exception e) {
            return p;
        }
    }
}
