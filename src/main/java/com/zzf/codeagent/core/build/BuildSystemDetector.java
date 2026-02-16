package com.zzf.codeagent.core.build;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class BuildSystemDetector {

    private static final Pattern GRADLE_CMD = Pattern.compile("(?i)(^|\\s|/|\\\\)(gradle|gradlew)(\\.bat)?(\\s|$)");
    private static final Pattern MAVEN_CMD = Pattern.compile("(?i)(^|\\s|/|\\\\)(mvn|mvnw)(\\.cmd)?(\\s|$)");

    private BuildSystemDetector() {
    }

    public static BuildSystemDetection detect(Path workspaceRoot, String command) {
        BuildSystemType fromCommand = detectFromCommand(command);
        Set<BuildSystemType> fromWorkspace = detectFromWorkspace(workspaceRoot);

        EnumSet<BuildSystemType> candidates = EnumSet.noneOf(BuildSystemType.class);
        if (fromCommand != BuildSystemType.UNKNOWN) {
            candidates.add(fromCommand);
        }
        candidates.addAll(fromWorkspace);
        if (candidates.isEmpty()) {
            candidates.add(BuildSystemType.UNKNOWN);
        }

        BuildSystemType primary = resolvePrimary(fromCommand, fromWorkspace);
        return new BuildSystemDetection(primary, candidates, fromCommand, fromWorkspace);
    }

    private static BuildSystemType detectFromCommand(String command) {
        if (command == null || command.isBlank()) {
            return BuildSystemType.UNKNOWN;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        boolean gradle = GRADLE_CMD.matcher(normalized).find();
        boolean maven = MAVEN_CMD.matcher(normalized).find();
        if (gradle && !maven) {
            return BuildSystemType.GRADLE;
        }
        if (maven && !gradle) {
            return BuildSystemType.MAVEN;
        }
        return BuildSystemType.UNKNOWN;
    }

    private static Set<BuildSystemType> detectFromWorkspace(Path workspaceRoot) {
        if (workspaceRoot == null) {
            return EnumSet.noneOf(BuildSystemType.class);
        }
        EnumSet<BuildSystemType> result = EnumSet.noneOf(BuildSystemType.class);
        if (hasGradleMarkers(workspaceRoot)) {
            result.add(BuildSystemType.GRADLE);
        }
        if (hasMavenMarkers(workspaceRoot)) {
            result.add(BuildSystemType.MAVEN);
        }
        return result;
    }

    private static boolean hasGradleMarkers(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("build.gradle"))
                || Files.exists(workspaceRoot.resolve("build.gradle.kts"))
                || Files.exists(workspaceRoot.resolve("settings.gradle"))
                || Files.exists(workspaceRoot.resolve("settings.gradle.kts"))
                || Files.exists(workspaceRoot.resolve("gradlew"))
                || Files.exists(workspaceRoot.resolve("gradlew.bat"));
    }

    private static boolean hasMavenMarkers(Path workspaceRoot) {
        return Files.exists(workspaceRoot.resolve("pom.xml"))
                || Files.exists(workspaceRoot.resolve("mvnw"))
                || Files.exists(workspaceRoot.resolve("mvnw.cmd"));
    }

    private static BuildSystemType resolvePrimary(BuildSystemType fromCommand, Set<BuildSystemType> fromWorkspace) {
        if (fromCommand != BuildSystemType.UNKNOWN) {
            return fromCommand;
        }
        if (fromWorkspace.contains(BuildSystemType.GRADLE) && !fromWorkspace.contains(BuildSystemType.MAVEN)) {
            return BuildSystemType.GRADLE;
        }
        if (fromWorkspace.contains(BuildSystemType.MAVEN) && !fromWorkspace.contains(BuildSystemType.GRADLE)) {
            return BuildSystemType.MAVEN;
        }
        return BuildSystemType.UNKNOWN;
    }
}
