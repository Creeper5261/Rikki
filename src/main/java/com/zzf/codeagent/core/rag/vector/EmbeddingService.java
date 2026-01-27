package com.zzf.codeagent.core.rag.vector;

public interface EmbeddingService {
    float[] embed(String text);
}
