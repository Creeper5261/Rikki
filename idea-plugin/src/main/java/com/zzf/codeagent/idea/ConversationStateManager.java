package com.zzf.codeagent.idea;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

final class ConversationStateManager<T> {
    private final String pendingKey;
    private final MessageStateStore<T> stateStore;
    private final Map<String, T> byMessageId = new LinkedHashMap<>();

    ConversationStateManager(String pendingKey, MessageStateStore<T> stateStore) {
        this.pendingKey = pendingKey;
        this.stateStore = stateStore;
    }

    void bindPending(T pendingState) {
        if (pendingState == null) {
            return;
        }
        byMessageId.put(pendingKey, pendingState);
    }

    T resolve(String messageId) {
        return stateStore.resolve(byMessageId, messageId);
    }

    T find(String messageId) {
        return stateStore.find(byMessageId, messageId);
    }

    T last() {
        return stateStore.last(byMessageId);
    }

    List<T> uniqueSnapshot() {
        return new ArrayList<>(new LinkedHashSet<>(byMessageId.values()));
    }
}
