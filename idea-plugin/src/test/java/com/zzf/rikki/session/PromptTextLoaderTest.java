package com.zzf.rikki.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTextLoaderTest {

    @Test
    void loadsBackendCompatibleWebSearchPromptAlias() {
        String alias = PromptTextLoader.load("prompts/tool/web_search.txt");
        String description = PromptTextLoader.loadToolDescription("web_search", "D:/workspace");

        assertFalse(alias.isBlank());
        assertTrue(description.contains("Search the web"));
        assertFalse(description.contains("{{date}}"));
    }

    @Test
    void loadsBackendCompatibleCodeSearchPromptAlias() {
        String alias = PromptTextLoader.load("prompts/tool/search_codebase.txt");
        String description = PromptTextLoader.loadToolDescription("search_codebase", "D:/workspace");

        assertFalse(alias.isBlank());
        assertTrue(description.contains("Search and get relevant context"));
    }
}