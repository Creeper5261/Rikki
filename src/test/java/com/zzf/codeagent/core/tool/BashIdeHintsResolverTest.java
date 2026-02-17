package com.zzf.codeagent.core.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BashIdeHintsResolverTest {

    private final BashIdeHintsResolver resolver = new BashIdeHintsResolver();

    @Test
    void shouldResolveIdeHintsFromIdeContext() {
        Tool.Context ctx = Tool.Context.builder()
                .extra(Map.of(
                        "ideContext", Map.of(
                                "projectSdkResolvedHome", "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.10.7-hotspot",
                                "projectSdkMajor", "17",
                                "languageLevel", "JDK_17"
                        )
                ))
                .build();

        BashBuildSelfHealModel.IdeHints hints = resolver.resolveIdeHints(ctx);
        assertEquals(17, hints.javaMajor);
        assertEquals("JDK_17", hints.languageLevel);
    }

    @Test
    void shouldFallbackToRunConfigHomeList() {
        Tool.Context ctx = Tool.Context.builder()
                .extra(Map.of(
                        "ideContext", Map.of(
                                "runConfigResolvedHomes", List.of("", "C:\\Java\\jdk-21"),
                                "moduleSdkMajors", List.of("21")
                        )
                ))
                .build();

        BashBuildSelfHealModel.IdeHints hints = resolver.resolveIdeHints(ctx);
        assertEquals(21, hints.javaMajor);
    }
}
