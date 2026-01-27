package com.zzf.codeagent.core.pipeline;

import com.zzf.codeagent.core.rag.index.FileNameIndexService;
import com.zzf.codeagent.core.tools.FileSystemToolService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FixVerificationTest {

    @TempDir
    Path tempDir;

    @Test
    public void testDynamicContextBuilder_ExpandedConfig() throws IOException {
        // Create new supported config files
        Files.createFile(tempDir.resolve("Cargo.toml"));
        Files.createFile(tempDir.resolve("go.mod"));
        Files.createFile(tempDir.resolve(".env"));
        Files.createFile(tempDir.resolve(".gitignore"));
        Files.createFile(tempDir.resolve("unknown.config"));

        DynamicContextBuilder builder = new DynamicContextBuilder(tempDir.toString());
        String context = builder.build(List.of());

        System.out.println("Context:\n" + context);

        assertTrue(context.contains("Cargo.toml"), "Should contain Cargo.toml");
        assertTrue(context.contains("go.mod"), "Should contain go.mod");
        assertTrue(context.contains(".env"), "Should contain .env");
        assertTrue(context.contains(".gitignore"), "Should contain .gitignore");
        
        // Test hidden file logic
        Files.createFile(tempDir.resolve(".hidden_random"));
        context = builder.build(List.of());
        assertFalse(context.contains(".hidden_random"), "Should NOT contain random hidden files");
    }

    @Test
    public void testFileSystemTool_ExpandedExtensions() {
        assertTrue(FileSystemToolService.isIndexableExt("py"));
        assertTrue(FileSystemToolService.isIndexableExt("rs"));
        assertTrue(FileSystemToolService.isIndexableExt("go"));
        assertTrue(FileSystemToolService.isIndexableExt("js"));
        assertTrue(FileSystemToolService.isIndexableExt("ts"));
        assertTrue(FileSystemToolService.isIndexableExt("cpp"));
        
        assertTrue(FileSystemToolService.isIndexablePath("src/main.rs"));
        assertTrue(FileSystemToolService.isIndexablePath("script.py"));
        
        assertFalse(FileSystemToolService.isIndexableExt("exe"));
        assertFalse(FileSystemToolService.isIndexableExt("dll"));
    }

    @Test
    public void testFileNameIndex_FuzzySearch() throws IOException {
        FileNameIndexService service = new FileNameIndexService();
        Files.createFile(tempDir.resolve("UserController.java"));
        Files.createFile(tempDir.resolve("UserService.java"));
        Files.createFile(tempDir.resolve("UserModel.py")); // Python file
        
        service.ensureIndexed(tempDir.toString());
        
        List<String> hits = service.search(tempDir.toString(), "User");
        assertEquals(3, hits.size(), "Should find all 3 User files");
        
        List<String> pyHits = service.search(tempDir.toString(), "Model");
        assertEquals(1, pyHits.size(), "Should find Python file");
        assertTrue(pyHits.get(0).endsWith("UserModel.py"));
    }
}
