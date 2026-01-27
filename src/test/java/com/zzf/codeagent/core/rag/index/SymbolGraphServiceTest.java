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
        service = new SymbolGraphService();
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
