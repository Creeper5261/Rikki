package com.zzf.codeagent.core.rag.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FileNameIndexServiceTest {

    @TempDir
    Path tempDir;

    @Test
    public void testSearchHonorsIgnoredDirsAndPathMatching() throws Exception {
        Path src = tempDir.resolve("src/main/java");
        Files.createDirectories(src);
        Files.writeString(src.resolve("UserService.java"), "class UserService {}");
        Path configDir = tempDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("app.yml"), "name: demo");
        Path ignored = tempDir.resolve("build");
        Files.createDirectories(ignored);
        Files.writeString(ignored.resolve("secret.txt"), "nope");

        FileNameIndexService service = new FileNameIndexService();

        List<String> strictResults = service.search(tempDir.toString(), "co");
        assertFalse(strictResults.stream().anyMatch(p -> p.contains("config/app.yml")));

        List<String> fuzzyResults = service.search(tempDir.toString(), "config");
        assertTrue(fuzzyResults.stream().anyMatch(p -> p.contains("config/app.yml")));
        assertFalse(fuzzyResults.stream().anyMatch(p -> p.contains("build/secret.txt")));
    }

    @Test
    public void testSearchSortingPrefersExactFileName() throws Exception {
        Path docs = tempDir.resolve("docs");
        Files.createDirectories(docs);
        Files.writeString(docs.resolve("readme"), "doc");
        Files.writeString(docs.resolve("readme-extra"), "doc");
        Files.writeString(docs.resolve("guide-readme.txt"), "doc");

        FileNameIndexService service = new FileNameIndexService();

        List<String> results = service.search(tempDir.toString(), "readme");
        assertFalse(results.isEmpty());
        assertEquals("docs/readme", results.get(0));
    }
}
