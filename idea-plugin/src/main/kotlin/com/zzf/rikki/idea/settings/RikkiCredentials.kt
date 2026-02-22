package com.zzf.rikki.idea.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import java.util.concurrent.ConcurrentHashMap

/**
 * Stores and retrieves Rikki API keys via IntelliJ PasswordSafe (OS keychain / master-password
 * store). Keys are never written to disk in plaintext.
 *
 * Usage:
 *  - Call [loadAll] once on a background thread at plugin startup to prime the in-memory cache.
 *  - Call [get] from any thread to read a key.
 *  - Call [set] from any thread to write a key (persists immediately to PasswordSafe and updates
 *    the in-memory cache).
 */
internal object RikkiCredentials {

    private const val SERVICE = "RikkiCodeAgent"

    /** All provider keys that are stored independently. */
    private val PROVIDERS = listOf(
        "DEEPSEEK", "OPENAI", "ANTHROPIC", "GEMINI", "MOONSHOT", "OLLAMA", "CUSTOM",
        "COMPLETION_OVERRIDE"
    )

    private val cache  = ConcurrentHashMap<String, String>()
    @Volatile private var loaded = false

    /** Returns the cached API key for [key], or "" if not yet loaded / not set. */
    fun get(key: String): String = cache[key.uppercase()] ?: ""

    /**
     * Persists [value] to PasswordSafe and updates the in-memory cache immediately.
     * A blank value removes the credential from the store.
     */
    fun set(key: String, value: String) {
        val k = key.uppercase()
        PasswordSafe.instance.setPassword(attrs(k), value.ifBlank { null })
        cache[k] = value
    }

    /**
     * Loads all keys from PasswordSafe into the in-memory cache.
     * Must be called from a **background** thread (not EDT) to avoid blocking the UI.
     */
    fun loadAll() {
        PROVIDERS.forEach { k ->
            cache[k] = PasswordSafe.instance.getPassword(attrs(k)) ?: ""
        }
        loaded = true
    }

    /** True once [loadAll] has completed at least once. */
    fun isLoaded(): Boolean = loaded

    private fun attrs(key: String) =
        CredentialAttributes(generateServiceName(SERVICE, key))
}
