package com.zzf.rikki.idea.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.*

class RikkiSettingsConfigurable : Configurable {

    private enum class Provider(
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
        ANTHROPIC(
            "Anthropic (Claude) *",
            "https://api.anthropic.com/v1",
            "claude-opus-4-6",
            listOf("claude-opus-4-6", "claude-sonnet-4-6", "claude-haiku-4-5-20251001",
                   "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022")
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

    private var panel: JPanel? = null
    private var currentProvider: Provider = Provider.DEEPSEEK

    private val apiKeyField     = JBPasswordField()
    private val providerCombo   = JComboBox<Provider>()
    private val baseUrlField    = JBTextField()
    private val modelCombo      = JComboBox<String>()
    private val completionBox   = JCheckBox("Enable inline TAB completion")
    private val completionModel = JBTextField()

    /** In-memory API key cache keyed by provider — populated on reset(), flushed on apply(). */
    private val apiKeyCache = mutableMapOf<Provider, String>()

    override fun getDisplayName() = "Rikki Code Agent"

    override fun createComponent(): JComponent {
        Provider.values().forEach { providerCombo.addItem(it) }
        providerCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ) = super.getListCellRendererComponent(
                list, (value as? Provider)?.label ?: value, index, isSelected, cellHasFocus
            )
        }

        modelCombo.isEditable = true

        providerCombo.addActionListener {
            val p = providerCombo.selectedItem as? Provider ?: return@addActionListener
            if (p == currentProvider) return@addActionListener

            // Persist current key before switching
            apiKeyCache[currentProvider] = String(apiKeyField.password)
            currentProvider = p

            // Restore key for new provider (empty if never entered)
            apiKeyField.text = apiKeyCache[p] ?: ""

            // Update URL
            if (p != Provider.CUSTOM) baseUrlField.text = p.url
            val urlEditable = p == Provider.CUSTOM || p == Provider.OLLAMA
            baseUrlField.isEnabled  = urlEditable
            baseUrlField.isEditable = urlEditable

            // Rebuild model dropdown
            rebuildModelCombo(p, p.defaultModel)
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API Key:"),    apiKeyField,   true)
            .addLabeledComponent(JBLabel("Provider:"),   providerCombo, true)
            .addLabeledComponent(JBLabel("Base URL:"),   baseUrlField,  true)
            .addLabeledComponent(JBLabel("Chat model:"), modelCombo,    true)
            .addSeparator()
            .addComponent(completionBox)
            .addLabeledComponent(
                JBLabel("Completion model (empty = same as chat):"),
                completionModel, true
            )
            .addComponentFillVertically(JPanel(), 0)
            .panel
        reset()
        return panel!!
    }

    private fun rebuildModelCombo(provider: Provider, selectedModel: String) {
        modelCombo.removeAllItems()
        provider.models.forEach { modelCombo.addItem(it) }
        modelCombo.selectedItem = selectedModel
        // If not in list, set as editable text
        if (modelCombo.selectedIndex < 0) modelCombo.selectedItem = selectedModel
    }

    private fun currentModelText(): String =
        ((modelCombo.editor?.item as? String)?.trim() ?: (modelCombo.selectedItem as? String)?.trim() ?: "")

    private fun savedKeyFor(s: RikkiSettings.State, p: Provider): String = when (p) {
        Provider.DEEPSEEK  -> s.apiKeyDeepseek
        Provider.OPENAI    -> s.apiKeyOpenai
        Provider.ANTHROPIC -> s.apiKeyAnthropic
        Provider.GEMINI    -> s.apiKeyGemini
        Provider.MOONSHOT  -> s.apiKeyMoonshot
        Provider.OLLAMA    -> s.apiKeyOllama
        Provider.CUSTOM    -> s.apiKeyCustom
    }

    override fun isModified(): Boolean {
        val s = RikkiSettings.getInstance().state
        // Snapshot current field into a temp cache without mutating apiKeyCache
        val snap = apiKeyCache.toMutableMap()
        snap[currentProvider] = String(apiKeyField.password)

        if (s.provider != currentProvider.name) return true
        if (currentModelText() != s.modelName) return true
        if (baseUrlField.text.trim().trimEnd('/') != s.customBaseUrl) return true
        if (completionBox.isSelected != s.completionEnabled) return true
        if (completionModel.text.trim() != s.completionModelName) return true
        return Provider.values().any { p -> (snap[p] ?: "") != savedKeyFor(s, p) }
    }

    override fun apply() {
        apiKeyCache[currentProvider] = String(apiKeyField.password)
        val s = RikkiSettings.getInstance().state
        s.provider            = currentProvider.name
        s.modelName           = currentModelText()
        s.customBaseUrl       = baseUrlField.text.trim().trimEnd('/')
        s.completionEnabled   = completionBox.isSelected
        s.completionModelName = completionModel.text.trim()
        s.apiKeyDeepseek      = apiKeyCache[Provider.DEEPSEEK]  ?: ""
        s.apiKeyOpenai        = apiKeyCache[Provider.OPENAI]    ?: ""
        s.apiKeyAnthropic     = apiKeyCache[Provider.ANTHROPIC] ?: ""
        s.apiKeyGemini        = apiKeyCache[Provider.GEMINI]    ?: ""
        s.apiKeyMoonshot      = apiKeyCache[Provider.MOONSHOT]  ?: ""
        s.apiKeyOllama        = apiKeyCache[Provider.OLLAMA]    ?: ""
        s.apiKeyCustom        = apiKeyCache[Provider.CUSTOM]    ?: ""
    }

    override fun reset() {
        val s = RikkiSettings.getInstance().state
        currentProvider = Provider.fromName(s.provider)

        // Load all saved keys into cache
        apiKeyCache[Provider.DEEPSEEK]  = s.apiKeyDeepseek
        apiKeyCache[Provider.OPENAI]    = s.apiKeyOpenai
        apiKeyCache[Provider.ANTHROPIC] = s.apiKeyAnthropic
        apiKeyCache[Provider.GEMINI]    = s.apiKeyGemini
        apiKeyCache[Provider.MOONSHOT]  = s.apiKeyMoonshot
        apiKeyCache[Provider.OLLAMA]    = s.apiKeyOllama
        apiKeyCache[Provider.CUSTOM]    = s.apiKeyCustom

        completionBox.isSelected = s.completionEnabled
        completionModel.text     = s.completionModelName

        // Set URL
        val urlEditable = currentProvider == Provider.CUSTOM || currentProvider == Provider.OLLAMA
        baseUrlField.text = if (urlEditable) s.customBaseUrl else currentProvider.url
        baseUrlField.isEnabled  = urlEditable
        baseUrlField.isEditable = urlEditable

        // Rebuild model combo with saved model selected
        rebuildModelCombo(currentProvider, s.modelName)

        // Set provider combo last (listener checks p == currentProvider and returns early)
        providerCombo.selectedItem = currentProvider

        // Restore API key for current provider
        apiKeyField.text = apiKeyCache[currentProvider] ?: ""
    }
}
