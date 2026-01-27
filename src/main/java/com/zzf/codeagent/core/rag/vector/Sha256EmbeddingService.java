package com.zzf.codeagent.core.rag.vector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class Sha256EmbeddingService implements EmbeddingService {
    private final int dims;

    public Sha256EmbeddingService(int dims) {
        this.dims = dims;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dims];
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        byte[] hash = sha256(bytes);
        for (int i = 0; i < dims; i++) {
            int b = hash[i % hash.length] & 0xFF;
            v[i] = (b / 255.0f) * 2.0f - 1.0f;
        }
        return l2Normalize(v);
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static float[] l2Normalize(float[] v) {
        double sum = 0.0;
        for (int i = 0; i < v.length; i++) {
            sum += (double) v[i] * (double) v[i];
        }
        double norm = Math.sqrt(sum);
        if (norm == 0.0) {
            return v;
        }
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }
}
