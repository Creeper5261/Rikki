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
 * Lightweight LLM client for the standalone (lite) plugin.
 * Calls any OpenAI-compatible /chat/completions endpoint directly,
 * reading API key + base URL from [RikkiSettings].
 */
object LiteLlmClient {

    private val SYSTEM_PROMPT = """
        You are a code completion engine. Complete the code at <|CURSOR|>.
        Rules:
        - Output ONLY the raw completion text. Nothing else.
        - No explanation. No markdown. No backticks. No prose.
        - Stop at a natural completion point (end of statement, block, or function).
        - Match the indentation and style of the surrounding code.
    """.trimIndent()

    /**
     * Streams FIM completion tokens. Calls [onToken] for each decoded token.
     * Suspends until the stream ends or the coroutine is cancelled.
     */
    suspend fun streamCompletion(
        prefix: String,
        suffix: String,
        language: String,
        onToken: suspend (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val settings = RikkiSettings.getInstance().state
        if (settings.apiKey.isBlank()) return@withContext

        val model   = settings.completionModelName.ifBlank { settings.modelName }
        val baseUrl = settings.baseUrl.trimEnd('/')
        val body    = buildBody(model, prefix, suffix, language)

        val conn = openConnection("$baseUrl/chat/completions", settings.apiKey) ?: return@withContext
        try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return@withContext

            BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    currentCoroutineContext().ensureActive()
                    if (!line!!.startsWith("data:")) continue
                    val data = line!!.removePrefix("data:").trim()
                    if (data == "[DONE]" || data.isEmpty()) continue
                    val token = extractContent(data) ?: continue
                    if (token.isNotEmpty()) onToken(token)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnection(url: String, apiKey: String): HttpURLConnection? = try {
        (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            doOutput      = true
            connectTimeout = 5_000
            readTimeout    = 30_000
        }
    } catch (_: Exception) { null }

    private fun buildBody(model: String, prefix: String, suffix: String, language: String): String {
        val user = "Language: ${language.escapeJson()}\n\n${prefix.escapeJson()}<|CURSOR|>${suffix.escapeJson()}"
        return """{"model":"${model.escapeJson()}","stream":true,"max_tokens":256,"temperature":0.1,""" +
               """"messages":[{"role":"system","content":"${SYSTEM_PROMPT.escapeJson()}"},""" +
               """{"role":"user","content":"$user"}]}"""
    }

    /**
     * Extracts and unescapes the "content" string from a streaming delta JSON chunk.
     * Handles escape sequences: \n \r \t \" \\
     */
    private fun extractContent(json: String): String? {
        if (json.contains("\"content\":null")) return null
        val key   = "\"content\":\""
        val start = json.indexOf(key).takeIf { it >= 0 }?.plus(key.length) ?: return null
        val sb    = StringBuilder()
        var i     = start
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
        return sb.toString().ifEmpty { null }
    }

    private fun String.escapeJson() = replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
