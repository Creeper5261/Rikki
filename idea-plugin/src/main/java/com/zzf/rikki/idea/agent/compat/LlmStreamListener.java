package com.zzf.rikki.idea.agent.compat;

public interface LlmStreamListener {
    void onMessageDelta(String messageId, String delta);

    void onThoughtDelta(String messageId, String delta);

    void onThoughtEnd(String messageId);
}
