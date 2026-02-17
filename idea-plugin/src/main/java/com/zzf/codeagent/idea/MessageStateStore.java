package com.zzf.codeagent.idea;

import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

final class MessageStateStore<T> {
    private final String pendingKey;
    private final Supplier<T> pendingCreator;
    private final Function<String, T> identifiedCreator;

    MessageStateStore(String pendingKey, Supplier<T> pendingCreator, Function<String, T> identifiedCreator) {
        this.pendingKey = pendingKey;
        this.pendingCreator = pendingCreator;
        this.identifiedCreator = identifiedCreator;
    }

    T resolve(Map<String, T> store, String messageId) {
        String normalized = normalize(messageId);
        if (!normalized.isBlank()) {
            T existing = store.get(normalized);
            if (existing != null) {
                return existing;
            }
            T pending = store.remove(pendingKey);
            if (pending != null) {
                store.put(normalized, pending);
                return pending;
            }
            T created = identifiedCreator.apply(normalized);
            store.put(normalized, created);
            return created;
        }
        T pending = store.get(pendingKey);
        if (pending != null) {
            return pending;
        }
        T created = pendingCreator.get();
        store.put(pendingKey, created);
        return created;
    }

    T find(Map<String, T> store, String messageId) {
        String normalized = normalize(messageId);
        if (normalized.isBlank()) {
            return store.get(pendingKey);
        }
        return store.get(normalized);
    }

    T last(Map<String, T> store) {
        T last = null;
        for (T value : store.values()) {
            last = value;
        }
        if (last != null) {
            return last;
        }
        return resolve(store, "");
    }

    String normalize(String messageId) {
        return messageId == null ? "" : messageId.trim();
    }
}
