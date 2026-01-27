package com.zzf.codeagent.core.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileSystemToolServiceTest {

    @Test
    public void testListFilesSkipsIgnoredDirectories(@TempDir Path tempDir) throws Exception {
        Path keep = tempDir.resolve("src/Keep.java");
        Files.createDirectories(keep.getParent());
        Files.writeString(keep, "class Keep {}", StandardCharsets.UTF_8);

        Path ignored = tempDir.resolve("build/Ignore.java");
        Files.createDirectories(ignored.getParent());
        Files.writeString(ignored, "class Ignore {}", StandardCharsets.UTF_8);

        FileSystemToolService service = new FileSystemToolService(tempDir);
        FileSystemToolService.ListFilesResult result = service.listFiles("", "**/*.java", 20, 5);

        assertTrue(result.files.contains("src/Keep.java"));
        assertFalse(result.files.contains("build/Ignore.java"));
        assertEquals(null, result.error);
    }

    @Test
    public void testReadEditAndGrep(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("src/Test.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "first\nbeta\nthird\n", StandardCharsets.UTF_8);

        FileSystemToolService service = new FileSystemToolService(tempDir);
        FileSystemToolService.ReadFileResult read = service.readFile("src/Test.java", 1, 2, 2000);

        assertEquals(null, read.error);
        assertTrue(read.content.contains("1→first"));
        assertTrue(read.content.contains("2→beta"));

        FileSystemToolService.EditFileResult edit = service.editFile("src/Test.java", "beta", "gamma");
        assertTrue(edit.success);

        String updated = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(updated.contains("gamma"));

        FileSystemToolService.GrepResult grep = service.grep("gamma", "", "**/*.java", 10, 10, 1);
        assertEquals(1, grep.matches.size());
        assertEquals("src/Test.java", grep.matches.get(0).filePath);
    }

    @Test
    public void testCreateInsertUndoAndDelete(@TempDir Path tempDir) throws Exception {
        Files.createDirectories(tempDir.resolve("src"));

        FileSystemToolService service = new FileSystemToolService(tempDir);
        FileSystemToolService.EditFileResult created = service.createFile("src/New.txt", "a\nb\n");
        assertTrue(created.success);
        assertTrue(Files.exists(tempDir.resolve("src/New.txt")));

        FileSystemToolService.EditFileResult inserted = service.insertIntoFile("src/New.txt", 2, "x");
        assertTrue(inserted.success);
        String afterInsert = Files.readString(tempDir.resolve("src/New.txt"), StandardCharsets.UTF_8);
        assertTrue(afterInsert.contains("x"));

        FileSystemToolService.EditFileResult undoInsert = service.undoEdit("src/New.txt");
        assertTrue(undoInsert.success);
        String afterUndoInsert = Files.readString(tempDir.resolve("src/New.txt"), StandardCharsets.UTF_8);
        assertEquals("a\nb\n", afterUndoInsert);

        FileSystemToolService.EditFileResult deleted = service.deletePath("src/New.txt", false);
        assertTrue(deleted.success);
        assertFalse(Files.exists(tempDir.resolve("src/New.txt")));

        FileSystemToolService.EditFileResult undoDelete = service.undoEdit("src/New.txt");
        assertTrue(undoDelete.success);
        assertTrue(Files.exists(tempDir.resolve("src/New.txt")));
        String afterUndoDelete = Files.readString(tempDir.resolve("src/New.txt"), StandardCharsets.UTF_8);
        assertEquals("a\nb\n", afterUndoDelete);
    }

    @Test
    public void testApplyPatch(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("src/A.txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "line1\nline2\nline3\n", StandardCharsets.UTF_8);

        FileSystemToolService service = new FileSystemToolService(tempDir);
        String diff = ""
                + "diff --git a/src/A.txt b/src/A.txt\n"
                + "--- a/src/A.txt\n"
                + "+++ b/src/A.txt\n"
                + "@@ -1,3 +1,3 @@\n"
                + "-line1\n"
                + "+line1b\n"
                + " line2\n"
                + " line3\n";

        FileSystemToolService.PatchApplyResult result = service.applyPatch(diff, false);
        assertTrue(result.success);
        assertEquals(1, result.filesApplied);
        assertTrue(result.summary.contains("files=1"));
        String updated = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(updated.contains("line1b"));
        assertFalse(updated.contains("line1\n"));
    }

    @Test
    public void testBatchReplacePreviewAndApply(@TempDir Path tempDir) throws Exception {
        Path fileA = tempDir.resolve("src/A.txt");
        Path fileB = tempDir.resolve("src/B.txt");
        Files.createDirectories(fileA.getParent());
        Files.writeString(fileA, "foo foo\n", StandardCharsets.UTF_8);
        Files.writeString(fileB, "foo\n", StandardCharsets.UTF_8);

        FileSystemToolService service = new FileSystemToolService(tempDir);
        FileSystemToolService.BatchReplaceResult preview = service.batchReplace("src", "*", "foo", "bar", 10, 10, Boolean.TRUE);
        assertTrue(preview.success);
        String originalA = Files.readString(fileA, StandardCharsets.UTF_8);
        String originalB = Files.readString(fileB, StandardCharsets.UTF_8);
        assertEquals("foo foo\n", originalA);
        assertEquals("foo\n", originalB);

        FileSystemToolService.BatchReplaceResult applied = service.batchReplace("src", "*", "foo", "bar", 10, 10, Boolean.FALSE);
        assertTrue(applied.success);
        String updatedA = Files.readString(fileA, StandardCharsets.UTF_8);
        String updatedB = Files.readString(fileB, StandardCharsets.UTF_8);
        assertEquals("bar bar\n", updatedA);
        assertEquals("bar\n", updatedB);
    }
}
