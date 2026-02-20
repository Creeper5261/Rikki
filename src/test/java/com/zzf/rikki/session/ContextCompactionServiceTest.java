package com.zzf.rikki.session;

import com.zzf.rikki.agent.AgentService;
import com.zzf.rikki.bus.AgentBus;
import com.zzf.rikki.config.ConfigInfo;
import com.zzf.rikki.config.ConfigManager;
import com.zzf.rikki.llm.LLMService;
import com.zzf.rikki.provider.ModelInfo;
import com.zzf.rikki.provider.ProviderManager;
import com.zzf.rikki.session.model.MessageV2;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class ContextCompactionServiceTest {

    @Mock
    private SessionService sessionService;
    @Mock
    private LLMService llmService;
    @Mock
    private AgentService agentService;
    @Mock
    private ConfigManager configManager;
    @Mock
    private ProviderManager providerManager;
    @Mock
    private AgentBus agentBus;

    private ContextCompactionService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ContextCompactionService(
                sessionService,
                llmService,
                agentService,
                configManager,
                providerManager,
                agentBus
        );
    }

    @Test
    void isOverflow_returnsFalse_whenTokensAreNull() {
        ConfigInfo config = new ConfigInfo();
        when(configManager.getConfig()).thenReturn(config);

        ModelInfo model = ModelInfo.builder()
                .limit(ModelInfo.ModelLimit.builder().context(128000).output(4096).build())
                .build();

        assertFalse(service.isOverflow(null, model));
    }

    @Test
    void isOverflow_returnsFalse_whenCompactionAutoDisabled() {
        ConfigInfo config = new ConfigInfo();
        ConfigInfo.CompactionConfig compaction = new ConfigInfo.CompactionConfig();
        compaction.setAuto(false);
        config.setCompaction(compaction);
        when(configManager.getConfig()).thenReturn(config);

        MessageV2.TokenUsage tokens = MessageV2.TokenUsage.builder()
                .input(500000)
                .output(500000)
                .reasoning(0)
                .build();
        ModelInfo model = ModelInfo.builder()
                .limit(ModelInfo.ModelLimit.builder().context(128000).output(4096).build())
                .build();

        assertFalse(service.isOverflow(tokens, model));
    }

    @Test
    void isOverflow_returnsTrue_whenTokenCountExceedsUsableWindow() {
        ConfigInfo config = new ConfigInfo();
        when(configManager.getConfig()).thenReturn(config);

        MessageV2.TokenUsage tokens = MessageV2.TokenUsage.builder()
                .input(9200)
                .output(100)
                .reasoning(0)
                .cache(MessageV2.CacheUsage.builder().read(0).write(0).build())
                .build();
        ModelInfo model = ModelInfo.builder()
                .limit(ModelInfo.ModelLimit.builder().context(10000).output(1000).build())
                .build();

        assertTrue(service.isOverflow(tokens, model));
    }
}
