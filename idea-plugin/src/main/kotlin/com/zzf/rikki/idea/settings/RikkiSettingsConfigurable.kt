package com.zzf.rikki.idea.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.*

class RikkiSettingsConfigurable : Configurable {

    // ── Chat provider enum ─────────────────────────────────────────────────────

    private enum class ChatProvider(
        val label: String,
        val url: String,
        val defaultModel: String,
        val models: List<String>
    ) {
        DEEPSEEK(
            "DeepSeek",
            "https://api.deepseek.com/v1",
            "deepseek-chat",
            listOf("deepseek-chat", "deepseek-reasoner")
        ),
        OPENAI(
            "OpenAI (GPT)",
            "https://api.openai.com/v1",
            "gpt-4o",
            listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4o", "gpt-4o-mini", "o3", "o3-mini", "o4-mini")
        ),
        GEMINI(
            "Google (Gemini)",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash",
            listOf("gemini-2.5-pro", "gemini-2.5-flash", "gemini-2.0-flash",
                   "gemini-2.0-flash-lite", "gemini-1.5-pro", "gemini-1.5-flash")
        ),
        MOONSHOT(
            "Moonshot (Kimi)",
            "https://api.moonshot.cn/v1",
            "moonshot-v1-8k",
            listOf("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-latest")
        ),
        OLLAMA(
            "Ollama (local, no key)",
            "http://localhost:11434/v1",
            "qwen2.5-coder:7b",
            listOf("qwen2.5-coder:7b", "qwen2.5-coder:14b", "qwen2.5-coder:32b",
                   "llama3.2", "llama3.1", "deepseek-coder-v2", "codellama")
        ),
        CUSTOM(
            "Custom",
            "",
            "",
            emptyList()
        );

        companion object {
            fun fromName(name: String) = values().firstOrNull { it.name == name } ?: DEEPSEEK
        }
    }

    // ── Completion provider enum ───────────────────────────────────────────────
    // Recommended models here are fast/cheap options; FIM providers use the
    // /completions endpoint, others use /chat/completions.

    private enum class CompletionProvider(
        val label: String,
        val baseUrl: String,
        val defaultModel: String,
        val models: List<String>,
        /** Editable URL field (OLLAMA/CUSTOM override). */
        val urlEditable: Boolean = false
    ) {
        SAME_AS_CHAT(
            "Same as Chat Provider",
            "",
            "",
            emptyList()
        ),
        DEEPSEEK(
            "DeepSeek  [FIM, beta endpoint]",
            "https://api.deepseek.com/beta",
            "deepseek-chat",
            listOf("deepseek-chat")
        ),
        OPENAI(
            "OpenAI  [chat format]",
            "https://api.openai.com/v1",
            "gpt-4.1-nano",
            listOf("gpt-4.1-nano", "gpt-4.1-mini", "gpt-4o-mini")
        ),
        GEMINI(
            "Google (Gemini)  [chat format]",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.0-flash-lite",
            listOf("gemini-2.0-flash-lite", "gemini-2.0-flash", "gemini-1.5-flash")
        ),
        MOONSHOT(
            "Moonshot (Kimi)  [chat format]",
            "https://api.moonshot.cn/v1",
            "moonshot-v1-8k",
            listOf("moonshot-v1-8k", "moonshot-v1-32k")
        ),
        OLLAMA(
            "Ollama  [FIM, local]",
            "http://localhost:11434/v1",
            "qwen2.5-coder:7b",
            listOf("qwen2.5-coder:7b", "qwen2.5-coder:14b", "qwen2.5-coder:32b", "codellama"),
            urlEditable = true
        ),
        CUSTOM(
            "Custom",
            "",
            "",
            emptyList(),
            urlEditable = true
        );

        companion object {
            fun fromName(name: String): CompletionProvider =
                values().firstOrNull { it.name == name } ?: SAME_AS_CHAT
        }
    }

    // ── UI fields — Chat ───────────────────────────────────────────────────────

