package com.zzf.rikki.idea.settings;

import java.util.List;

public final class ProviderDescriptor {
    private final String name;
    private final String label;
    private final String defaultBaseUrl;
    private final String defaultModel;
    private final List<String> models;
    private final boolean urlEditable;
    private final boolean requiresApiKey;
    private final boolean fim;
    private final boolean sameAsChat;

    public ProviderDescriptor(
            String name,
            String label,
            String defaultBaseUrl,
            String defaultModel,
            List<String> models,
            boolean urlEditable,
            boolean requiresApiKey,
            boolean fim,
            boolean sameAsChat
    ) {
        this.name = name;
        this.label = label;
        this.defaultBaseUrl = defaultBaseUrl;
        this.defaultModel = defaultModel;
        this.models = List.copyOf(models);
        this.urlEditable = urlEditable;
        this.requiresApiKey = requiresApiKey;
        this.fim = fim;
        this.sameAsChat = sameAsChat;
    }

    public String name() {
        return name;
    }

    public String label() {
        return label;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public String defaultModel() {
        return defaultModel;
    }

    public List<String> models() {
        return models;
    }

    public boolean urlEditable() {
        return urlEditable;
    }

    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    public boolean fim() {
        return fim;
    }

    public boolean sameAsChat() {
        return sameAsChat;
    }

    @Override
    public String toString() {
        return label;
    }
}
