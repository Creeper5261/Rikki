package com.zzf.codeagent.core.tool;

import com.zzf.codeagent.core.build.BuildSystemDetection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class BashBuildSelfHealModel {

    private BashBuildSelfHealModel() {
    }

    static final class BuildProbe {
        boolean windows;
        String shell;
        boolean shellBashLikeOnWindows;
        String javaHome;
        String javaVersionOutput;
        Integer javaMajor;
        boolean gradleAvailable;
        String gradleVersionOutput;
        Integer gradleMajor;
        boolean mavenAvailable;
        String mavenVersionOutput;
        boolean gradlewExists;
        boolean gradlewBatExists;
        boolean pomExists;
        boolean mavenwExists;
        boolean mavenwCmdExists;
        boolean wrapperJarExists;
        boolean wrapperPropertiesExists;
        String wrapperDistributionVersion;
        Integer wrapperGradleMajor;
        Integer requiredJavaMajor;
    }

    static final class IdeHints {
        String javaHome;
        Integer javaMajor;
        String languageLevel;
    }

    static final class Report {
        String originalCommand;
        String preparedCommand;
        BuildSystemDetection detection;
        BuildProbe probe;
        IdeHints ideHints;
        List<String> diagnoses = new ArrayList<>();
        String fixStrategy;
        String fixCommand;
        BashCommandExecutor.ExecutionResult fixExecution;
        String fixError;
        boolean wrapperJarExistsAfterFix;
        String retryCommand;
        BashCommandExecutor.ExecutionResult retryExecution;

        boolean hasSuccessfulRetry() {
            return retryExecution != null && !retryExecution.timedOut && retryExecution.exitCode == 0;
        }

        Map<String, Object> toMetadata() {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("diagnoses", new ArrayList<>(diagnoses));
            metadata.put("fix_strategy", firstNonBlankStatic(fixStrategy));
            metadata.put("fix_command", firstNonBlankStatic(fixCommand));
            metadata.put("fix_error", firstNonBlankStatic(fixError));
            metadata.put("retry_command", firstNonBlankStatic(retryCommand));
            if (detection != null) {
                metadata.put("build_system", detection.getPrimary().name().toLowerCase(Locale.ROOT));
                List<String> candidates = detection.getCandidates().stream()
                        .map(type -> type.name().toLowerCase(Locale.ROOT))
                        .toList();
                metadata.put("build_candidates", candidates);
            }
            if (fixExecution != null) {
                metadata.put("fix_exit", fixExecution.exitCode);
            }
            if (retryExecution != null) {
                metadata.put("retry_exit", retryExecution.exitCode);
            }
            metadata.put("wrapper_jar_after_fix", wrapperJarExistsAfterFix);
            if (ideHints != null) {
                Map<String, Object> ideMeta = new HashMap<>();
                ideMeta.put("java_home", firstNonBlankStatic(ideHints.javaHome));
                ideMeta.put("java_major", ideHints.javaMajor);
                ideMeta.put("language_level", firstNonBlankStatic(ideHints.languageLevel));
                metadata.put("ide_hints", ideMeta);
            }
            if (probe != null) {
                Map<String, Object> probeMeta = new HashMap<>();
                probeMeta.put("windows", probe.windows);
                probeMeta.put("shell", firstNonBlankStatic(probe.shell));
                probeMeta.put("java_home", firstNonBlankStatic(probe.javaHome));
                probeMeta.put("java_major", probe.javaMajor);
                probeMeta.put("gradle_available", probe.gradleAvailable);
                probeMeta.put("gradle_major", probe.gradleMajor);
                probeMeta.put("maven_available", probe.mavenAvailable);
                probeMeta.put("required_java_major", probe.requiredJavaMajor);
                probeMeta.put("wrapper_gradle_major", probe.wrapperGradleMajor);
                probeMeta.put("wrapper_distribution_version", firstNonBlankStatic(probe.wrapperDistributionVersion));
                probeMeta.put("wrapper_jar_exists", probe.wrapperJarExists);
                probeMeta.put("pom_exists", probe.pomExists);
                probeMeta.put("mavenw_exists", probe.mavenwExists);
                probeMeta.put("mavenw_cmd_exists", probe.mavenwCmdExists);
                metadata.put("probe", probeMeta);
            }
            return metadata;
        }

        private static String firstNonBlankStatic(String value) {
            return value == null ? "" : value;
        }
    }
}
