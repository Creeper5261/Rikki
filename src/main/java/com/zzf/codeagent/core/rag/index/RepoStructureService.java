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

    public String generateSkeleton(String content) {
        if (content == null || content.isEmpty()) return "";
        try {
            Optional<CompilationUnit> cu = javaParser.parse(content).getResult();
            if (cu.isPresent()) {
                StringBuilder sb = new StringBuilder();
                SkeletonVisitor visitor = new SkeletonVisitor(sb);
                cu.get().accept(visitor, null);
                return sb.toString();
            }
        } catch (Exception e) {
            logger.warn("skeleton.gen.fail err={}", e.toString());
        }
        return "";
    }

    public Set<String> extractDependencies(String content) {
        if (content == null || content.isEmpty()) return Collections.emptySet();
        try {
            Optional<CompilationUnit> cu = javaParser.parse(content).getResult();
            if (cu.isPresent()) {
                // Phase 1: Collect potential imports and used types
                Map<String, String> importMap = new HashMap<>();
                Set<String> usedTypes = new HashSet<>();

                DependencyVisitor visitor = new DependencyVisitor(importMap, usedTypes);
                cu.get().accept(visitor, null);

                // Phase 2: Resolve dependencies (Filter unused imports)
                Set<String> dependencies = new HashSet<>();
                for (String used : usedTypes) {
                    if (importMap.containsKey(used)) {
                        // Resolved via import (Strong Dependency)
                        dependencies.add(importMap.get(used));
                    } else {
                        // Unresolved (Same package, java.lang, or un-imported)
                        // Keep simple name for fuzzy matching later
                        dependencies.add(used);
                    }
                }
                return dependencies;
            }
        } catch (Exception e) {
            logger.warn("dependency.extract.fail err={}", e.toString());
        }
        return Collections.emptySet();
    }

    private static class DependencyVisitor extends com.github.javaparser.ast.visitor.VoidVisitorAdapter<Void> {
        private final Map<String, String> importMap;
        private final Set<String> usedTypes;

        public DependencyVisitor(Map<String, String> importMap, Set<String> usedTypes) {
            this.importMap = importMap;
            this.usedTypes = usedTypes;
        }

        @Override
        public void visit(com.github.javaparser.ast.ImportDeclaration n, Void arg) {
            // Register potential import mapping: "List" -> "java.util.List"
            String fullName = n.getNameAsString();
            String simpleName = fullName;
            if (fullName.contains(".")) {
                simpleName = fullName.substring(fullName.lastIndexOf('.') + 1);
            }
            importMap.put(simpleName, fullName);
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.type.ClassOrInterfaceType n, Void arg) {
            // Capture types used in fields, variables, extends, implements, 'new' expressions
            usedTypes.add(n.getNameAsString());
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.expr.MarkerAnnotationExpr n, Void arg) {
            usedTypes.add(n.getNameAsString());
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.expr.SingleMemberAnnotationExpr n, Void arg) {
            usedTypes.add(n.getNameAsString());
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.expr.NormalAnnotationExpr n, Void arg) {
            usedTypes.add(n.getNameAsString());
            super.visit(n, arg);
        }

        @Override
        public void visit(com.github.javaparser.ast.expr.MethodCallExpr n, Void arg) {
            // Heuristic: Only capture Static Method Calls (Scope starts with Uppercase)
            // e.g. "UserUtils.isLogin()" -> "UserUtils" is a dependency
            // e.g. "userService.findAll()" -> "userService" is a variable, not a type (dependency captured via field type)
            if (n.getScope().isPresent()) {
                String scope = n.getScope().get().toString();
                if (!scope.isEmpty() && Character.isUpperCase(scope.charAt(0))) {
                    usedTypes.add(scope);
                }
            }
            super.visit(n, arg);
        }
    }

    private static class SkeletonVisitor extends com.github.javaparser.ast.visitor.VoidVisitorAdapter<Void> {
        private final StringBuilder sb;
        private int indentLevel = 0;

        public SkeletonVisitor(StringBuilder sb) {
            this.sb = sb;
        }

        private void appendIndent() {
            sb.append("  ".repeat(Math.max(0, indentLevel)));
        }

        @Override
        public void visit(ClassOrInterfaceDeclaration n, Void arg) {
            appendIndent();
            if (n.isPublic()) sb.append("public ");
            if (n.isInterface()) sb.append("interface ");
            else sb.append("class ");
            sb.append(n.getNameAsString());
            if (!n.getImplementedTypes().isEmpty()) {
                sb.append(" implements ").append(n.getImplementedTypes().get(0).getNameAsString());
                if (n.getImplementedTypes().size() > 1) sb.append(", ...");
            }
            if (!n.getExtendedTypes().isEmpty()) {
                sb.append(" extends ").append(n.getExtendedTypes().get(0).getNameAsString());
            }
            sb.append(" {\n");
            indentLevel++;
            super.visit(n, arg);
            indentLevel--;
            appendIndent();
            sb.append("}\n");
        }

        @Override
        public void visit(ConstructorDeclaration n, Void arg) {
            if (!n.isPublic() && !n.isProtected()) return; // Skip private/package-private
            appendIndent();
            if (n.isPublic()) sb.append("public ");
            if (n.isProtected()) sb.append("protected ");
            sb.append(n.getNameAsString()).append("(");
            sb.append(n.getParameters().stream()
                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
                    .reduce((a, b) -> a + ", " + b).orElse(""));
            sb.append(");\n");
        }

        @Override
        public void visit(MethodDeclaration n, Void arg) {
            if (!n.isPublic() && !n.isProtected()) return; // Skip private/package-private
            appendIndent();
            if (n.isPublic()) sb.append("public ");
            if (n.isProtected()) sb.append("protected ");
            if (n.isStatic()) sb.append("static ");
            if (n.isAbstract()) sb.append("abstract ");
            sb.append(n.getType().asString()).append(" ");
            sb.append(n.getNameAsString()).append("(");
            sb.append(n.getParameters().stream()
                    .map(p -> p.getType().asString() + " " + p.getNameAsString())
                    .reduce((a, b) -> a + ", " + b).orElse(""));
            sb.append(")");
            if (n.getBody().isPresent()) {
                sb.append(" { ... }\n");
            } else {
                sb.append(";\n");
            }
        }
        
        // Skip fields for now to keep it compact, or add if needed
    }

    private void parseJavaFile(String content, StringBuilder sb) {
        try {
            Optional<CompilationUnit> cu = javaParser.parse(content).getResult();
            if (cu.isPresent()) {
                for (TypeDeclaration<?> type : cu.get().getTypes()) {
                    // Type Javadoc (First sentence)
                    type.getJavadoc().ifPresent(javadoc -> {
                        String summary = javadoc.getDescription().toText().split("\n")[0].trim();
                        if (!summary.isEmpty()) {
                            sb.append("  /** ").append(StringUtils.truncate(summary, 100)).append(" */\n");
                        }
                    });

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
                                cd.getJavadoc().ifPresent(javadoc -> {
                                    String summary = javadoc.getDescription().toText().split("\n")[0].trim();
                                    if (!summary.isEmpty()) {
                                        sb.append("    // ").append(StringUtils.truncate(summary, 80)).append("\n");
                                    }
                                });
                                sb.append("    ").append(cd.getDeclarationAsString(true, false, false)).append("\n");
                            }
                        } else if (member instanceof MethodDeclaration) {
                            MethodDeclaration md = (MethodDeclaration) member;
                            if (md.isPublic()) {
                                md.getJavadoc().ifPresent(javadoc -> {
                                    String summary = javadoc.getDescription().toText().split("\n")[0].trim();
                                    if (!summary.isEmpty()) {
                                        sb.append("    // ").append(StringUtils.truncate(summary, 80)).append("\n");
                                    }
                                });
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
