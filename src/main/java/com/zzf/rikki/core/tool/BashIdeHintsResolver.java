package com.zzf.rikki.core.tool;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BashIdeHintsResolver {

    BashBuildSelfHealModel.IdeHints resolveIdeHints(Tool.Context ctx) {
        BashBuildSelfHealModel.IdeHints hints = new BashBuildSelfHealModel.IdeHints();
        if (ctx == null || ctx.getExtra() == null || ctx.getExtra().isEmpty()) {
            return hints;
        }
        Object ideContextObj = ctx.getExtra().get("ideContext");
        if (ideContextObj instanceof Map<?, ?> ideMap) {
            hints.javaHome = normalizePotentialJavaHome(readString(
                    ideMap,
                    "projectSdkResolvedHome",
                    "projectSdkHome",
                    "gradleJvmResolvedHome",
                    "mavenRunnerResolvedHome",
                    "runConfigResolvedHome",
                    "sdkHomePath",
                    "javaHome"
            ));
            if ((hints.javaHome == null || hints.javaHome.isBlank())) {
                hints.javaHome = normalizePotentialJavaHome(readFirstStringFromList(ideMap.get("runConfigResolvedHomes")));
            }
            hints.javaMajor = readInteger(ideMap, "projectSdkMajor", "sdkMajor", "javaMajor");
            hints.languageLevel = readString(ideMap, "languageLevel");
            if (hints.javaMajor == null) {
                Integer fromVersion = BashTool.parseJavaMajor(readString(ideMap, "projectSdkVersion", "sdkVersion"));
                hints.javaMajor = fromVersion;
            }
            if (hints.javaMajor == null) {
                Integer moduleSdkMajor = readFirstIntegerFromList(ideMap.get("moduleSdkMajors"));
                if (moduleSdkMajor != null) {
                    hints.javaMajor = moduleSdkMajor;
                }
            }
        }
        if ((hints.javaHome == null || hints.javaHome.isBlank())) {
            Object javaHome = ctx.getExtra().get("javaHome");
            if (javaHome instanceof String && !((String) javaHome).isBlank()) {
                hints.javaHome = normalizePotentialJavaHome(((String) javaHome).trim());
            }
        }
        return hints;
    }

    private String normalizePotentialJavaHome(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.trim();
        if ("project sdk".equalsIgnoreCase(value)
                || "project jdk".equalsIgnoreCase(value)
                || "use project jdk".equalsIgnoreCase(value)
                || "#JAVA_INTERNAL".equalsIgnoreCase(value)) {
            return "";
        }
        if (value.contains(",")) {
            String first = value.split(",")[0].trim();
            if (!first.isBlank()) {
                value = first;
            }
        }
        try {
            Path path = Paths.get(value);
            if (path.isAbsolute()) {
                return path.normalize().toString();
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private String readFirstStringFromList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            return "";
        }
        for (Object item : list) {
            if (item instanceof String text && !text.isBlank()) {
                return text.trim();
            }
        }
        return "";
    }

    private String readString(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return "";
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = map.get(key);
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (!text.isBlank()) {
                    return text;
                }
            }
        }
        return "";
    }

    private Integer readInteger(Map<?, ?> map, String... keys) {
        if (map == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Object value = map.get(key);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (text.isBlank()) {
                    continue;
                }
                try {
                    return Integer.parseInt(text);
                } catch (Exception ignored) {
                    Matcher matcher = Pattern.compile("(\\d{1,2})").matcher(text);
                    if (matcher.find()) {
                        try {
                            return Integer.parseInt(matcher.group(1));
                        } catch (Exception ignored2) {
                        }
                    }
                    Integer fromVersion = BashTool.parseJavaMajor(text);
                    if (fromVersion != null) {
                        return fromVersion;
                    }
                }
            }
        }
        return null;
    }

    private Integer readFirstIntegerFromList(Object listObject) {
        if (!(listObject instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        for (Object item : list) {
            if (item instanceof Number) {
                return ((Number) item).intValue();
            }
            if (item instanceof String) {
                String text = ((String) item).trim();
                if (text.isBlank()) {
                    continue;
                }
                try {
                    return Integer.parseInt(text);
                } catch (Exception ignored) {
                    Integer parsed = BashTool.parseJavaMajor(text);
                    if (parsed != null) {
                        return parsed;
                    }
                }
            }
        }
        return null;
    }
}
