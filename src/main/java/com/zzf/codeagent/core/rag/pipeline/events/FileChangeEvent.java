package com.zzf.codeagent.core.rag.pipeline.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class FileChangeEvent {
    private final String traceId;
    private final String repoRoot;
    private final String relativePath;
    private final String sha256;

    @JsonCreator
    public FileChangeEvent(
            @JsonProperty("traceId") String traceId,
            @JsonProperty("repoRoot") String repoRoot,
            @JsonProperty("relativePath") String relativePath,
            @JsonProperty("sha256") String sha256
    ) {
        this.traceId = traceId;
        this.repoRoot = repoRoot;
        this.relativePath = relativePath;
        this.sha256 = sha256;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getRepoRoot() {
        return repoRoot;
    }

    public String getRelativePath() {
        return relativePath;
    }

    public String getSha256() {
        return sha256;
    }
}
