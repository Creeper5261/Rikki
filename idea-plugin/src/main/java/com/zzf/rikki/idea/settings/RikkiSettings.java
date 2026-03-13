package com.zzf.rikki.idea.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Storage;
import com.intellij.util.xmlb.XmlSerializerUtil;

@com.intellij.openapi.components.State(name = "RikkiSettings", storages = @Storage("rikki.xml"))
public class RikkiSettings implements PersistentStateComponent<RikkiSettings.State> {
    private final State myState = new State();

    @Override
    public State getState() {
        return myState;
    }

    @Override
    public void loadState(State state) {
        XmlSerializerUtil.copyBean(state, myState);
        ApplicationManager.getApplication().executeOnPooledThread(RikkiCredentials::loadAll);
    }

    public static RikkiSettings getInstance() {
        return ApplicationManager.getApplication().getService(RikkiSettings.class);
    }

    public static final class State {
        private String provider = "DEEPSEEK";
        private String modelName = "deepseek-chat";
        private String customBaseUrl = "";
        private boolean completionEnabled = true;
        private String completionProvider = "";
        private String completionModelName = "";
        private String completionCustomBaseUrl = "";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider == null ? "DEEPSEEK" : provider;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName == null ? "" : modelName;
        }

        public String getCustomBaseUrl() {
            return customBaseUrl;
        }

        public void setCustomBaseUrl(String customBaseUrl) {
            this.customBaseUrl = customBaseUrl == null ? "" : ProviderCatalog.trimSlashes(customBaseUrl);
        }

        public boolean isCompletionEnabled() {
            return completionEnabled;
        }

        public void setCompletionEnabled(boolean completionEnabled) {
            this.completionEnabled = completionEnabled;
        }

        public String getCompletionProvider() {
            return completionProvider;
        }

        public void setCompletionProvider(String completionProvider) {
            this.completionProvider = completionProvider == null ? "" : completionProvider;
        }

        public String getCompletionModelName() {
            return completionModelName;
        }

        public void setCompletionModelName(String completionModelName) {
            this.completionModelName = completionModelName == null ? "" : completionModelName;
        }

        public String getCompletionCustomBaseUrl() {
            return completionCustomBaseUrl;
        }

        public void setCompletionCustomBaseUrl(String completionCustomBaseUrl) {
            this.completionCustomBaseUrl = completionCustomBaseUrl == null ? "" : ProviderCatalog.trimSlashes(completionCustomBaseUrl);
        }

        public String currentApiKey() {
            return RikkiCredentials.get(provider);
        }

        public String currentBaseUrl() {
            return ProviderCatalog.chatBaseUrl(provider, customBaseUrl);
        }

        public String completionEffectiveProvider() {
            return completionProvider == null || completionProvider.isBlank() ? provider : completionProvider;
        }

        public String completionEffectiveApiKey() {
            String override = RikkiCredentials.get("COMPLETION_OVERRIDE");
            return override.isBlank() ? RikkiCredentials.get(completionEffectiveProvider()) : override;
        }

        public String completionEffectiveModel() {
            return completionModelName == null || completionModelName.isBlank() ? modelName : completionModelName;
        }

        public String completionEffectiveBaseUrl() {
            return ProviderCatalog.completionBaseUrl(this);
        }

        public boolean completionUsesFim() {
            return ProviderCatalog.completionUsesFim(completionEffectiveProvider());
        }
    }
}
