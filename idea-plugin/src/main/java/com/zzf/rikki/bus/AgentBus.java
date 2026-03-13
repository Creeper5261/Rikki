package com.zzf.rikki.bus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class AgentBus {
    private final Map<String, List<Subscription>> subscriptions = new ConcurrentHashMap<>();

    public static final class EventInstance {
        private final String type;
        private final Object properties;

        public EventInstance(String type, Object properties) {
            this.type = type;
            this.properties = properties;
        }

        public String getType() {
            return type;
        }

        public Object getProperties() {
            return properties;
        }
    }

    @FunctionalInterface
    public interface Subscription extends Consumer<EventInstance> {
    }

    public CompletableFuture<Void> publish(String type, Object properties) {
        EventInstance event = new EventInstance(type, properties);
        Throwable firstError = null;
        for (String key : Arrays.asList(type, "*")) {
            List<Subscription> subs = subscriptions.getOrDefault(key, Collections.emptyList());
            for (Subscription sub : new ArrayList<>(subs)) {
                try {
                    sub.accept(event);
                } catch (Throwable throwable) {
                    if (firstError == null) {
                        firstError = throwable;
                    } else {
                        firstError.addSuppressed(throwable);
                    }
                }
            }
        }
        if (firstError != null) {
            return CompletableFuture.failedFuture(firstError);
        }
        return CompletableFuture.completedFuture(null);
    }

    public Runnable subscribe(String type, Subscription callback) {
        subscriptions.computeIfAbsent(type, ignored -> Collections.synchronizedList(new ArrayList<>())).add(callback);
        return () -> unsubscribe(type, callback);
    }

    public Runnable subscribeAll(Subscription callback) {
        return subscribe("*", callback);
    }

    public void once(String type, Predicate<EventInstance> callback) {
        final Runnable[] unsubscribe = {null};
        unsubscribe[0] = subscribe(type, event -> {
            if (callback.test(event) && unsubscribe[0] != null) {
                unsubscribe[0].run();
            }
        });
    }

    private void unsubscribe(String type, Subscription callback) {
        List<Subscription> subs = subscriptions.get(type);
        if (subs != null) {
            subs.remove(callback);
        }
    }
}