package com.zzf.rikki.idea.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.*

class RikkiSettingsConfigurable : Configurable {

    private enum class Provider(val label: String, val url: String, val defaultModel: String) {
        DEEPSEEK ("DeepSeek",               "https://api.deepseek.com/v1",                              "deepseek-chat"),
        OPENAI   ("OpenAI (GPT)",           "https://api.openai.com/v1",                               "gpt-4o"),
        ANTHROPIC("Anthropic (Claude)",     "https://api.anthropic.com/v1",                            "claude-opus-4-6"),
        GEMINI   ("Google (Gemini)",        "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash"),
        MOONSHOT ("Moonshot (Kimi)",        "https://api.moonshot.cn/v1",                              "moonshot-v1-8k"),
        OLLAMA   ("Ollama (local, no key)", "http://localhost:11434/v1",                               "qwen2.5-coder:7b"),
        CUSTOM   ("Custom",                 "",                                                         "");

        companion object {
            fun fromUrl(url: String): Provider {
                val trimmed = url.trimEnd('/')
                return values().firstOrNull { it != CUSTOM && it.url.trimEnd('/') == trimmed } ?: CUSTOM
            }
        }
    }

    private var panel: JPanel? = null
    private val apiKeyField     = JBPasswordField()
    private val providerCombo   = JComboBox<Provider>()
    private val baseUrlField    = JBTextField()
    private val modelField      = JBTextField()
    private val completionBox   = JCheckBox("Enable inline TAB completion")
    private val completionModel = JBTextField()

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

        providerCombo.addActionListener {
            val p = providerCombo.selectedItem as? Provider ?: return@addActionListener
            if (p != Provider.CUSTOM) {
                baseUrlField.text = p.url
                // Only auto-fill model when it's blank or matches another preset's default
                if (modelField.text.isBlank() || Provider.values().any { it.defaultModel == modelField.text }) {
                    modelField.text = p.defaultModel
                }
            }
            val urlEditable = p == Provider.CUSTOM || p == Provider.OLLAMA
            baseUrlField.isEnabled  = urlEditable
            baseUrlField.isEditable = urlEditable
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API Key:"),    apiKeyField,   true)
            .addLabeledComponent(JBLabel("Provider:"),   providerCombo, true)
            .addLabeledComponent(JBLabel("Base URL:"),   baseUrlField,  true)
            .addLabeledComponent(JBLabel("Chat model:"), modelField,    true)
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

    override fun isModified(): Boolean {
        val s = RikkiSettings.getInstance().state
        return String(apiKeyField.password) != s.apiKey
            || baseUrlField.text        != s.baseUrl
            || modelField.text          != s.modelName
            || completionBox.isSelected != s.completionEnabled
            || completionModel.text     != s.completionModelName
    }

    override fun apply() {
        val s = RikkiSettings.getInstance().state
        s.apiKey              = String(apiKeyField.password)
        s.baseUrl             = baseUrlField.text.trim().trimEnd('/')
        s.modelName           = modelField.text.trim()
        s.completionEnabled   = completionBox.isSelected
        s.completionModelName = completionModel.text.trim()
    }

    override fun reset() {
        val s = RikkiSettings.getInstance().state
        apiKeyField.text         = s.apiKey
        modelField.text          = s.modelName
        completionBox.isSelected = s.completionEnabled
        completionModel.text     = s.completionModelName

        val provider = Provider.fromUrl(s.baseUrl)
        // Set provider first (listener fires → fills baseUrlField and maybe modelField)
        providerCombo.selectedItem = provider
        // Then override with exact saved values so custom edits are preserved
        baseUrlField.text = s.baseUrl
        modelField.text   = s.modelName
        val urlEditable = provider == Provider.CUSTOM || provider == Provider.OLLAMA
        baseUrlField.isEnabled  = urlEditable
        baseUrlField.isEditable = urlEditable
    }
}
