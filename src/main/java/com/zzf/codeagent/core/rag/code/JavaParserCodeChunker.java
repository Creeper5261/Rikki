package com.zzf.codeagent.core.rag.code;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JavaParserCodeChunker implements CodeChunker {

    @Override
    public List<CodeChunk> chunk(Path filePath, String sourceCode) {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        List<CodeChunk> chunks = new ArrayList<CodeChunk>();
        String relativePath = filePath.toString().replace('\\', '/');
        String pkg = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration n, Void arg) {
                super.visit(n, arg);
                String name = n.getNameAsString();
                String id = buildId(relativePath, "class", name, n.getBegin().map(p -> p.line).orElse(0));
                String signature = "class " + name;
                int start = n.getBegin().map(p -> p.line).orElse(0);
                int end = n.getEnd().map(p -> p.line).orElse(start);
                String content = n.toString();
                chunks.add(new CodeChunk(id, "java", relativePath, "class", qualify(pkg, name), signature, start, end, content));
            }

            @Override
            public void visit(MethodDeclaration n, Void arg) {
                super.visit(n, arg);
                String name = n.getNameAsString();
                String id = buildId(relativePath, "method", name, n.getBegin().map(p -> p.line).orElse(0));
                String signature = buildMethodSignature(n);
                int start = n.getBegin().map(p -> p.line).orElse(0);
                int end = n.getEnd().map(p -> p.line).orElse(start);
                String content = n.toString();
                chunks.add(new CodeChunk(id, "java", relativePath, "method", qualify(pkg, name), signature, start, end, content));
            }

            @Override
            public void visit(ConstructorDeclaration n, Void arg) {
                super.visit(n, arg);
                String name = n.getNameAsString();
                String id = buildId(relativePath, "ctor", name, n.getBegin().map(p -> p.line).orElse(0));
                String signature = buildConstructorSignature(n);
                int start = n.getBegin().map(p -> p.line).orElse(0);
                int end = n.getEnd().map(p -> p.line).orElse(start);
                String content = n.toString();
                chunks.add(new CodeChunk(id, "java", relativePath, "ctor", qualify(pkg, name), signature, start, end, content));
            }
        }, null);

        return chunks;
    }

    private static String buildMethodSignature(MethodDeclaration n) {
        StringBuilder sb = new StringBuilder();
        sb.append(n.getTypeAsString()).append(" ").append(n.getNameAsString()).append("(");
        List<Parameter> params = n.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params.get(i).getTypeAsString());
        }
        sb.append(")");
        return sb.toString();
    }

    private static String buildConstructorSignature(ConstructorDeclaration n) {
        StringBuilder sb = new StringBuilder();
        sb.append(n.getNameAsString()).append("(");
        List<Parameter> params = n.getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params.get(i).getTypeAsString());
        }
        sb.append(")");
        return sb.toString();
    }

    private static String buildId(String file, String kind, String name, int startLine) {
        return file + "|" + kind + "|" + name + "|" + startLine;
    }

    private static String qualify(String pkg, String name) {
        if (pkg == null || pkg.trim().isEmpty()) {
            return name;
        }
        return pkg + "." + name;
    }
}
