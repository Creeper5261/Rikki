package com.zzf.codeagent.core.rag.index;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SymbolGraphServiceTest {

    private SymbolGraphService service;
    
    @TempDir
    Path tempDir;

    @BeforeEach
    public void setup() {
        RepoStructureService repoStructureService = new RepoStructureService();
        service = new SymbolGraphService(repoStructureService);
    }

    @Test
    public void testPageRankRanking() throws IOException {
        Path src = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        
        // Utils (Referenced by 2)
        Files.writeString(src.resolve("Utils.java"), 
            "package com.example; public class Utils { public static void help() {} }");
            
        // Service (Referenced by 1, References 1)
        Files.writeString(src.resolve("Service.java"), 
            "package com.example; import com.example.Utils; public class Service { void doWork() { Utils.help(); } }");
            
        // App (References 2)
        Files.writeString(src.resolve("App.java"), 
            "package com.example; import com.example.Utils; import com.example.Service; public class App { void run() { Utils.help(); new Service().doWork(); } }");

        service.ensureIndexed(tempDir.toString());
        
        // Check App dependencies
        // App depends on Utils and Service.
        // Utils (In-Degree 2) should have higher PageRank than Service (In-Degree 1).
        // So getRelatedFiles should return [Utils, Service] or [Service, Utils] sorted by rank.
        
        java.util.Set<String> related = service.getRelatedFiles(tempDir.toString(), src.resolve("App.java").toString());
        assertEquals(2, related.size());
        
        java.util.List<String> sorted = new java.util.ArrayList<>(related);
        // Note: Set iteration order is not guaranteed, but getRelatedFiles returns LinkedHashSet sorted by rank.
        
        // Identify which is which (relative paths)
        String utilsPath = "src/main/java/com/example/Utils.java".replace("/", File.separator);
        String servicePath = "src/main/java/com/example/Service.java".replace("/", File.separator);
        
        // On Windows/Linux handling
        boolean hasUtils = sorted.stream().anyMatch(s -> s.endsWith("Utils.java"));
        boolean hasService = sorted.stream().anyMatch(s -> s.endsWith("Service.java"));
        assertTrue(hasUtils);
        assertTrue(hasService);
        
        // Verify Order: Utils should be first (Higher Rank)
        // Utils In-Degree=2, Service In-Degree=1.
        // Both are CamelCase (Boost 1.5).
        // Utils Score > Service Score.
        
        // However, paths might differ slightly due to OS. Normalize for check.
        String first = sorted.get(0);
        String second = sorted.get(1);
        
        // Assuming PageRank works: Utils should be higher.
        // Let's print ranks if possible or just assert.
        // System.out.println("Sorted: " + sorted);
        
        assertTrue(first.endsWith("Utils.java"), "Utils should be ranked higher than Service due to more incoming references");
    }


    @Test
    public void testIndexingAndLookup() throws IOException {
        // Create a dummy Java file
        Path src = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(src);
        Path javaFile = src.resolve("UserService.java");
        
        String content = "package com.example;\n" +
                "public class UserService {\n" +
                "    public void login(String user) {}\n" +
                "    public void logout() {}\n" +
                "}\n";
        Files.writeString(javaFile, content);

        // Index
        service.ensureIndexed(tempDir.toString());

        // Search Class
        List<SymbolGraphService.SymbolEntry> hits = service.findSymbol(tempDir.toString(), "UserService");
        assertFalse(hits.isEmpty(), "Should find UserService");
        assertEquals("class", hits.get(0).kind);
        
        // Search Method
        hits = service.findSymbol(tempDir.toString(), "login");
        assertFalse(hits.isEmpty(), "Should find login method");
        assertEquals("method", hits.get(0).kind);

        // Search Fuzzy (case insensitive)
        hits = service.findSymbol(tempDir.toString(), "userservice");
        assertFalse(hits.isEmpty());
        
        // Search Unknown
        hits = service.findSymbol(tempDir.toString(), "UnknownClass");
        assertTrue(hits.isEmpty());
    }
}
