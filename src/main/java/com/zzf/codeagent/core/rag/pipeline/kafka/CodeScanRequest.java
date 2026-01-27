package com.zzf.codeagent.core.rag.pipeline.kafka;

public final class CodeScanRequest {
    private final String traceId;
    private final String repoRoot;

    public CodeScanRequest(String traceId, String repoRoot) {
        this.traceId = traceId;
        this.repoRoot = repoRoot;
    }

    public String getTraceId() {
        return traceId;
    }

    public String getRepoRoot() {
        return repoRoot;
    }
}
