package com.zzf.rikki.idea.llm

import com.zzf.rikki.idea.settings.RikkiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Lightweight LLM client for inline code completion.
 *
 * Supports two modes determined by [RikkiSettings.State.completionUsesFim]:
 *  - **FIM** (Fill-In-Middle) — DeepSeek beta `/completions`, Ollama `/v1/completions`.
 *    Sends `prompt`/`suffix` fields; streaming delta field is `choices[0].text`.
 *  - **Chat** — OpenAI-compatible `/chat/completions` (OpenAI, Gemini, Moonshot, …).
 *    Sends a messages array; streaming delta field is `choices[0].delta.content`.
 */
object LiteLlmClient {

    private val CHAT_SYSTEM_PROMPT = """
        You are a code completion engine. Complete the code at <|CURSOR|>.
        Rules:
        - Output ONLY the raw completion text. Nothing else.
        - No explanation. No markdown. No backticks. No prose.
        - Stop at a natural completion point (end of statement, block, or function).
        - Match the indentation and style of the surrounding code.
    """.trimIndent()

    /**
     * Streams completion tokens into [onToken].
     * Automatically selects FIM or chat mode based on the saved completion provider.
     */
    suspend fun streamCompletion(
        prefix: String,
        suffix: String,
        language: String,
        onToken: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val s      = RikkiSettings.getInstance().state
        val apiKey = s.completionEffectiveApiKey()
        val prov   = s.completionEffectiveProvider()
        if (apiKey.isBlank() && prov != "OLLAMA") return@withContext

        val model   = s.completionEffectiveModel()
        val baseUrl = s.completionEffectiveBaseUrl().trimEnd('/')

        if (s.completionUsesFim()) {
            streamFim(baseUrl, apiKey, model, prefix, suffix, onToken)
        } else {
            streamChat(baseUrl, apiKey, model, prefix, suffix, language, onToken)
        }
    }

    // ── FIM mode (/completions, choices[0].text) ──────────────────────────────

    private suspend fun streamFim(
        baseUrl: String,
        apiKey: String,
        model: String,
        prefix: String,
        suffix: String,
        onToken: suspend (String) -> Unit
    ) {
        val conn = openConnection("$baseUrl/completions", apiKey) ?: return
        try {
            conn.outputStream.use { it.write(buildFimBody(model, prefix, suffix).toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    currentCoroutineContext().ensureActive()
                    if (!line!!.startsWith("data:")) continue
                    val data = line!!.removePrefix("data:").trim()
                    if (data == "[DONE]" || data.isEmpty()) continue
                    val token = extractFimText(data) ?: continue
                    if (token.isNotEmpty()) onToken(token)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun buildFimBody(model: String, prefix: String, suffix: String): String =
        """{"model":"${model.escapeJson()}","stream":true,"max_tokens":128,"temperature":0.0,""" +
        """"prompt":"${prefix.escapeJson()}","suffix":"${suffix.escapeJson()}"}"""

    /** Extracts text from a FIM streaming chunk: `choices[0].text`. */
    private fun extractFimText(json: String): String? {
        val key   = "\"text\":\""
        val start = json.indexOf(key).takeIf { it >= 0 }?.plus(key.length) ?: return null
        return extractEscapedString(json, start).ifEmpty { null }
    }

    // ── Chat mode (/chat/completions, choices[0].delta.content) ───────────────

    private suspend fun streamChat(
        baseUrl: String,
        apiKey: String,
        model: String,
        prefix: String,
        suffix: String,
        language: String,
        onToken: suspend (String) -> Unit
    ) {
        val conn = openConnection("$baseUrl/chat/completions", apiKey) ?: return
        try {
            conn.outputStream.use { it.write(buildChatBody(model, prefix, suffix, language).toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return
            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    currentCoroutineContext().ensureActive()
                    if (!line!!.startsWith("data:")) continue
                    val data = line!!.removePrefix("data:").trim()
                    if (data == "[DONE]" || data.isEmpty()) continue
                    val token = extractChatContent(data) ?: continue
                    if (token.isNotEmpty()) onToken(token)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun buildChatBody(model: String, prefix: String, suffix: String, language: String): String {
        val user = "Language: ${language.escapeJson()}\\n\\n${prefix.escapeJson()}<|CURSOR|>${suffix.escapeJson()}"
        return """{"model":"${model.escapeJson()}","stream":true,"max_tokens":128,"temperature":0.1,""" +
               """"messages":[{"role":"system","content":"${CHAT_SYSTEM_PROMPT.escapeJson()}"},""" +
               """{"role":"user","content":"$user"}]}"""
    }

    /** Extracts content from a chat streaming delta: `choices[0].delta.content`. */
    private fun extractChatContent(json: String): String? {
        if (json.contains("\"content\":null")) return null
        val key   = "\"content\":\""
        val start = json.indexOf(key).takeIf { it >= 0 }?.plus(key.length) ?: return null
        return extractEscapedString(json, start).ifEmpty { null }
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    private fun openConnection(url: String, apiKey: String): HttpURLConnection? = try {
        (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput       = true
            connectTimeout = 5_000
            readTimeout    = 30_000
        }
    } catch (_: Exception) { null }

    /** Reads a JSON-escaped string starting at [start], stopping at the closing `"`. */
    private fun extractEscapedString(json: String, start: Int): String {
        val sb = StringBuilder()
        var i  = start
        while (i < json.length) {
            val c = json[i]
            if (c == '\\' && i + 1 < json.length) {
                when (json[i + 1]) {
                    'n'  -> { sb.append('\n'); i += 2 }
                    'r'  -> { sb.append('\r'); i += 2 }
                    't'  -> { sb.append('\t'); i += 2 }
                    '"'  -> { sb.append('"');  i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c);    i++ }
                }
            } else if (c == '"') {
                break
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun String.escapeJson() = replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
