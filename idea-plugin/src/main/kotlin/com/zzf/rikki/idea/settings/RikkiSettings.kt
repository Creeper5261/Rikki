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
        /** Custom base URL for completion; only relevant for OLLAMA / CUSTOM. */
        var completionCustomBaseUrl: String = ""

        // API keys are NOT stored here — they live in PasswordSafe via RikkiCredentials.
    ) {
        // ── Chat helpers ───────────────────────────────────────────────────────

        fun currentApiKey(): String = RikkiCredentials.get(provider)

        fun currentBaseUrl(): String = chatBaseUrlFor(provider, customBaseUrl)

        // ── Completion helpers ─────────────────────────────────────────────────

        fun completionEffectiveProvider(): String = completionProvider.ifBlank { provider }

        /**
         * Effective API key for completion.
         * If the user stored a per-completion override key, use it;
         * otherwise fall back to the chat provider's key.
         */
        fun completionEffectiveApiKey(): String {
            val override = RikkiCredentials.get("COMPLETION_OVERRIDE")
            return override.ifBlank { RikkiCredentials.get(completionEffectiveProvider()) }
        }

        fun completionEffectiveModel(): String = completionModelName.ifBlank { modelName }

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

        fun completionUsesFim(): Boolean =
            completionEffectiveProvider() in listOf("DEEPSEEK", "OLLAMA")

        // ── Private helpers ────────────────────────────────────────────────────

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
        // Prime the in-memory credential cache on a background thread so API keys
        // are available before the user opens Settings for the first time.
        ApplicationManager.getApplication().executeOnPooledThread {
            RikkiCredentials.loadAll()
        }
    }

    companion object {
        fun getInstance(): RikkiSettings =
            ApplicationManager.getApplication().getService(RikkiSettings::class.java)
    }
}
