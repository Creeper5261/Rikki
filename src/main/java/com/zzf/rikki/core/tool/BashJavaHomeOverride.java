package com.zzf.rikki.core.tool;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BashJavaHomeOverride {
    private final BashCommandNormalizer commandNormalizer;
    private final BashRiskAssessor riskAssessor;
    private final boolean requireIdeJavaForBuildWhenIdeContext;

    BashJavaHomeOverride(
            BashCommandNormalizer commandNormalizer,
            BashRiskAssessor riskAssessor,
            boolean requireIdeJavaForBuildWhenIdeContext
    ) {
        this.commandNormalizer = commandNormalizer;
        this.riskAssessor = riskAssessor;
        this.requireIdeJavaForBuildWhenIdeContext = requireIdeJavaForBuildWhenIdeContext;
    }

    String maybeApplyIdeJavaHomeOverride(
            String originalCommand,
            String preparedCommand,
            String shell,
            BashBuildSelfHealModel.IdeHints ideHints
    ) {
        if (preparedCommand == null || preparedCommand.isBlank()) {
            return preparedCommand;
        }
        if (ideHints == null || ideHints.javaHome == null || ideHints.javaHome.isBlank()) {
            return preparedCommand;
        }
        boolean buildLike = BashTool.isBuildLikeCommand(originalCommand);
        if (!buildLike && !isJavaToolchainCommand(originalCommand, preparedCommand)) {
            return preparedCommand;
        }
        if (!buildLike && commandAlreadySetsJavaHome(preparedCommand)) {
            return preparedCommand;
        }
        String override = buildJavaHomeOverrideCommand(preparedCommand, ideHints.javaHome, shell);
        return (override == null || override.isBlank()) ? preparedCommand : override;
    }

    boolean shouldRequireIdeJavaForBuild(String command, Tool.Context ctx) {
        if (!requireIdeJavaForBuildWhenIdeContext) {
            return false;
        }
        if (!BashTool.isBuildLikeCommand(command)) {
            return false;
        }
        if (ctx == null || ctx.getExtra() == null) {
            return false;
        }
        Object ideContext = ctx.getExtra().get("ideContext");
        return ideContext instanceof Map<?, ?> && !((Map<?, ?>) ideContext).isEmpty();
    }

    String buildJavaHomeOverrideCommand(String command, String javaHome, String shell) {
        if (command == null || command.isBlank() || javaHome == null || javaHome.isBlank()) {
            return command;
        }
        String normalizedCommand = command.trim();
        String normalizedHome = normalizeJavaHomeForShell(javaHome.trim(), shell);
        String lowerShell = shell == null ? "" : shell.toLowerCase(Locale.ROOT);

        if (isWindows() && lowerShell.endsWith("cmd.exe")) {
            return "set \"JAVA_HOME=" + escapeCmdValue(normalizedHome) + "\" && "
                    + "set \"PATH=%JAVA_HOME%\\bin;%PATH%\" && "
                    + normalizedCommand;
        }
        if (isPowerShell(lowerShell)) {
            return "$env:JAVA_HOME='" + escapePowerShellSingleQuoted(normalizedHome) + "'; "
                    + "$env:PATH=\"$env:JAVA_HOME\\bin;$env:PATH\"; "
                    + normalizedCommand;
        }
        return "export JAVA_HOME='" + escapeSingleQuoted(normalizedHome) + "'; "
                + "export PATH=\"$JAVA_HOME/bin:$PATH\"; "
                + normalizedCommand;
    }

    private boolean isJavaToolchainCommand(String originalCommand, String preparedCommand) {
        String normalized = firstNonBlank(originalCommand, preparedCommand).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }
        String family = riskAssessor.extractCommandFamily(normalized);
        if ("java".equals(family)
                || "javac".equals(family)
                || "jar".equals(family)
                || "jshell".equals(family)
                || "jcmd".equals(family)
                || "jdeps".equals(family)
                || "jlink".equals(family)
                || "jpackage".equals(family)
                || "gradle".equals(family)
                || "gradlew".equals(family)
                || "mvn".equals(family)
                || "mvnw".equals(family)) {
            return true;
        }
        return normalized.contains(" gradle ")
                || normalized.startsWith("gradle ")
                || normalized.contains(" gradlew")
                || normalized.startsWith("./gradlew")
                || normalized.startsWith(".\\gradlew")
                || normalized.contains(" maven ")
                || normalized.startsWith("mvn ")
                || normalized.startsWith("./mvnw")
                || normalized.startsWith(".\\mvnw")
                || normalized.contains(" java ")
                || normalized.startsWith("java ")
                || normalized.contains(" javac ")
                || normalized.startsWith("javac ");
    }

    private boolean commandAlreadySetsJavaHome(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return normalized.contains("java_home=")
                || normalized.contains("$env:java_home")
                || normalized.contains("%java_home%")
                || normalized.contains("set \"java_home=");
    }

    private String normalizeJavaHomeForShell(String javaHome, String shell) {
        if (javaHome == null || javaHome.isBlank()) {
            return "";
        }
        if (!commandNormalizer.isWindowsBashShell(shell)) {
            return javaHome;
        }
        String normalized = javaHome.trim().replace('\\', '/');
        Matcher driveMatcher = Pattern.compile("^([a-zA-Z]):/(.*)$").matcher(normalized);
        if (driveMatcher.find()) {
            String drive = driveMatcher.group(1).toLowerCase(Locale.ROOT);
            String rest = driveMatcher.group(2);
            return "/" + drive + "/" + rest;
        }
        return normalized;
    }

    private boolean isPowerShell(String lowerShell) {
        if (lowerShell == null || lowerShell.isBlank()) {
            return false;
        }
        return lowerShell.endsWith("powershell.exe")
                || lowerShell.endsWith("pwsh.exe")
                || lowerShell.contains("\\powershell")
                || lowerShell.endsWith("powershell")
                || lowerShell.endsWith("pwsh");
    }

    private String escapeCmdValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\"", "\\\"");
    }

    private String escapePowerShellSingleQuoted(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "''");
    }

    private String escapeSingleQuoted(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("'", "'\"'\"'");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
