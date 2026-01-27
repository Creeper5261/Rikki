package com.zzf.codeagent.core.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ReplaceLinesTest {

    @Test
    public void testReplaceLines(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\n", StandardCharsets.UTF_8);

        FileSystemToolService service = new FileSystemToolService(tempDir);

        // Test normal replacement
        FileSystemToolService.EditFileResult result = service.replaceLines("test.txt", 2, 3, "new2\nnew3", false);
        assertTrue(result.success, "Replace lines should succeed: " + result.error);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("line1\nnew2\nnew3\nline4\n", content);

        // Test deletion (empty content)
        result = service.replaceLines("test.txt", 2, 3, null, false);
        assertTrue(result.success);
        content = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("line1\nline4\n", content);

        // Test out of bounds
        result = service.replaceLines("test.txt", 10, 11, "fail", false);
        assertFalse(result.success);
        assertEquals("start_line_out_of_bounds", result.error);

        // Test preview
        result = service.replaceLines("test.txt", 1, 1, "preview", true);
        assertTrue(result.success);
        assertTrue(result.preview);
        content = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("line1\nline4\n", content); // Content unchanged
    }

    @Test
    public void testReplaceLinesBinary(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("binary.bin");
        byte[] bytes = new byte[] { 'a', 'b', 0, 'c' };
        Files.write(file, bytes);

        FileSystemToolService service = new FileSystemToolService(tempDir);
        FileSystemToolService.EditFileResult result = service.replaceLines("binary.bin", 1, 1, "text", false);
        assertFalse(result.success);
        assertEquals("file_is_binary", result.error);
    }
    
    @Test
    public void testCreateDirectoryAndMove(@TempDir Path tempDir) throws Exception {
        FileSystemToolService service = new FileSystemToolService(tempDir);
        
        // Create directory
        FileSystemToolService.EditFileResult r1 = service.createDirectory("newdir", false);
        assertTrue(r1.success);
        assertTrue(Files.exists(tempDir.resolve("newdir")));
        assertTrue(Files.isDirectory(tempDir.resolve("newdir")));
        
        // Move directory
        FileSystemToolService.EditFileResult r2 = service.movePath("newdir", "moveddir", false);
        assertTrue(r2.success);
        assertFalse(Files.exists(tempDir.resolve("newdir")));
        assertTrue(Files.exists(tempDir.resolve("moveddir")));
        assertTrue(Files.isDirectory(tempDir.resolve("moveddir")));
    }
}
