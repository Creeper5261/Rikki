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
        FileSystemToolService.ReadFileResult content = service.readFile("test.txt", 1, 10, 2000);
        assertTrue(content.content.contains("new2"));
        assertTrue(content.content.contains("new3"));

        // Test deletion (empty content)
        result = service.replaceLines("test.txt", 2, 3, null, false);
        assertTrue(result.success);
        content = service.readFile("test.txt", 1, 10, 2000);
        assertFalse(content.content.contains("line2"));
        assertFalse(content.content.contains("line3"));

        // Test out of bounds
        result = service.replaceLines("test.txt", 10, 11, "fail", false);
        assertFalse(result.success);
        assertEquals("start_line_out_of_bounds", result.error);

        // Test preview
        result = service.replaceLines("test.txt", 1, 1, "preview", true);
        assertTrue(result.success);
        assertTrue(result.preview);
        content = service.readFile("test.txt", 1, 10, 2000);
        assertFalse(content.content.contains("preview")); // Content unchanged
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
        
        // Create file in directory (staged)
        FileSystemToolService.EditFileResult r1 = service.createFile("newdir/file.txt", "hello");
        assertTrue(r1.success);
        FileSystemToolService.ReadFileResult read1 = service.readFile("newdir/file.txt", 1, 10, 2000);
        assertTrue(read1.content.contains("hello"));
        
        // Move file
        FileSystemToolService.EditFileResult r2 = service.movePath("newdir/file.txt", "moveddir/file.txt", false);
        assertTrue(r2.success);
        FileSystemToolService.ReadFileResult moved = service.readFile("moveddir/file.txt", 1, 10, 2000);
        assertTrue(moved.content.contains("hello"));
        FileSystemToolService.ReadFileResult old = service.readFile("newdir/file.txt", 1, 10, 2000);
        assertTrue(old.error != null);
    }
}
