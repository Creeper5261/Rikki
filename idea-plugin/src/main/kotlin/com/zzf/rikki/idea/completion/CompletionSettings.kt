package com.zzf.rikki.idea.completion

/**
 * Global settings for inline completion.
 * Values can be overridden via JVM system properties for easy testing:
 *   -Dcodeagent.completion.enabled=true
 *   -Dcodeagent.completion.endpoint=http://localhost:18080/api/agent/complete
 */
object CompletionSettings {

    /** Master on/off switch. */
    var isEnabled: Boolean
        get() = _enabled
        set(value) { _enabled = value }

    /** Backend FIM endpoint URL. */
    var endpoint: String
        get() = _endpoint
        set(value) { _endpoint = value }

    // ── Backing fields with system-property override support ──────────────────

    private var _enabled: Boolean =
        System.getProperty("rikki.completion.enabled", "true").toBoolean()

    private var _endpoint: String =
        System.getProperty(
            "rikki.completion.endpoint",
            resolveDefaultEndpoint()
        )

    private fun resolveDefaultEndpoint(): String {
        val base = System.getProperty("rikki.endpoint", "http://localhost:18080/api/agent/chat")
        // Derive /complete endpoint from the existing chat endpoint
        return when {
            base.endsWith("/chat/stream") ->
                base.removeSuffix("/stream").removeSuffix("/chat") + "/complete"
            base.endsWith("/chat") ->
                base.removeSuffix("/chat") + "/complete"
            else -> "$base/complete"
        }
    }
}
