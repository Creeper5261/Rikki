package com.zzf.codeagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 配置管理器 (对齐 OpenCode Config.state)
 */
@Slf4j
@Service
public class ConfigManager {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private ConfigInfo config;

    @PostConstruct
    public void init() {
        loadConfig();
    }

    public void loadConfig() {
        try {
            // 简单实现：从当前目录或用户主目录读取 opencode.json
            File configFile = findConfigFile();
            if (configFile != null && configFile.exists()) {
                config = objectMapper.readValue(configFile, ConfigInfo.class);
                log.info("Loaded config from: {}", configFile.getAbsolutePath());
            } else {
                config = new ConfigInfo(); // 默认配置
                log.warn("No config file found, using default settings.");
            }
        } catch (Exception e) {
            log.error("Failed to load config", e);
            config = new ConfigInfo();
        }
    }

    private File findConfigFile() {
        // 1. 当前目录
        Path current = Paths.get("opencode.json");
        if (current.toFile().exists()) return current.toFile();

        // 2. 用户主目录 .opencode/opencode.json
        Path home = Paths.get(System.getProperty("user.home"), ".opencode", "opencode.json");
        if (home.toFile().exists()) return home.toFile();

        return null;
    }

    public ConfigInfo getConfig() {
        return config;
    }
}
