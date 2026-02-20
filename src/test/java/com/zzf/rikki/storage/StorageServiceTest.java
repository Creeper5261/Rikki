package com.zzf.rikki.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzf.rikki.project.ProjectContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class StorageServiceTest {

    @TempDir
    Path tempDir;

    private ProjectContext projectContext;
    private ObjectMapper objectMapper;
    private StorageService storageService;

    @BeforeEach
    void setUp() {
        projectContext = Mockito.mock(ProjectContext.class);
        when(projectContext.getDirectory()).thenReturn(tempDir.toString());
        objectMapper = new ObjectMapper();
        storageService = new StorageService(projectContext, objectMapper);
        storageService.init();
    }

    @Test
    void testWriteAndRead() {
        List<String> key = List.of("test", "data");
        String value = "hello world";
        
        storageService.write(key, value);
        String result = storageService.read(key, String.class);
        
        assertEquals(value, result);
    }

    @Test
    void testReadNonExistent() {
        List<String> key = List.of("non", "existent");
        String result = storageService.read(key, String.class);
        assertNull(result);
    }

    @Test
    void testList() {
        storageService.write(List.of("folder", "file1"), "content1");
        storageService.write(List.of("folder", "file2"), "content2");
        
        List<List<String>> keys = storageService.list(List.of("folder"));
        assertEquals(2, keys.size());
        assertTrue(keys.contains(List.of("folder", "file1")));
        assertTrue(keys.contains(List.of("folder", "file2")));
    }

    @Test
    void testDelete() {
        List<String> key = List.of("delete", "me");
        storageService.write(key, "content");
        assertNotNull(storageService.read(key, String.class));
        
        storageService.remove(key);
        assertNull(storageService.read(key, String.class));
    }
}
