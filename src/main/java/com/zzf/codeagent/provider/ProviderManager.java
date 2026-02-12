package com.zzf.codeagent.provider;

import com.zzf.codeagent.config.ConfigInfo;
import com.zzf.codeagent.config.ConfigManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Provider 管理器 (对齐 OpenCode Provider 命名空间)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderManager {

    private final ConfigManager configManager;

    /**
     * 获取模型信息
     */
    public Optional<ModelInfo> getModel(String providerID, String modelID) {
        ConfigInfo config = configManager.getConfig();
        if (config.getProvider() == null) return Optional.empty();

        ConfigInfo.ProviderConfig providerConfig = config.getProvider().get(providerID);
        if (providerConfig == null || providerConfig.getModels() == null) return Optional.empty();

        ConfigInfo.ModelConfig modelConfig = providerConfig.getModels().get(modelID);
        if (modelConfig == null) return Optional.empty();

        return Optional.of(ModelInfo.builder()
                .id(modelID)
                .providerID(providerID)
                .name(modelConfig.getName())
                .limit(ModelInfo.ModelLimit.builder()
                        .context(modelConfig.getLimit() != null ? modelConfig.getLimit().getContext() : 0)
                        .output(modelConfig.getLimit() != null ? modelConfig.getLimit().getOutput() : 0)
                        .build())
                .options(modelConfig.getOptions())
                .build());
    }

    /**
     * 获取默认模型
     */
    public ModelInfo getDefaultModel() {
        try {
            ConfigInfo config = configManager.getConfig();
            if (config == null) return getHardcodedDefault();
            
            String defaultModelPath = config.getModel();
            if (defaultModelPath != null && defaultModelPath.contains("/")) {
                String[] parts = defaultModelPath.split("/");
                return getModel(parts[0], parts[1]).orElseGet(this::getHardcodedDefault);
            }
            
            // Handle single string model name (e.g. "DEEPSEEK" or "deepseek-chat")
            if (defaultModelPath != null) {
                String modelName = defaultModelPath.toLowerCase();
                if (modelName.contains("deepseek")) {
                    return getModel("deepseek", "deepseek-chat").orElseGet(this::getHardcodedDefault);
                }
                return getModel("deepseek", modelName).orElseGet(this::getHardcodedDefault);
            }
        } catch (Exception e) {
            log.warn("Failed to get default model from config, using hardcoded default", e);
        }

        return getHardcodedDefault();
    }

    private ModelInfo getHardcodedDefault() {
        log.info("Using hardcoded default model: deepseek/deepseek-chat");
        return ModelInfo.builder()
                .id("deepseek-chat")
                .providerID("deepseek")
                .name("DeepSeek Chat")
                .limit(ModelInfo.ModelLimit.builder()
                        .context(64000)
                        .output(4000)
                        .build())
                .build();
    }

    /**
     * 解析模型字符串 (provider/model)
     */
    public String[] parseModel(String model) {
        if (model == null) return null;
        return model.split("/", 2);
    }
}
