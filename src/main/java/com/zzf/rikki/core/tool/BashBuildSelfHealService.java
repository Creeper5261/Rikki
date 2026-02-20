package com.zzf.rikki.core.tool;

import com.zzf.rikki.core.build.BuildSystemDetection;
import com.zzf.rikki.core.build.BuildSystemDetector;
import com.zzf.rikki.core.build.BuildSystemType;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BashBuildSelfHealService {

    @FunctionalInterface
    interface JavaHomeOverrideBuilder {
        String build(String command, String javaHome, String shell);
    }

    private final BashCommandExecutor commandExecutor;
    private final BashCommandNormalizer commandNormalizer;
    private final long probeTimeoutMs;
    private final long fixTimeoutMs;
    private final String defaultWrapperGradleVersion;
    private final JavaHomeOverrideBuilder javaHomeOverrideBuilder;
    private static final Pattern WRAPPER_DIST_PATTERN =
            Pattern.compile("gradle-([0-9]+(?:\\.[0-9]+){0,2})-(?:bin|all)\\.zip", Pattern.CASE_INSENSITIVE);

    BashBuildSelfHealService(
            BashCommandExecutor commandExecutor,
            BashCommandNormalizer commandNormalizer,
            long probeTimeoutMs,
            long fixTimeoutMs,
            String defaultWrapperGradleVersion,
            JavaHomeOverrideBuilder javaHomeOverrideBuilder
    ) {
        this.commandExecutor = commandExecutor;
        this.commandNormalizer = commandNormalizer;
        this.probeTimeoutMs = probeTimeoutMs;
        this.fixTimeoutMs = fixTimeoutMs;
        this.defaultWrapperGradleVersion = defaultWrapperGradleVersion;
        this.javaHomeOverrideBuilder = javaHomeOverrideBuilder;
    }

    BashBuildSelfHealModel.Report processBuildSelfHeal(
            String originalCommand,
            String preparedCommand,
            String shell,
            Path workdir,
            long timeoutMs,
            BashCommandExecutor.ExecutionResult primary,
            BashBuildSelfHealModel.IdeHints ideHints
    ) {
        if (primary == null || primary.exitCode == 0 || primary.timedOut) {
            return null;
        }

        BuildSystemDetection detection = BuildSystemDetector.detect(workdir, preparedCommand);
        BashBuildSelfHealModel.BuildProbe probe = probeBuildEnvironment(shell, workdir);
        List<String> diagnoses = buildSelfHealDiagnostics(originalCommand, primary.output, probe, ideHints, detection);

        BashBuildSelfHealModel.Report report = new BashBuildSelfHealModel.Report();
        report.originalCommand = originalCommand;
        report.preparedCommand = preparedCommand;
        report.probe = probe;
        report.ideHints = ideHints;
        report.detection = detection;
        report.diagnoses.addAll(diagnoses);

        String quotedCommand = BashTool.quoteLeadingWindowsExecutable(preparedCommand);
        boolean canApplyQuoteFix = commandNormalizer.isWindowsBashShell(shell)
                && !quotedCommand.equals(preparedCommand)
                && looksLikeWindowsPathQuotingFailure(primary.output, preparedCommand);

        if (canApplyQuoteFix) {
            report.fixStrategy = "quote_windows_executable";
            report.fixCommand = quotedCommand;
            try {
                report.retryCommand = quotedCommand;
                report.retryExecution = commandExecutor.execute(
                        quotedCommand,
                        shell,
                        workdir,
                        timeoutMs,
                        "Retry build command after quoting executable",
                        null,
                        false
                );
            } catch (Exception e) {
                report.fixError = e.getMessage();
            }
            return report;
        }

        boolean mavenCommand = shouldTryMavenFix(detection, preparedCommand, probe);
        boolean mavenCommandMissing = looksLikeMavenMissingFailure(primary.output);
        if (mavenCommand && mavenCommandMissing) {
            String wrapperCommand = resolveMavenWrapperRetryCommand(preparedCommand, probe);
            if (wrapperCommand != null && !wrapperCommand.isBlank()) {
                report.fixStrategy = "switch_to_maven_wrapper";
                report.fixCommand = wrapperCommand;
                try {
                    report.retryCommand = wrapperCommand;
                    report.retryExecution = commandExecutor.execute(
                            wrapperCommand,
                            shell,
                            workdir,
                            timeoutMs,
                            "Retry build command via Maven wrapper",
                            null,
                            false
                    );
                } catch (Exception e) {
                    report.fixError = e.getMessage();
                }
                return report;
            }
        }

        boolean gradleWrapperFixSignal = shouldTryGradleWrapperFix(detection, preparedCommand, probe, primary.output);
        if (gradleWrapperFixSignal && probe.gradleAvailable) {
            String wrapperVersion = probe.wrapperDistributionVersion == null || probe.wrapperDistributionVersion.isBlank()
                    ? defaultWrapperGradleVersion
                    : probe.wrapperDistributionVersion;
            String fixCommand = "gradle wrapper --gradle-version " + wrapperVersion;
            report.fixStrategy = "regenerate_gradle_wrapper";
            report.fixCommand = fixCommand;
            try {
                report.fixExecution = commandExecutor.execute(
                        fixCommand,
                        shell,
                        workdir,
                        Math.max(fixTimeoutMs, timeoutMs),
                        "Regenerate Gradle wrapper",
                        null,
                        false
                );
                report.wrapperJarExistsAfterFix = Files.exists(workdir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar"));
                if (report.fixExecution.exitCode == 0 && report.wrapperJarExistsAfterFix) {
                    report.retryCommand = preparedCommand;
                    report.retryExecution = commandExecutor.execute(
                            preparedCommand,
                            shell,
                            workdir,
                            timeoutMs,
                            "Retry build command after wrapper fix",
                            null,
                            false
                    );
                }
            } catch (Exception e) {
                report.fixError = e.getMessage();
            }
            return report;
        }

        boolean canUseIdeJavaHome = ideHints != null
                && ideHints.javaHome != null
                && !ideHints.javaHome.isBlank();
        boolean ideJavaMismatch = canUseIdeJavaHome
                && ideHints.javaMajor != null
                && probe.javaMajor != null
                && !ideHints.javaMajor.equals(probe.javaMajor);
        boolean ideJavaCompatible = isIdeJavaCompatibleWithProject(ideHints, probe);
        if (canUseIdeJavaHome
                && ideJavaCompatible
                && (ideJavaMismatch || looksLikeJavaMismatchFailure(primary.output))) {
            String overrideCommand = javaHomeOverrideBuilder.build(preparedCommand, ideHints.javaHome, shell);
            if (overrideCommand != null && !overrideCommand.isBlank() && !overrideCommand.equals(preparedCommand)) {
                report.fixStrategy = "use_ide_project_sdk";
                report.fixCommand = overrideCommand;
                try {
                    report.retryCommand = overrideCommand;
                    report.retryExecution = commandExecutor.execute(
                            overrideCommand,
                            shell,
                            workdir,
                            timeoutMs,
                            "Retry build command via IDE project SDK",
                            null,
                            false
                    );
                } catch (Exception e) {
                    report.fixError = e.getMessage();
                }
                return report;
            }
        }

        return report;
    }

    String appendSelfHealContext(String output, BashBuildSelfHealModel.Report report) {
        if (report == null) {
            return firstNonBlank(output);
        }
        StringBuilder sb = new StringBuilder();
        String baseOutput = firstNonBlank(output);
        if (!baseOutput.isBlank()) {
            sb.append(baseOutput.trim());
        }

        if (sb.length() > 0) {
            sb.append("\n\n");
        }
        sb.append("[build-self-heal]").append('\n');
        if (!report.diagnoses.isEmpty()) {
            sb.append("Diagnoses:").append('\n');
            for (String diag : report.diagnoses) {
                sb.append("- ").append(diag).append('\n');
            }
        }
        if (report.fixStrategy != null && !report.fixStrategy.isBlank()) {
            sb.append("Fix strategy: ").append(report.fixStrategy).append('\n');
            if (report.fixCommand != null && !report.fixCommand.isBlank()) {
                sb.append("Fix command: ").append(report.fixCommand).append('\n');
            }
            if (report.fixExecution != null) {
                sb.append("Fix exit: ").append(report.fixExecution.exitCode).append('\n');
            }
            if (report.fixError != null && !report.fixError.isBlank()) {
                sb.append("Fix error: ").append(report.fixError).append('\n');
            }
        }
        if (report.retryExecution != null) {
            sb.append("Retry command: ").append(firstNonBlank(report.retryCommand, report.preparedCommand)).append('\n');
            sb.append("Retry exit: ").append(report.retryExecution.exitCode).append('\n');
            if (report.retryExecution.exitCode == 0 && report.retryExecution.output != null && !report.retryExecution.output.isBlank()) {
                sb.append('\n').append(report.retryExecution.output.trim()).append('\n');
            }
        }

        return sb.toString().trim();
    }

    private BashBuildSelfHealModel.BuildProbe probeBuildEnvironment(String shell, Path workdir) {
        String resolvedShell = commandNormalizer.resolveShell(shell);
        BashBuildSelfHealModel.BuildProbe probe = new BashBuildSelfHealModel.BuildProbe();
        probe.windows = isWindows();
        probe.shell = resolvedShell;
        probe.shellBashLikeOnWindows = probe.windows && commandNormalizer.isWindowsBashShell(resolvedShell);
        probe.javaHome = safeEnv("JAVA_HOME");

        try {
            BashCommandExecutor.ExecutionResult javaInfo = commandExecutor.execute(
                    "java -version",
                    resolvedShell,
                    workdir,
                    probeTimeoutMs,
                    "Probe java version",
                    null,
                    false
            );
            probe.javaVersionOutput = firstNonBlank(javaInfo.output);
            probe.javaMajor = BashTool.parseJavaMajor(probe.javaVersionOutput);
        } catch (Exception ignored) {
        }

        try {
            BashCommandExecutor.ExecutionResult gradleInfo = commandExecutor.execute(
                    "gradle -v",
                    resolvedShell,
                    workdir,
                    probeTimeoutMs,
                    "Probe gradle version",
                    null,
                    false
            );
            probe.gradleAvailable = gradleInfo.exitCode == 0;
            probe.gradleVersionOutput = firstNonBlank(gradleInfo.output);
            probe.gradleMajor = BashTool.parseGradleMajor(probe.gradleVersionOutput);
        } catch (Exception ignored) {
        }

        try {
            BashCommandExecutor.ExecutionResult mavenInfo = commandExecutor.execute(
                    "mvn -v",
                    resolvedShell,
                    workdir,
                    probeTimeoutMs,
                    "Probe maven version",
                    null,
                    false
            );
            probe.mavenAvailable = mavenInfo.exitCode == 0;
            probe.mavenVersionOutput = firstNonBlank(mavenInfo.output);
        } catch (Exception ignored) {
        }

        probe.gradlewExists = Files.exists(workdir.resolve("gradlew"));
        probe.gradlewBatExists = Files.exists(workdir.resolve("gradlew.bat"));
        probe.wrapperJarExists = Files.exists(workdir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.jar"));
        probe.wrapperPropertiesExists = Files.exists(workdir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties"));
        probe.pomExists = Files.exists(workdir.resolve("pom.xml"));
        probe.mavenwExists = Files.exists(workdir.resolve("mvnw"));
        probe.mavenwCmdExists = Files.exists(workdir.resolve("mvnw.cmd"));
        probe.wrapperDistributionVersion = extractWrapperDistributionVersion(workdir);
        probe.wrapperGradleMajor = BashTool.parseGradleMajor(firstNonBlank(probe.wrapperDistributionVersion));
        probe.requiredJavaMajor = readRequiredJavaMajor(workdir);
        return probe;
    }

    private List<String> buildSelfHealDiagnostics(
            String command,
            String output,
            BashBuildSelfHealModel.BuildProbe probe,
            BashBuildSelfHealModel.IdeHints ideHints,
            BuildSystemDetection detection
    ) {
        List<String> diagnostics = new ArrayList<>();
        String normalizedOutput = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (detection != null && detection.getPrimary() != BuildSystemType.UNKNOWN) {
            diagnostics.add("Detected build system: " + detection.getPrimary().name().toLowerCase(Locale.ROOT) + ".");
        }

        if ((probe.gradlewExists && !probe.wrapperJarExists) || normalizedOutput.contains("gradlewrappermain")) {
            diagnostics.add("Gradle wrapper appears broken (gradle-wrapper.jar missing or wrapper main class not found).");
        }
        if (looksLikeWindowsPathQuotingFailure(output, command)) {
            diagnostics.add("Windows executable path may be unquoted in bash shell.");
        }
        if (probe.requiredJavaMajor != null && probe.javaMajor != null && !probe.requiredJavaMajor.equals(probe.javaMajor)) {
            diagnostics.add("Project requires Java " + probe.requiredJavaMajor + " but runtime Java is " + probe.javaMajor + ".");
        }
        if (probe.wrapperGradleMajor != null && probe.javaMajor != null && probe.javaMajor >= 21 && probe.wrapperGradleMajor < 8) {
            diagnostics.add("Gradle wrapper version " + probe.wrapperDistributionVersion + " may be incompatible with Java " + probe.javaMajor + ".");
        }
        if ((probe.gradlewExists && !probe.wrapperJarExists) && !probe.gradleAvailable) {
            diagnostics.add("Cannot regenerate wrapper automatically because local gradle command is unavailable.");
        }
        if (probe.pomExists && !probe.mavenAvailable && !probe.mavenwExists && !probe.mavenwCmdExists) {
            diagnostics.add("Maven project detected but mvn/mvnw is unavailable.");
        }
        if (looksLikeMavenMissingFailure(output) && (probe.mavenwExists || probe.mavenwCmdExists)) {
            diagnostics.add("Local Maven command missing; Maven wrapper is available and can be used.");
        }
        if (ideHints != null) {
            if (ideHints.javaMajor != null && probe.javaMajor != null && !ideHints.javaMajor.equals(probe.javaMajor)) {
                diagnostics.add("IDE project SDK Java " + ideHints.javaMajor + " differs from runtime Java " + probe.javaMajor + ".");
            }
            if (!isIdeJavaCompatibleWithProject(ideHints, probe)) {
                diagnostics.add("IDE project SDK Java does not match project-required Java version.");
            }
            if (ideHints.javaHome != null && !ideHints.javaHome.isBlank() && looksLikeJavaMismatchFailure(output)) {
                diagnostics.add("Build output suggests Java mismatch; IDE project SDK home is available for retry.");
            }
        }
        if (diagnostics.isEmpty()) {
            diagnostics.add("Build failed; no deterministic auto-fix rule matched.");
        }
        return diagnostics;
    }

    private String extractWrapperDistributionVersion(Path workdir) {
        try {
            Path propsPath = workdir.resolve("gradle").resolve("wrapper").resolve("gradle-wrapper.properties");
            if (!Files.exists(propsPath)) {
                return "";
            }
            List<String> lines = Files.readAllLines(propsPath, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                Matcher matcher = WRAPPER_DIST_PATTERN.matcher(line);
                if (matcher.find()) {
                    return firstNonBlank(matcher.group(1));
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private Integer readRequiredJavaMajor(Path workdir) {
        List<Path> candidates = List.of(
                workdir.resolve("build.gradle"),
                workdir.resolve("build.gradle.kts")
        );
        for (Path file : candidates) {
            if (!Files.exists(file)) {
                continue;
            }
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                Integer major = parseRequiredJavaMajor(content);
                if (major != null) {
                    return major;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private Integer parseRequiredJavaMajor(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Pattern[] patterns = new Pattern[]{
                Pattern.compile("sourceCompatibility\\s*=\\s*['\"]?(\\d{1,2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("JavaVersion\\.VERSION_(\\d{1,2})", Pattern.CASE_INSENSITIVE),
                Pattern.compile("JavaLanguageVersion\\.of\\((\\d{1,2})\\)", Pattern.CASE_INSENSITIVE)
        };
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                try {
                    return Integer.parseInt(matcher.group(1));
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    private boolean looksLikeWindowsPathQuotingFailure(String output, String command) {
        String normalizedOutput = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (normalizedOutput.contains("c:program: command not found")) {
            return true;
        }
        return normalizedOutput.contains("command not found")
                && !BashTool.quoteLeadingWindowsExecutable(command).equals(command)
                && isWindows();
    }

    private boolean looksLikeMavenCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("mvn ")
                || normalized.equals("mvn")
                || normalized.startsWith(".\\mvnw")
                || normalized.startsWith("./mvnw")
                || normalized.startsWith("mvnw ");
    }

    private boolean looksLikeGradleCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.trim().toLowerCase(Locale.ROOT);
        return normalized.startsWith("gradle ")
                || normalized.equals("gradle")
                || normalized.startsWith(".\\gradlew")
                || normalized.startsWith("./gradlew")
                || normalized.startsWith("gradlew ");
    }

    private boolean shouldTryMavenFix(BuildSystemDetection detection, String preparedCommand, BashBuildSelfHealModel.BuildProbe probe) {
        boolean commandLooksMaven = looksLikeMavenCommand(preparedCommand);
        if (commandLooksMaven) {
            return true;
        }
        if (detection != null && detection.supports(BuildSystemType.MAVEN)) {
            return probe != null && (probe.mavenwExists || probe.mavenwCmdExists || probe.pomExists);
        }
        return false;
    }

    private boolean shouldTryGradleWrapperFix(
            BuildSystemDetection detection,
            String preparedCommand,
            BashBuildSelfHealModel.BuildProbe probe,
            String primaryOutput
    ) {
        if (probe == null) {
            return false;
        }
        boolean wrapperMissing = probe.gradlewExists && !probe.wrapperJarExists;
        boolean wrapperRelatedFailure = primaryOutput != null
                && primaryOutput.toLowerCase(Locale.ROOT).contains("gradlewrappermain");
        if (wrapperMissing || wrapperRelatedFailure) {
            return true;
        }
        if (looksLikeGradleCommand(preparedCommand)) {
            return true;
        }
        return detection != null && detection.supports(BuildSystemType.GRADLE);
    }

    private boolean looksLikeMavenMissingFailure(String output) {
        String normalized = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return normalized.contains("mvn: command not found")
                || normalized.contains("'mvn' is not recognized")
                || normalized.contains("mvn is not recognized");
    }

    private String resolveMavenWrapperRetryCommand(String command, BashBuildSelfHealModel.BuildProbe probe) {
        if (command == null || command.isBlank() || probe == null) {
            return "";
        }
        if (isWindows() && probe.mavenwCmdExists) {
            return replaceLeadingWord(command, "mvn", ".\\mvnw.cmd");
        }
        if (probe.mavenwExists) {
            return replaceLeadingWord(command, "mvn", "./mvnw");
        }
        return "";
    }

    private String replaceLeadingWord(String command, String target, String replacement) {
        if (command == null || command.isBlank() || target == null || target.isBlank()
                || replacement == null || replacement.isBlank()) {
            return command;
        }
        String trimmed = command.trim();
        String replaced = trimmed.replaceFirst("(?i)^" + Pattern.quote(target) + "\\b", Matcher.quoteReplacement(replacement));
        return replaced.equals(trimmed) ? command : replaced;
    }

    private boolean looksLikeJavaMismatchFailure(String output) {
        String normalized = output == null ? "" : output.toLowerCase(Locale.ROOT);
        return normalized.contains("unsupported class file major version")
                || normalized.contains("class file has wrong version")
                || normalized.contains("requires java")
                || normalized.contains("source release")
                || normalized.contains("target release")
                || normalized.contains("invalid source release")
                || normalized.contains("invalid target release")
                || normalized.contains("not supported by this gradle version")
                || normalized.contains("could not target platform");
    }

    private boolean isIdeJavaCompatibleWithProject(
            BashBuildSelfHealModel.IdeHints ideHints,
            BashBuildSelfHealModel.BuildProbe probe
    ) {
        if (ideHints == null || ideHints.javaMajor == null) {
            return true;
        }
        if (probe == null || probe.requiredJavaMajor == null) {
            return true;
        }
        return ideHints.javaMajor.equals(probe.requiredJavaMajor);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String safeEnv(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String value = System.getenv(key);
        return value == null ? "" : value;
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
