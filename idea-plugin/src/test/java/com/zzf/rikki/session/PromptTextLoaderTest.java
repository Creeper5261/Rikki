package com.zzf.rikki.session;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("runtime")
class PromptTextLoaderTest {

    @Test
    void loadsCanonicalWebSearchPromptWithoutAliasFile() {
        String canonical = PromptTextLoader.loadToolPrompt("web_search");
        String description = PromptTextLoader.loadToolDescription("web_search", "D:/workspace");

        assertFalse(PromptTextLoader.has("prompts/tool/web_search.txt"));
        assertFalse(canonical.isBlank());
        assertTrue(description.contains("Search the web"));
        assertFalse(description.contains("{{date}}"));
    }

    @Test
    void loadsCanonicalCodeSearchPromptWithoutAliasFile() {
        String canonical = PromptTextLoader.loadToolPrompt("search_codebase");
        String description = PromptTextLoader.loadToolDescription("search_codebase", "D:/workspace");

        assertFalse(PromptTextLoader.has("prompts/tool/search_codebase.txt"));
        assertFalse(canonical.isBlank());
        assertTrue(description.contains("Search and get relevant context"));
    }

    @Test
    void loadsRuntimePromptFromRuntimeDirectory() {
        assertFalse(PromptTextLoader.has("prompts/opencode/plan.txt"));
        assertTrue(PromptTextLoader.loadRuntimePrompt("plan").contains("Plan Mode"));
    }

    @Test
    void renderTemplate_supportsDoubleBraceAndLegacyDollarPlaceholders() {
        String rendered = PromptTextLoader.renderTemplate(
                "root={{workspaceRoot}} max=${maxLines} date={{date}}",
                java.util.Map.of(
                        "workspaceRoot", "D:/workspace",
                        "maxLines", 200,
                        "date", "2026-03-14"
                )
        );

        assertTrue(rendered.contains("root=D:/workspace"));
        assertTrue(rendered.contains("max=200"));
        assertTrue(rendered.contains("date=2026-03-14"));
    }
}
