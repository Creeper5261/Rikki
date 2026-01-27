package com.zzf.codeagent.core.rag.pipeline.redis;

public interface FileHashCache {
    boolean isUnchanged(String repoRoot, String relativePath, String sha256);

    void update(String repoRoot, String relativePath, String sha256);
}
