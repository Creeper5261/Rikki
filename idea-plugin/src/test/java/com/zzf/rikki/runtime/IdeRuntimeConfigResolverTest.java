package com.zzf.rikki.runtime;

import com.zzf.rikki.idea.settings.RikkiCredentials;
import com.zzf.rikki.idea.settings.RikkiSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdeRuntimeConfigResolverTest {

    @AfterEach
    void tearDown() {
        RikkiCredentials.clearForTest();
    }

    @Test
    void resolve_shouldUseIdeDefaultsAndRequestOverrides() {
        RikkiCredentials.injectForTest("DEEPSEEK", "deepseek-key");
        RikkiSettings.State state = new RikkiSettings.State();
        state.setProvider("DEEPSEEK");
        state.setModelName("deepseek-chat");

        RuntimeAgentConfig config = new IdeRuntimeConfigResolver(() -> state).resolve(Map.of(
                "model", "deepseek-reasoner",
                "agent", "plan",
                "language", "zh-CN",
                "temperature", 0.25
        ));

        assertEquals("DEEPSEEK", config.getProvider());
        assertEquals("deepseek-reasoner", config.getModel());
        assertEquals("https://api.deepseek.com/v1", config.getBaseUrl());
        assertEquals("deepseek-key", config.getApiKey());
        assertEquals("plan", config.getAgent());
        assertEquals("zh-CN", config.getLanguage());
        assertEquals(0.25, config.getTemperature());
    }

    @Test
    void resolve_shouldAllowProviderAndBaseUrlOverrideFromRawConfig() {
        RikkiCredentials.injectForTest("OPENAI", "openai-key");
        RikkiSettings.State state = new RikkiSettings.State();
        state.setProvider("DEEPSEEK");
        state.setModelName("deepseek-chat");

        RuntimeAgentConfig config = new IdeRuntimeConfigResolver(() -> state).resolve(Map.of(
                "provider", "OPENAI",
                "model", "gpt-4o",
                "baseUrl", "https://proxy.example.com/v1/"
        ));

        assertEquals("OPENAI", config.getProvider());
        assertEquals("gpt-4o", config.getModel());
        assertEquals("https://proxy.example.com/v1", config.getBaseUrl());
        assertEquals("openai-key", config.getApiKey());
    }
}
