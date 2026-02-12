package com.zzf.codeagent.provider;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.Map;
import java.util.List;

/**
 * 模型信息 (对齐 OpenCode Provider.Model)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelInfo {
    private String id;
    private String providerID;
    private ApiConfig api;
    private String name;
    private String family;
    private Capabilities capabilities;
    private Cost cost;
    private ModelLimit limit;
    private String status;
    private Map<String, Object> options;
    private Map<String, String> headers;
    private String releaseDate;
    private Map<String, Map<String, Object>> variants;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiConfig {
        private String id;
        private String url;
        private String npm;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Capabilities {
        private boolean temperature;
        private boolean reasoning;
        private boolean attachment;
        private boolean toolcall;
        private IOConfig input;
        private IOConfig output;
        private Object interleaved; // Boolean or Map with field
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IOConfig {
        private boolean text;
        private boolean audio;
        private boolean image;
        private boolean video;
        private boolean pdf;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cost {
        private double input;
        private double output;
        private CacheCost cache;
        private ExperimentalOver200K experimentalOver200K;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheCost {
        private double read;
        private double write;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExperimentalOver200K {
        private double input;
        private double output;
        private CacheCost cache;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelLimit {
        private Integer context;
        private Integer input;
        private Integer output;
    }
}
