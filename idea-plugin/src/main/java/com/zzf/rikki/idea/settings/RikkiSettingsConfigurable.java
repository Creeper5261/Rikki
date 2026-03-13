package com.zzf.rikki.idea.settings;

import com.intellij.openapi.options.Configurable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JCheckBox;
import javax.swing.JList;
import javax.swing.JPanel;
import java.awt.Component;
import java.util.LinkedHashMap;
import java.util.Map;

public class RikkiSettingsConfigurable implements Configurable {
    private JPanel panel;

    private ProviderDescriptor currentChatProvider = ProviderCatalog.chatProvider("DEEPSEEK");
    private ProviderDescriptor currentCompletionProvider = ProviderCatalog.completionProvider("SAME_AS_CHAT");

    private final JBPasswordField chatApiKeyField = new JBPasswordField();
    private final JComboBox<ProviderDescriptor> chatProviderCombo = new JComboBox<>();
    private final JBTextField chatBaseUrlField = new JBTextField();
    private final JComboBox<String> chatModelCombo = new JComboBox<>();
    private final Map<String, String> chatApiKeyCache = new LinkedHashMap<>();

    private final JCheckBox completionBox = new JCheckBox("Enable inline TAB completion");
    private final JComboBox<ProviderDescriptor> completionProviderCombo = new JComboBox<>();
    private final JBPasswordField completionApiKeyField = new JBPasswordField();
    private final JComboBox<String> completionModelCombo = new JComboBox<>();
    private final JBTextField completionBaseUrlField = new JBTextField();

    @Override
    public String getDisplayName() {
        return "Rikki Code Agent";
    }

