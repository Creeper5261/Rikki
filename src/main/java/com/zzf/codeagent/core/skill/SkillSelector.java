package com.zzf.codeagent.core.skill;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SkillSelector {
    private SkillSelector() {}

    private static final Map<String, List<String>> TRIGGERS = new LinkedHashMap<>();

    static {
        TRIGGERS.put("log-analyzer", List.of(
                "stack trace", "exception", "error log", "error", "日志", "异常", "堆栈", "报错"
        ));
        TRIGGERS.put("gradle-expert", List.of(
                "gradle", "build.gradle", "build.gradle.kts", "dependency", "dependencies", "依赖", "构建"
        ));
        TRIGGERS.put("java-test-gen", List.of(
                "junit", "mockito", "unit test", "test case", "单元测试", "测试用例", "写测试", "测试"
        ));
        TRIGGERS.put("spring-boot-helper", List.of(
                "spring boot", "spring", "controller", "service", "repository", "rest", "接口", "控制器", "服务", "仓库"
        ));
        TRIGGERS.put("git-workflow", List.of(
                "git", "commit", "branch", "merge", "rebase", "push", "pull", "提交", "分支", "合并"
        ));
    }

    public static List<String> selectSkillNames(String text, List<Skill> availableSkills, int limit) {
        if (text == null || text.trim().isEmpty() || availableSkills == null || availableSkills.isEmpty()) {
            return List.of();
        }
        String lower = text.toLowerCase();

        Set<String> available = new HashSet<>();
        for (Skill s : availableSkills) {
            if (s != null && s.getName() != null) {
                available.add(s.getName().toLowerCase());
            }
        }
        if (available.isEmpty()) return List.of();

        List<String> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // 1) Explicit mentions (skill name or $skillName)
        for (String name : available) {
            String raw = name;
            if (lower.contains(raw) || lower.contains("$" + raw)) {
                if (seen.add(raw)) {
                    selected.add(raw);
                }
            }
        }

        // 2) Keyword triggers (ordered)
        for (Map.Entry<String, List<String>> entry : TRIGGERS.entrySet()) {
            String skillName = entry.getKey();
            if (!available.contains(skillName)) continue;
            if (seen.contains(skillName)) continue;
            for (String k : entry.getValue()) {
                if (lower.contains(k.toLowerCase())) {
                    selected.add(skillName);
                    seen.add(skillName);
                    break;
                }
            }
            if (limit > 0 && selected.size() >= limit) {
                break;
            }
        }

        if (limit > 0 && selected.size() > limit) {
            return selected.subList(0, limit);
        }
        return selected;
    }
}
