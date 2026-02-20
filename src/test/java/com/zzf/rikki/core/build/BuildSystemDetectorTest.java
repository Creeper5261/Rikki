package com.zzf.rikki.core.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildSystemDetectorTest {

    @Test
    void shouldPreferCommandSignalOverWorkspace(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        BuildSystemDetection detection = BuildSystemDetector.detect(tempDir, "./gradlew build");
        assertEquals(BuildSystemType.GRADLE, detection.getPrimary());
        assertTrue(detection.supports(BuildSystemType.MAVEN));
    }

    @Test
    void shouldDetectMavenFromWorkspace(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        BuildSystemDetection detection = BuildSystemDetector.detect(tempDir, "");
        assertEquals(BuildSystemType.MAVEN, detection.getPrimary());
    }

    @Test
    void shouldReturnUnknownWhenNoMarkers(@TempDir Path tempDir) {
        BuildSystemDetection detection = BuildSystemDetector.detect(tempDir, "echo hello");
        assertEquals(BuildSystemType.UNKNOWN, detection.getPrimary());
    }
}
