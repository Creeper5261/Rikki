package com.zzf.rikki.runtime.port;

import com.zzf.rikki.idea.agent.compat.LlmChatRequest;
import com.zzf.rikki.idea.agent.compat.LlmStreamListener;
import com.zzf.rikki.idea.agent.compat.LlmStreamResult;

public interface LlmPort {
    LlmStreamResult streamChat(LlmChatRequest request, LlmStreamListener listener);
}
