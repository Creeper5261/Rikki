package com.zzf.rikki.idea.settings

import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

class RikkiSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val apiKeyField       = JBPasswordField()
    private val baseUrlField      = JBTextField()
    private val modelField        = JBTextField()
    private val completionBox     = JCheckBox("Enable inline TAB completion")
    private val completionModel   = JBTextField()

    override fun getDisplayName() = "Rikki Code Agent"

    override fun createComponent(): JComponent {
        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("API Key:"), apiKeyField, true)
            .addLabeledComponent(JBLabel("Base URL:"), baseUrlField, true)
            .addLabeledComponent(JBLabel("Chat model:"), modelField, true)
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
            || baseUrlField.text    != s.baseUrl
            || modelField.text      != s.modelName
            || completionBox.isSelected != s.completionEnabled
            || completionModel.text != s.completionModelName
    }

    override fun apply() {
        val s = RikkiSettings.getInstance().state
        s.apiKey              = String(apiKeyField.password)
        s.baseUrl             = baseUrlField.text.trimEnd('/')
        s.modelName           = modelField.text.trim()
        s.completionEnabled   = completionBox.isSelected
        s.completionModelName = completionModel.text.trim()
    }

    override fun reset() {
        val s = RikkiSettings.getInstance().state
        apiKeyField.text      = s.apiKey
        baseUrlField.text     = s.baseUrl
        modelField.text       = s.modelName
        completionBox.isSelected = s.completionEnabled
        completionModel.text  = s.completionModelName
    }
}
