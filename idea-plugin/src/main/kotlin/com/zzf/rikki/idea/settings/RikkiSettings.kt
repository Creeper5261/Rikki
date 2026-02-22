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
        // ── Chat config ────────────────────────────────────────────────────────
        var provider: String = "DEEPSEEK",
        var modelName: String = "deepseek-chat",
        /** Used only when provider is OLLAMA or CUSTOM. */
        var customBaseUrl: String = "",

        // ── Completion config ──────────────────────────────────────────────────
        var completionEnabled: Boolean = true,
        /** Empty = same as chat provider. */
        var completionProvider: String = "",
        /** Empty = same as chat model. */
        var completionModelName: String = "",
        /** Empty = use chat provider's API key. */
        var completionApiKeyOverride: String = "",
        /** Custom base URL for completion; only relevant for OLLAMA / CUSTOM. */
        var completionCustomBaseUrl: String = "",

        // ── Per-provider API keys (chat) ───────────────────────────────────────
        var apiKeyDeepseek: String  = "",
        var apiKeyOpenai: String    = "",
        var apiKeyAnthropic: String = "",
        var apiKeyGemini: String    = "",
        var apiKeyMoonshot: String  = "",
        var apiKeyOllama: String    = "",
        var apiKeyCustom: String    = ""
    ) {
        // ── Chat helpers ───────────────────────────────────────────────────────

        fun currentApiKey(): String = apiKeyFor(provider)

        fun currentBaseUrl(): String = chatBaseUrlFor(provider, customBaseUrl)

        // ── Completion helpers ─────────────────────────────────────────────────

        /** Effective provider for completion (falls back to chat provider when empty). */
        fun completionEffectiveProvider(): String = completionProvider.ifBlank { provider }

        /** Effective API key for completion. Uses override if set, otherwise the
         *  chat key that belongs to the effective completion provider. */
        fun completionEffectiveApiKey(): String =
            completionApiKeyOverride.ifBlank { apiKeyFor(completionEffectiveProvider()) }

        /** Effective model for completion (falls back to chat model when empty). */
        fun completionEffectiveModel(): String = completionModelName.ifBlank { modelName }

        /**
         * Base URL for the completion endpoint.
         * DeepSeek FIM requires the `/beta` base URL (distinct from the chat `/v1`).
         */
        fun completionEffectiveBaseUrl(): String {
            val p = completionEffectiveProvider()
            val custom = completionCustomBaseUrl.ifBlank {
                if (p == provider) customBaseUrl else ""
            }
            return when (p) {
                "DEEPSEEK"  -> "https://api.deepseek.com/beta"
                "OPENAI"    -> "https://api.openai.com/v1"
                "ANTHROPIC" -> "https://api.anthropic.com/v1"
                "GEMINI"    -> "https://generativelanguage.googleapis.com/v1beta/openai"
                "MOONSHOT"  -> "https://api.moonshot.cn/v1"
                "OLLAMA"    -> custom.ifBlank { "http://localhost:11434/v1" }
                else        -> custom
            }
        }

        /**
         * True when the effective completion provider uses the legacy FIM
         * `/completions` endpoint (DeepSeek beta, Ollama).
         * False → use `/chat/completions` (OpenAI, Gemini, Moonshot, …).
         */
        fun completionUsesFim(): Boolean =
            completionEffectiveProvider() in listOf("DEEPSEEK", "OLLAMA")

        // ── Private helpers ────────────────────────────────────────────────────

        private fun apiKeyFor(prov: String): String = when (prov) {
            "DEEPSEEK"  -> apiKeyDeepseek
            "OPENAI"    -> apiKeyOpenai
            "ANTHROPIC" -> apiKeyAnthropic
            "GEMINI"    -> apiKeyGemini
            "MOONSHOT"  -> apiKeyMoonshot
            "OLLAMA"    -> apiKeyOllama
            else        -> apiKeyCustom
        }

        private fun chatBaseUrlFor(prov: String, customUrl: String): String = when (prov) {
            "DEEPSEEK"  -> "https://api.deepseek.com/v1"
            "OPENAI"    -> "https://api.openai.com/v1"
            "ANTHROPIC" -> "https://api.anthropic.com/v1"
            "GEMINI"    -> "https://generativelanguage.googleapis.com/v1beta/openai"
            "MOONSHOT"  -> "https://api.moonshot.cn/v1"
            "OLLAMA"    -> customUrl.ifBlank { "http://localhost:11434/v1" }
            else        -> customUrl
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