    @Override
    public JComponent createComponent() {
        if (panel != null) {
            return panel;
        }
        for (ProviderDescriptor provider : ProviderCatalog.chatProviders()) {
            chatProviderCombo.addItem(provider);
        }
        chatProviderCombo.setRenderer(new ProviderRenderer());
        chatModelCombo.setEditable(true);
        chatProviderCombo.addActionListener(event -> onChatProviderChanged((ProviderDescriptor) chatProviderCombo.getSelectedItem()));

        for (ProviderDescriptor provider : ProviderCatalog.completionProviders()) {
            completionProviderCombo.addItem(provider);
        }
        completionProviderCombo.setRenderer(new ProviderRenderer());
        completionModelCombo.setEditable(true);
        completionProviderCombo.addActionListener(event -> onCompletionProviderChanged((ProviderDescriptor) completionProviderCombo.getSelectedItem()));

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("Provider:"), chatProviderCombo, true)
                .addLabeledComponent(new JBLabel("API Key:"), chatApiKeyField, true)
                .addLabeledComponent(new JBLabel("Base URL:"), chatBaseUrlField, true)
                .addLabeledComponent(new JBLabel("Chat model:"), chatModelCombo, true)
                .addSeparator()
                .addComponent(completionBox)
                .addLabeledComponent(new JBLabel("Completion provider:"), completionProviderCombo, true)
                .addLabeledComponent(new JBLabel("Completion API key:"), completionApiKeyField, true)
                .addLabeledComponent(new JBLabel("Completion model:"), completionModelCombo, true)
                .addLabeledComponent(new JBLabel("Completion base URL:"), completionBaseUrlField, true)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
        reset();
        return panel;
    }

    @Override
    public boolean isModified() {
        RikkiSettings.State state = RikkiSettings.getInstance().getState();
        Map<String, String> snapshot = new LinkedHashMap<>(chatApiKeyCache);
        snapshot.put(currentChatProvider.name(), new String(chatApiKeyField.getPassword()));
        if (!state.getProvider().equals(currentChatProvider.name())) {
            return true;
        }
        if (!currentChatModelText().equals(state.getModelName())) {
            return true;
        }
        if (!ProviderCatalog.trimSlashes(chatBaseUrlField.getText()).equals(state.getCustomBaseUrl())) {
            return true;
        }
        for (ProviderDescriptor provider : ProviderCatalog.chatProviders()) {
            if (!snapshot.getOrDefault(provider.name(), "").equals(savedChatKeyFor(provider))) {
                return true;
            }
        }

        if (completionBox.isSelected() != state.isCompletionEnabled()) {
            return true;
        }
        String completionProviderName = currentCompletionProvider.sameAsChat() ? "" : currentCompletionProvider.name();
        if (!state.getCompletionProvider().equals(completionProviderName)) {
            return true;
        }
        String completionKey = currentCompletionProvider.sameAsChat() ? "" : new String(completionApiKeyField.getPassword());
        if (!RikkiCredentials.get("COMPLETION_OVERRIDE").equals(completionKey)) {
            return true;
        }
        if (!currentCompletionModelText().equals(state.getCompletionModelName())) {
            return true;
        }
        String completionUrl = currentCompletionProvider.urlEditable() ? completionBaseUrlField.getText().trim() : "";
        return !state.getCompletionCustomBaseUrl().equals(completionUrl);
    }

    @Override
    public void apply() {
        chatApiKeyCache.put(currentChatProvider.name(), new String(chatApiKeyField.getPassword()));
        RikkiSettings.State state = RikkiSettings.getInstance().getState();
        state.setProvider(currentChatProvider.name());
        state.setModelName(currentChatModelText());
        state.setCustomBaseUrl(chatBaseUrlField.getText());

        for (ProviderDescriptor provider : ProviderCatalog.chatProviders()) {
            RikkiCredentials.set(provider.name(), chatApiKeyCache.getOrDefault(provider.name(), ""));
        }

        state.setCompletionEnabled(completionBox.isSelected());
        state.setCompletionProvider(currentCompletionProvider.sameAsChat() ? "" : currentCompletionProvider.name());
        String completionKey = currentCompletionProvider.sameAsChat() ? "" : new String(completionApiKeyField.getPassword());
        RikkiCredentials.set("COMPLETION_OVERRIDE", completionKey);
        state.setCompletionModelName(currentCompletionModelText());
        state.setCompletionCustomBaseUrl(currentCompletionProvider.urlEditable() ? completionBaseUrlField.getText().trim() : "");
    }

    @Override
    public void reset() {
        RikkiSettings.State state = RikkiSettings.getInstance().getState();
        currentChatProvider = ProviderCatalog.chatProvider(state.getProvider());
        for (ProviderDescriptor provider : ProviderCatalog.chatProviders()) {
            chatApiKeyCache.put(provider.name(), RikkiCredentials.get(provider.name()));
        }

        boolean chatUrlEditable = currentChatProvider.urlEditable();
        chatBaseUrlField.setText(chatUrlEditable ? state.getCustomBaseUrl() : currentChatProvider.defaultBaseUrl());
        chatBaseUrlField.setEnabled(chatUrlEditable);
        chatBaseUrlField.setEditable(chatUrlEditable);
        rebuildModelCombo(chatModelCombo, currentChatProvider, state.getModelName());
        chatProviderCombo.setSelectedItem(currentChatProvider);
        chatApiKeyField.setText(chatApiKeyCache.getOrDefault(currentChatProvider.name(), ""));

        completionBox.setSelected(state.isCompletionEnabled());
        currentCompletionProvider = ProviderCatalog.completionProvider(
                state.getCompletionProvider().isBlank() ? "SAME_AS_CHAT" : state.getCompletionProvider()
        );
        completionProviderCombo.setSelectedItem(currentCompletionProvider);
        applyCompletionProviderToUi(currentCompletionProvider);
        if (!currentCompletionProvider.sameAsChat()) {
            completionApiKeyField.setText(RikkiCredentials.get("COMPLETION_OVERRIDE"));
        }
        if (currentCompletionProvider.urlEditable() && !state.getCompletionCustomBaseUrl().isBlank()) {
            completionBaseUrlField.setText(state.getCompletionCustomBaseUrl());
        }
        completionModelCombo.setSelectedItem(state.getCompletionModelName());
        if (completionModelCombo.getSelectedIndex() < 0) {
            completionModelCombo.setSelectedItem(state.getCompletionModelName());
        }
    }

    private void onChatProviderChanged(ProviderDescriptor provider) {
        if (provider == null || provider == currentChatProvider) {
            return;
        }
        chatApiKeyCache.put(currentChatProvider.name(), new String(chatApiKeyField.getPassword()));
        currentChatProvider = provider;
        chatApiKeyField.setText(chatApiKeyCache.getOrDefault(provider.name(), ""));
        if (!provider.urlEditable()) {
            chatBaseUrlField.setText(provider.defaultBaseUrl());
        }
        chatBaseUrlField.setEnabled(provider.urlEditable());
        chatBaseUrlField.setEditable(provider.urlEditable());
        rebuildModelCombo(chatModelCombo, provider, provider.defaultModel());
    }

    private void onCompletionProviderChanged(ProviderDescriptor provider) {
        if (provider == null || provider == currentCompletionProvider) {
            return;
        }
        currentCompletionProvider = provider;
        applyCompletionProviderToUi(provider);
    }

    private void applyCompletionProviderToUi(ProviderDescriptor provider) {
        boolean sameAsChat = provider.sameAsChat();
        completionApiKeyField.setEnabled(!sameAsChat);
        completionApiKeyField.setEditable(!sameAsChat);
        if (sameAsChat) {
            completionApiKeyField.setText("");
        }

        completionBaseUrlField.setEnabled(provider.urlEditable());
        completionBaseUrlField.setEditable(provider.urlEditable());
        if (!provider.urlEditable()) {
            completionBaseUrlField.setText(provider.defaultBaseUrl());
        }
        rebuildModelCombo(completionModelCombo, provider, sameAsChat ? "" : provider.defaultModel());
    }

    private void rebuildModelCombo(JComboBox<String> comboBox, ProviderDescriptor provider, String selectedModel) {
        comboBox.removeAllItems();
        for (String model : provider.models()) {
            comboBox.addItem(model);
        }
        comboBox.setSelectedItem(selectedModel);
        if (comboBox.getSelectedIndex() < 0) {
            comboBox.setSelectedItem(selectedModel);
        }
    }

    private String currentChatModelText() {
        return currentComboText(chatModelCombo);
    }

    private String currentCompletionModelText() {
        return currentComboText(completionModelCombo);
    }

    private static String currentComboText(JComboBox<String> comboBox) {
        Object editorItem = comboBox.getEditor() == null ? null : comboBox.getEditor().getItem();
        String editorText = editorItem instanceof String ? ((String) editorItem).trim() : "";
        if (!editorText.isBlank()) {
            return editorText;
        }
        Object selected = comboBox.getSelectedItem();
        return selected instanceof String ? ((String) selected).trim() : "";
    }

    private String savedChatKeyFor(ProviderDescriptor provider) {
        return RikkiCredentials.get(provider.name());
    }

    private static final class ProviderRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            String text = value instanceof ProviderDescriptor provider ? provider.label() : String.valueOf(value);
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus);
        }
    }
}
