package com.zzf.codeagent.bus;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 核心事件总线 (对齐 OpenCode Bus)
 */
@Component
public class AgentBus {
    private final Map<String, List<Subscription>> subscriptions = new ConcurrentHashMap<>();

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class BusEvent<T> {
        private String type;
        private Class<T> payloadType;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class EventInstance {
        private String type;
        private Object properties;
    }

    public interface Subscription extends Consumer<EventInstance> {}

    /**
     * 发布事件 (对齐 OpenCode Bus.publish)
     */
    public CompletableFuture<Void> publish(String type, Object properties) {
        EventInstance event = new EventInstance(type, properties);
        Throwable firstError = null;

        for (String key : Arrays.asList(type, "*")) {
            List<Subscription> subs = subscriptions.getOrDefault(key, Collections.emptyList());
            for (Subscription sub : new ArrayList<>(subs)) {
                try {
                    sub.accept(event);
                } catch (Throwable t) {
                    if (firstError == null) {
                        firstError = t;
                    } else {
                        firstError.addSuppressed(t);
                    }
                }
            }
        }

        if (firstError != null) {
            return CompletableFuture.failedFuture(firstError);
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 发布强类型事件
     */
    public <T> CompletableFuture<Void> publish(BusEvent<T> eventType, T properties) {
        return publish(eventType.getType(), properties);
    }

    /**
     * 订阅事件 (对齐 OpenCode Bus.subscribe)
     */
    public Runnable subscribe(String type, Subscription callback) {
        subscriptions.computeIfAbsent(type, k -> Collections.synchronizedList(new ArrayList<>())).add(callback);
        return () -> unsubscribe(type, callback);
    }

    /**
     * 订阅一次 (对齐 OpenCode Bus.once)
     */
    public void once(String type, Predicate<EventInstance> callback) {
        final Runnable[] unsubscribe = {null};
        unsubscribe[0] = subscribe(type, event -> {
            if (callback.test(event)) {
                if (unsubscribe[0] != null) unsubscribe[0].run();
            }
        });
    }

    /**
     * 取消订阅
     */
    private void unsubscribe(String type, Subscription callback) {
        List<Subscription> subs = subscriptions.get(type);
        if (subs != null) {
            subs.remove(callback);
        }
    }

    /**
     * 订阅所有事件 (对齐 OpenCode Bus.subscribeAll)
     */
    public Runnable subscribeAll(Subscription callback) {
        return subscribe("*", callback);
    }
}
