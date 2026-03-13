package com.zzf.rikki.idea.settings;

import com.zzf.rikki.idea.completion.CompletionConfigResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RikkiSettingsStateTest {

    @AfterEach
    void tearDown() {
        RikkiCredentials.clearForTest();
    }

    @Test
    void state_shouldExposeExpectedDefaults() {
        RikkiSettings.State state = new RikkiSettings.State();

        assertEquals("DEEPSEEK", state.getProvider());
        assertEquals("deepseek-chat", state.getModelName());
        assertTrue(state.isCompletionEnabled());
        assertEquals("", state.getCompletionProvider());
        assertEquals("", state.getCompletionModelName());
        assertEquals("https://api.deepseek.com/v1", state.currentBaseUrl());
    }

    @Test
    void completionResolver_shouldUseChatProviderWhenCompletionOverridesAreBlank() {
        RikkiCredentials.injectForTest("OPENAI", "openai-key");
        RikkiSettings.State state = new RikkiSettings.State();
        state.setProvider("OPENAI");
        state.setModelName("gpt-4o");

        CompletionConfigResolver.CompletionConfig config = CompletionConfigResolver.resolve(state);

        assertEquals("OPENAI", config.provider());
        assertEquals("gpt-4o", config.model());
        assertEquals("https://api.openai.com/v1", config.baseUrl());
        assertEquals("openai-key", config.apiKey());
        assertFalse(config.useFim());
    }

    @Test
    void completionResolver_shouldUseDedicatedCompletionProviderAndOverrideKey() {
        RikkiCredentials.injectForTest("OPENAI", "openai-key");
        RikkiCredentials.injectForTest("COMPLETION_OVERRIDE", "completion-key");
        RikkiSettings.State state = new RikkiSettings.State();
        state.setProvider("OPENAI");
        state.setModelName("gpt-4o");
        state.setCompletionProvider("DEEPSEEK");
        state.setCompletionModelName("deepseek-chat");

        CompletionConfigResolver.CompletionConfig config = CompletionConfigResolver.resolve(state);

        assertEquals("DEEPSEEK", config.provider());
        assertEquals("deepseek-chat", config.model());
        assertEquals("https://api.deepseek.com/beta", config.baseUrl());
        assertEquals("completion-key", config.apiKey());
        assertTrue(config.useFim());
    }

    @Test
    void completionEffectiveBaseUrl_shouldReuseChatCustomUrlForMatchingProvider() {
        RikkiSettings.State state = new RikkiSettings.State();
        state.setProvider("OLLAMA");
        state.setCustomBaseUrl("http://localhost:11434/v1/");
        state.setCompletionProvider("OLLAMA");

        assertEquals("http://localhost:11434/v1", state.completionEffectiveBaseUrl());
        assertTrue(state.completionUsesFim());
    }
}
