package com.zzf.codeagent.core.rag.index;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.zzf.codeagent.core.util.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

@Service
public class RepoStructureService {
    private static final Logger logger = LoggerFactory.getLogger(RepoStructureService.class);
    private final JavaParser javaParser;
    private static final int MAX_MAP_CHARS = 6000;
    private static final int MAX_FILES_TO_SCAN = 200;
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", ".idea", ".gradle", "build", "target", "node_modules", "dist", "out", ".mvn", "wrapper"
    );

    public RepoStructureService() {
        ParserConfiguration config = new ParserConfiguration();
        config.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17);
        this.javaParser = new JavaParser(config);
    }

    public String generateRepoMap(String rootPath) {
        if (rootPath == null || rootPath.isEmpty()) return "";
        File root = new File(rootPath);
        if (!root.exists() || !root.isDirectory()) return "";

        long t0 = System.nanoTime();
        StringBuilder sb = new StringBuilder();
        sb.append("Repository Structure (Key Classes & Methods):\n");

        List<File> allFiles = new ArrayList<>();
        try {
            collectFiles(root, allFiles, 0);
        } catch (Exception e) {
            logger.error("repo_map.collect.fail root={} err={}", rootPath, e.toString());
        }

        // Sort for determinism
        allFiles.sort(Comparator.comparing(File::getAbsolutePath));

        int processedCount = 0;
        for (File file : allFiles) {
            if (sb.length() > MAX_MAP_CHARS) {
                sb.append("... (truncated due to size limit)\n");
                break;
            }
            if (processedCount >= MAX_FILES_TO_SCAN) {
                sb.append("... (truncated file count)\n");
                break;
            }

            String relPath = getRelativePath(root, file);
            sb.append(relPath).append(":\n");
            
            if (file.getName().endsWith(".java")) {
                try {
                    String content = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                    parseJavaFile(content, sb);
                } catch (Exception e) {
                    // ignore
                }
            }
            processedCount++;
        }

        long tookMs = (System.nanoTime() - t0) / 1_000_000L;
        logger.info("repo_map.generate.done root={} files={} chars={} tookMs={}", rootPath, processedCount, sb.length(), tookMs);
        return sb.toString();
    }

    private void collectFiles(File dir, List<File> result, int depth) {
        if (depth > 10) return; // Max directory depth
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                if (!IGNORED_DIRS.contains(f.getName()) && !f.getName().startsWith(".")) {
                    collectFiles(f, result, depth + 1);
                }
            } else {
                if (f.getName().endsWith(".java")) {
                    result.add(f);
                }
            }
        }
    }

    private String getRelativePath(File root, File file) {
        return root.toPath().relativize(file.toPath()).toString().replace('\\', '/');
    }

    private void parseJavaFile(String content, StringBuilder sb) {
        try {
            Optional<CompilationUnit> cu = javaParser.parse(content).getResult();
            if (cu.isPresent()) {
                for (TypeDeclaration<?> type : cu.get().getTypes()) {
                    sb.append("  ").append(type.getNameAsString());
                    if (type instanceof ClassOrInterfaceDeclaration) {
                        ClassOrInterfaceDeclaration cid = (ClassOrInterfaceDeclaration) type;
                        if (cid.isInterface()) sb.append(" (interface)");
                    }
                    sb.append("\n");

                    for (BodyDeclaration<?> member : type.getMembers()) {
                        if (member instanceof ConstructorDeclaration) {
                            ConstructorDeclaration cd = (ConstructorDeclaration) member;
                            if (cd.isPublic()) {
                                sb.append("    ").append(cd.getDeclarationAsString(true, false, false)).append("\n");
                            }
                        } else if (member instanceof MethodDeclaration) {
                            MethodDeclaration md = (MethodDeclaration) member;
                            if (md.isPublic()) {
                                sb.append("    ").append(md.getDeclarationAsString(true, false, false)).append("\n");
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // ignore parse errors
        }
    }
}
