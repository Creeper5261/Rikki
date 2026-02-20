package com.zzf.rikki.core.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashToolBuildSelfHealTest {

    @Test
    void shouldIdentifyBuildLikeCommand() {
        assertTrue(BashTool.isBuildLikeCommand("./gradlew build"));
        assertTrue(BashTool.isBuildLikeCommand("gradle compileJava"));
        assertTrue(BashTool.isBuildLikeCommand("mvn test"));
        assertFalse(BashTool.isBuildLikeCommand("cat build.gradle"));
        assertFalse(BashTool.isBuildLikeCommand("ls -la"));
    }

    @Test
    void shouldQuoteLeadingWindowsExecutableWhenNeeded() {
        String original = "C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.101-hotspot\\bin\\java.exe --version";
        String quoted = BashTool.quoteLeadingWindowsExecutable(original);
        assertEquals("\"C:\\Program Files\\Eclipse Adoptium\\jdk-21.0.9.101-hotspot\\bin\\java.exe\" --version", quoted);

        String alreadyQuoted = "\"C:\\Program Files\\Java\\bin\\java.exe\" -version";
        assertEquals(alreadyQuoted, BashTool.quoteLeadingWindowsExecutable(alreadyQuoted));
    }

    @Test
    void shouldParseJavaMajorFromVersionOutput() {
        String java17 = "openjdk version \"17.0.16\" 2025-07-15";
        String java8 = "java version \"1.8.0_402\"";
        assertEquals(17, BashTool.parseJavaMajor(java17));
        assertEquals(8, BashTool.parseJavaMajor(java8));
    }

    @Test
    void shouldParseGradleMajorFromOutputs() {
        String gradleOutput = "------------------------------------------------------------\nGradle 7.6.2\n------------------------------------------------------------";
        String wrapperUrl = "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.5-bin.zip";
        assertEquals(7, BashTool.parseGradleMajor(gradleOutput));
        assertEquals(8, BashTool.parseGradleMajor(wrapperUrl));
    }
}
