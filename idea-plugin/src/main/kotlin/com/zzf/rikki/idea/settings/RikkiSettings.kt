package com.zzf.rikki.idea.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "RikkiSettings",
    storages = [Storage("rikki.xml")]
)
class RikkiSettings : PersistentStateComponent<RikkiSettings.State> {

    data class State(
        var provider: String = "DEEPSEEK",
        var modelName: String = "deepseek-chat",
        /** Used only when provider is OLLAMA or CUSTOM. */
        var customBaseUrl: String = "",
        var completionEnabled: Boolean = true,
        var completionModelName: String = "",
        // Per-provider API keys
        var apiKeyDeepseek: String  = "",
        var apiKeyOpenai: String    = "",
        var apiKeyAnthropic: String = "",
        var apiKeyGemini: String    = "",
        var apiKeyMoonshot: String  = "",
        var apiKeyOllama: String    = "",
        var apiKeyCustom: String    = ""
    ) {
        fun currentApiKey(): String = when (provider) {
            "DEEPSEEK"  -> apiKeyDeepseek
            "OPENAI"    -> apiKeyOpenai
            "ANTHROPIC" -> apiKeyAnthropic
            "GEMINI"    -> apiKeyGemini
            "MOONSHOT"  -> apiKeyMoonshot
            "OLLAMA"    -> apiKeyOllama
            else        -> apiKeyCustom
        }

        fun currentBaseUrl(): String = when (provider) {
            "DEEPSEEK"  -> "https://api.deepseek.com/v1"
            "OPENAI"    -> "https://api.openai.com/v1"
            "ANTHROPIC" -> "https://api.anthropic.com/v1"
            "GEMINI"    -> "https://generativelanguage.googleapis.com/v1beta/openai"
            "MOONSHOT"  -> "https://api.moonshot.cn/v1"
            "OLLAMA"    -> customBaseUrl.ifBlank { "http://localhost:11434/v1" }
            else        -> customBaseUrl
        }
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): RikkiSettings =
            ApplicationManager.getApplication().getService(RikkiSettings::class.java)
    }
}
