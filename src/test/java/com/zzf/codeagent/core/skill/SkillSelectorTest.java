package com.zzf.codeagent.core.skill;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class SkillSelectorTest {

    @Test
    public void selectsLogAnalyzerByKeywords() {
        List<Skill> skills = List.of(
                new Skill("log-analyzer", "log", "", ""),
                new Skill("gradle-expert", "gradle", "", "")
        );
        List<String> selected = SkillSelector.selectSkillNames("Please analyze this stack trace error log", skills, 2);
        assertTrue(selected.contains("log-analyzer"));
    }

    @Test
    public void selectsGradleExpertByKeywords() {
        List<Skill> skills = List.of(
                new Skill("gradle-expert", "gradle", "", ""),
                new Skill("java-test-gen", "tests", "", "")
        );
        List<String> selected = SkillSelector.selectSkillNames("Gradle dependency conflict in build.gradle.kts", skills, 2);
        assertTrue(selected.contains("gradle-expert"));
    }

    @Test
    public void selectsJavaTestGenByKeywords() {
        List<Skill> skills = List.of(
                new Skill("java-test-gen", "tests", "", ""),
                new Skill("spring-boot-helper", "spring", "", "")
        );
        List<String> selected = SkillSelector.selectSkillNames("帮我写JUnit单元测试，用Mockito", skills, 2);
        assertTrue(selected.contains("java-test-gen"));
    }
}