    private var panel: JPanel?         = null
    private var currentChatProvider: ChatProvider = ChatProvider.DEEPSEEK

    private val chatApiKeyField   = JBPasswordField()
    private val chatProviderCombo = JComboBox<ChatProvider>()
    private val chatBaseUrlField  = JBTextField()
    private val chatModelCombo    = JComboBox<String>()

    /** In-memory cache for chat API keys (keyed by provider). */
    private val chatApiKeyCache = mutableMapOf<ChatProvider, String>()

    // ── UI fields — Completion ─────────────────────────────────────────────────

    private var currentCompletionProvider: CompletionProvider = CompletionProvider.SAME_AS_CHAT

    private val completionBox          = JCheckBox("Enable inline TAB completion")
    private val completionProviderCombo = JComboBox<CompletionProvider>()
    private val completionApiKeyField  = JBPasswordField()
    private val completionModelCombo   = JComboBox<String>()
    private val completionBaseUrlField = JBTextField()

    // ── Configurable contract ──────────────────────────────────────────────────

    override fun getDisplayName() = "Rikki Code Agent"

    override fun createComponent(): JComponent {
        // ── Chat provider combo ────────────────────────────────────────────────
        ChatProvider.values().forEach { chatProviderCombo.addItem(it) }
        chatProviderCombo.renderer = labelRenderer { (it as? ChatProvider)?.label }
        chatModelCombo.isEditable = true

        chatProviderCombo.addActionListener {
            val p = chatProviderCombo.selectedItem as? ChatProvider ?: return@addActionListener
            if (p == currentChatProvider) return@addActionListener

            chatApiKeyCache[currentChatProvider] = String(chatApiKeyField.password)
            currentChatProvider = p

            chatApiKeyField.text = chatApiKeyCache[p] ?: ""
            if (p != ChatProvider.CUSTOM) chatBaseUrlField.text = p.url
            val urlEditable = p == ChatProvider.CUSTOM || p == ChatProvider.OLLAMA
            chatBaseUrlField.isEnabled  = urlEditable
            chatBaseUrlField.isEditable = urlEditable
            rebuildChatModelCombo(p, p.defaultModel)
        }

        // ── Completion provider combo ──────────────────────────────────────────
        CompletionProvider.values().forEach { completionProviderCombo.addItem(it) }
        completionProviderCombo.renderer = labelRenderer { (it as? CompletionProvider)?.label }
        completionModelCombo.isEditable = true

        completionProviderCombo.addActionListener {
            val p = completionProviderCombo.selectedItem as? CompletionProvider ?: return@addActionListener
            if (p == currentCompletionProvider) return@addActionListener
            currentCompletionProvider = p
            applyCompletionProviderToUi(p)
        }

        // ── Layout ─────────────────────────────────────────────────────────────
        panel = FormBuilder.createFormBuilder()
            // Chat section
            .addLabeledComponent(JBLabel("Provider:"),   chatProviderCombo, true)
            .addLabeledComponent(JBLabel("API Key:"),    chatApiKeyField,   true)
            .addLabeledComponent(JBLabel("Base URL:"),   chatBaseUrlField,  true)
            .addLabeledComponent(JBLabel("Chat model:"), chatModelCombo,    true)
            .addSeparator()
            // Completion section
            .addComponent(completionBox)
            .addLabeledComponent(JBLabel("Completion provider:"), completionProviderCombo, true)
            .addLabeledComponent(
                JBLabel("Completion API key:"),
                completionApiKeyField, true
            )
            .addLabeledComponent(JBLabel("Completion model:"),    completionModelCombo,    true)
            .addLabeledComponent(JBLabel("Completion base URL:"), completionBaseUrlField,  true)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun labelRenderer(getText: (Any?) -> String?) = object : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
        ) = super.getListCellRendererComponent(list, getText(value) ?: value, index, isSelected, cellHasFocus)
    }

    private fun rebuildChatModelCombo(provider: ChatProvider, selectedModel: String) {
        chatModelCombo.removeAllItems()
        provider.models.forEach { chatModelCombo.addItem(it) }
        chatModelCombo.selectedItem = selectedModel
        if (chatModelCombo.selectedIndex < 0) chatModelCombo.selectedItem = selectedModel
    }

    private fun rebuildCompletionModelCombo(provider: CompletionProvider, selectedModel: String) {
        completionModelCombo.removeAllItems()
        provider.models.forEach { completionModelCombo.addItem(it) }
        completionModelCombo.selectedItem = selectedModel
        if (completionModelCombo.selectedIndex < 0) completionModelCombo.selectedItem = selectedModel
    }

    private fun applyCompletionProviderToUi(p: CompletionProvider) {
        val isSameAsChat = p == CompletionProvider.SAME_AS_CHAT
        completionApiKeyField.isEnabled  = !isSameAsChat
        completionApiKeyField.isEditable = !isSameAsChat
        if (isSameAsChat) completionApiKeyField.text = ""

        completionBaseUrlField.isEnabled  = p.urlEditable
        completionBaseUrlField.isEditable = p.urlEditable
        if (!p.urlEditable) completionBaseUrlField.text = p.baseUrl

        rebuildCompletionModelCombo(p, if (isSameAsChat) "" else p.defaultModel)
    }

    private fun currentChatModelText(): String =
        ((chatModelCombo.editor?.item as? String)?.trim()
            ?: (chatModelCombo.selectedItem as? String)?.trim() ?: "")

    private fun currentCompletionModelText(): String =
        ((completionModelCombo.editor?.item as? String)?.trim()
            ?: (completionModelCombo.selectedItem as? String)?.trim() ?: "")

    private fun savedChatKeyFor(p: ChatProvider): String = RikkiCredentials.get(p.name)

    // ── Configurable: isModified ───────────────────────────────────────────────

    override fun isModified(): Boolean {
        val s = RikkiSettings.getInstance().state

        // Chat
        val snap = chatApiKeyCache.toMutableMap()
        snap[currentChatProvider] = String(chatApiKeyField.password)
        if (s.provider != currentChatProvider.name) return true
        if (currentChatModelText() != s.modelName) return true
        if (chatBaseUrlField.text.trim().trimEnd('/') != s.customBaseUrl) return true
        if (ChatProvider.values().any { p -> (snap[p] ?: "") != savedChatKeyFor(p) }) return true

        // Completion
        if (completionBox.isSelected != s.completionEnabled) return true
        val cpStr = if (currentCompletionProvider == CompletionProvider.SAME_AS_CHAT) "" else currentCompletionProvider.name
        if (s.completionProvider != cpStr) return true
        val cpKey = if (currentCompletionProvider == CompletionProvider.SAME_AS_CHAT) "" else String(completionApiKeyField.password)
        if (RikkiCredentials.get("COMPLETION_OVERRIDE") != cpKey) return true
        if (currentCompletionModelText() != s.completionModelName) return true
        val cpUrl = if (currentCompletionProvider.urlEditable) completionBaseUrlField.text.trim() else ""
        if (s.completionCustomBaseUrl != cpUrl) return true

        return false
    }

    // ── Configurable: apply ────────────────────────────────────────────────────

    override fun apply() {
        chatApiKeyCache[currentChatProvider] = String(chatApiKeyField.password)
        val s = RikkiSettings.getInstance().state

        // Chat
        s.provider      = currentChatProvider.name
        s.modelName     = currentChatModelText()
        s.customBaseUrl = chatBaseUrlField.text.trim().trimEnd('/')
        // Persist each provider's key to PasswordSafe
        RikkiCredentials.set("DEEPSEEK",  chatApiKeyCache[ChatProvider.DEEPSEEK]  ?: "")
        RikkiCredentials.set("OPENAI",    chatApiKeyCache[ChatProvider.OPENAI]    ?: "")
        RikkiCredentials.set("GEMINI",    chatApiKeyCache[ChatProvider.GEMINI]    ?: "")
        RikkiCredentials.set("MOONSHOT",  chatApiKeyCache[ChatProvider.MOONSHOT]  ?: "")
        RikkiCredentials.set("OLLAMA",    chatApiKeyCache[ChatProvider.OLLAMA]    ?: "")
        RikkiCredentials.set("CUSTOM",    chatApiKeyCache[ChatProvider.CUSTOM]    ?: "")

        // Completion
        s.completionEnabled = completionBox.isSelected
        s.completionProvider = if (currentCompletionProvider == CompletionProvider.SAME_AS_CHAT) ""
                               else currentCompletionProvider.name
        val completionKey = if (currentCompletionProvider == CompletionProvider.SAME_AS_CHAT) ""
                            else String(completionApiKeyField.password)
        RikkiCredentials.set("COMPLETION_OVERRIDE", completionKey)
        s.completionModelName     = currentCompletionModelText()
        s.completionCustomBaseUrl = if (currentCompletionProvider.urlEditable)
                                        completionBaseUrlField.text.trim() else ""
    }

    // ── Configurable: reset ────────────────────────────────────────────────────

    override fun reset() {
        val s = RikkiSettings.getInstance().state

        // ── Chat ───────────────────────────────────────────────────────────────
        currentChatProvider = ChatProvider.fromName(s.provider)

        // Load all keys fresh from PasswordSafe into the in-memory UI cache
        chatApiKeyCache[ChatProvider.DEEPSEEK]  = RikkiCredentials.get("DEEPSEEK")
        chatApiKeyCache[ChatProvider.OPENAI]    = RikkiCredentials.get("OPENAI")
        chatApiKeyCache[ChatProvider.GEMINI]    = RikkiCredentials.get("GEMINI")
        chatApiKeyCache[ChatProvider.MOONSHOT]  = RikkiCredentials.get("MOONSHOT")
        chatApiKeyCache[ChatProvider.OLLAMA]    = RikkiCredentials.get("OLLAMA")
        chatApiKeyCache[ChatProvider.CUSTOM]    = RikkiCredentials.get("CUSTOM")

        val chatUrlEditable = currentChatProvider == ChatProvider.CUSTOM || currentChatProvider == ChatProvider.OLLAMA
        chatBaseUrlField.text      = if (chatUrlEditable) s.customBaseUrl else currentChatProvider.url
        chatBaseUrlField.isEnabled  = chatUrlEditable
        chatBaseUrlField.isEditable = chatUrlEditable

        rebuildChatModelCombo(currentChatProvider, s.modelName)
        // Set combo last so listener's early-return (p == currentChatProvider) fires
        chatProviderCombo.selectedItem = currentChatProvider
        chatApiKeyField.text = chatApiKeyCache[currentChatProvider] ?: ""

        // ── Completion ─────────────────────────────────────────────────────────
        completionBox.isSelected = s.completionEnabled

        currentCompletionProvider = CompletionProvider.fromName(
            s.completionProvider.ifBlank { "SAME_AS_CHAT" }
        )
        completionProviderCombo.selectedItem = currentCompletionProvider

        applyCompletionProviderToUi(currentCompletionProvider)

        // Restore saved key and model (after applyCompletionProviderToUi which may clear them)
        if (currentCompletionProvider != CompletionProvider.SAME_AS_CHAT) {
            completionApiKeyField.text = RikkiCredentials.get("COMPLETION_OVERRIDE")
        }
        // Restore custom URL if editable
        if (currentCompletionProvider.urlEditable && s.completionCustomBaseUrl.isNotBlank()) {
            completionBaseUrlField.text = s.completionCustomBaseUrl
        }
        // Restore saved completion model (override the default set by applyCompletionProviderToUi)
        completionModelCombo.selectedItem = s.completionModelName
        if (completionModelCombo.selectedIndex < 0) completionModelCombo.selectedItem = s.completionModelName
    }
}
