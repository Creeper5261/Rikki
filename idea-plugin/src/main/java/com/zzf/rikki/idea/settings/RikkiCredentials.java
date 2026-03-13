package com.zzf.rikki.idea.settings;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.application.ApplicationManager;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class RikkiCredentials {
    private static final String SERVICE = "RikkiCodeAgent";
    private static final List<String> PROVIDERS = List.of(
            "DEEPSEEK",
            "OPENAI",
            "GEMINI",
            "MOONSHOT",
            "OLLAMA",
            "CUSTOM",
            "COMPLETION_OVERRIDE"
    );

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private RikkiCredentials() {
    }

    public static String get(String key) {
        var application = ApplicationManager.getApplication();
        if (!loaded && application != null && !application.isDispatchThread()) {
            synchronized (RikkiCredentials.class) {
                if (!loaded) {
                    loadAll();
                }
            }
        }
        return CACHE.getOrDefault(normalize(key), "");
    }

    public static void set(String key, String value) {
        String normalized = normalize(key);
        PasswordSafe.getInstance().setPassword(attributes(normalized), value == null || value.isBlank() ? null : value);
        CACHE.put(normalized, value == null ? "" : value);
    }

    public static void loadAll() {
        for (String provider : PROVIDERS) {
            CACHE.put(provider, safeRead(provider));
        }
        loaded = true;
    }

    public static boolean isLoaded() {
        return loaded;
    }

    public static void injectForTest(String key, String value) {
        CACHE.put(normalize(key), value == null ? "" : value);
    }

    public static void clearForTest() {
        CACHE.clear();
        loaded = false;
    }

    private static String safeRead(String key) {
        try {
            String value = PasswordSafe.getInstance().getPassword(attributes(key));
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static CredentialAttributes attributes(String key) {
        return new CredentialAttributes(CredentialAttributesKt.generateServiceName(SERVICE, key));
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
    }
}
