package com.zzf.rikki.session;

import lombok.Builder;
import lombok.Data;
import lombok.extern.jackson.Jacksonized;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Session 信息模型 (对齐 OpenCode Session.Info)
 */
@Data
@Builder
@Jacksonized
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionInfo {
    private String id;
    private String slug;
    private String projectID;
    private String directory;
    private String parentID;
    private SessionSummary summary;
    private ShareInfo share;
    private String title;
    private String agent;
    private String workspaceName;
    private Map<String, Object> ideContext;
    private String version;
    private SessionTime time;
    private Map<String, Object> permission; 
    private SessionRevert revert;

    @Data
    @Builder
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionSummary {
        private int additions;
        private int deletions;
        private int files;
        
    }

    @Data
    @Builder
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ShareInfo {
        private String secret;
        private String url;
    }

    @Data
    @Builder
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionTime {
        private long created;
        private long updated;
        private Long compacting;
        private Long archived;
    }

    @Data
    @Builder
    @Jacksonized
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionRevert {
        private String messageID;
        private String partID;
        private String snapshot;
        private String diff;
    }
}
