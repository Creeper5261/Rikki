package com.zzf.codeagent.core.rag.pipeline.redis;

import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryFileHashCache implements FileHashCache {
    private final ConcurrentHashMap<String, String> map = new ConcurrentHashMap<String, String>();

    @Override
    public boolean isUnchanged(String repoRoot, String relativePath, String sha256) {
        String key = key(repoRoot, relativePath);
        String old = map.get(key);
        return old != null && old.equals(sha256);
    }

    @Override
    public void update(String repoRoot, String relativePath, String sha256) {
        map.put(key(repoRoot, relativePath), sha256);
    }

    private static String key(String repoRoot, String relativePath) {
        return repoRoot + "|" + relativePath;
    }
}
