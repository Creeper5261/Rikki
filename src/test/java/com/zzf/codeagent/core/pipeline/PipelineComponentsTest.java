package com.zzf.codeagent.core.pipeline;

import com.zzf.codeagent.core.rag.search.CodeSearchHit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PipelineComponentsTest {

    @TempDir
    Path tempDir;

    @Test
    public void testContextReranker() {
        ContextReranker reranker = new ContextReranker();
        
        CodeSearchHit h1 = new CodeSearchHit("A.java", "class", "User", 1, 10, "class User {}", false);
        CodeSearchHit h2 = new CodeSearchHit("B.java", "method", "login", 5, 15, "void login() {}", false);
        CodeSearchHit h3 = new CodeSearchHit("C.java", "text", "", 1, 5, "some random text", false);
        
        List<CodeSearchHit> hits = new ArrayList<>(Arrays.asList(h3, h1, h2));
        
        // Query: "login User"
        // Expected: h2 (matches "login"), h1 (matches "User") should be top
        List<CodeSearchHit> sorted = reranker.rerank("login User", hits);
        
        assertEquals(3, sorted.size());
        // h2 has keyword match "login" in snippet -> +10
        // h1 has keyword match "User" in snippet -> +10
        // h3 has nothing -> 0
        
        assertTrue(sorted.get(0).getScore() > 0);
        assertTrue(sorted.get(2).getScore() == 0 || sorted.get(2).getScore() < sorted.get(0).getScore());
    }

    @Test
    public void testDynamicContextBuilder() throws IOException {
        // Setup mock project structure
        // root/
        //   pom.xml
        //   src/
        //     main/
        //       java/
        //         com/
        //           App.java  <-- Active
        //           Utils.java
        //     test/
        //       java/
        //         AppTest.java
        
        Files.createFile(tempDir.resolve("pom.xml"));
        Path pkg = tempDir.resolve("src/main/java/com");
        Files.createDirectories(pkg);
        Files.createFile(pkg.resolve("App.java"));
        Files.createFile(pkg.resolve("Utils.java"));
        
        Path testPkg = tempDir.resolve("src/test/java");
        Files.createDirectories(testPkg);
        Files.createFile(testPkg.resolve("AppTest.java"));

        DynamicContextBuilder builder = new DynamicContextBuilder(tempDir.toString());
        
        // Scenario: User is looking at App.java
        // DynamicContextBuilder resolves path to absolute, so we should provide absolute path if possible
        // or path relative to workspaceRoot.
        // Let's pass absolute path of App.java
        List<String> activeFiles = List.of(pkg.resolve("App.java").toAbsolutePath().toString());
        String context = builder.build(activeFiles);
        
        System.out.println("Generated Context:\n" + context);

        assertTrue(context.contains("pom.xml"), "Should show key config files");
        assertTrue(context.contains("App.java (*)"), "Should mark active file");
        assertTrue(context.contains("src"), "Should show src dir");
        // It might NOT show Utils.java or AppTest.java depending on logic, 
        // but current logic shows all children of expanded nodes.
        // App.java is active -> parent "com" is expanded -> Utils.java is sibling -> shown.
        assertTrue(context.contains("Utils.java"), "Should show sibling files");
        
        // Directories not on active path should be present but their children might not be if depth limit was hit
        // In this small example, depth is small so everything likely shown.
    }
}
