package com.zzf.rikki.idea.completion

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.TextRange
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Rikki AI inline code completion (ghost text / TAB completion).
 *
 * Trigger: user pauses typing for [delay] ms.
 * Accept:  TAB (full), Ctrl+Right (word), Escape (dismiss).
 *
 * Design:
 *  - Buffers ALL streaming tokens before emitting, so ghost text appears all at once.
 *  - Backend sends JSON-encoded tokens to preserve \n/\t through SSE transport.
 *  - Multi-line completions with correct indentation are rendered as block ghost text.
 */
class RikkiInlineCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID("com.zzf.rikki.completion")

    override val delay: Duration get() = 350.milliseconds

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!CompletionSettings.isEnabled) return false
        return event is InlineCompletionEvent.DocumentChange ||
               event is InlineCompletionEvent.DirectCall
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val ctx = readAction {
            val doc    = request.document
            val offset = request.startOffset
            val total  = doc.textLength
            val prefixStart = maxOf(0, offset - PREFIX_LIMIT)
            val suffixEnd   = minOf(total, offset + SUFFIX_LIMIT)
            Context(
                prefix   = doc.getText(TextRange(prefixStart, offset)),
                suffix   = doc.getText(TextRange(offset, suffixEnd)),
                language = request.file.language.displayName,
                filePath = request.file.virtualFile?.path ?: ""
            )
        }

        if (shouldSkip(ctx.prefix)) return InlineCompletionSuggestion.Empty

        return InlineCompletionSingleSuggestion.build { _ ->
            val conn = openConnection(ctx) ?: return@build
            val buf = StringBuilder()
            try {
                conn.connect()
                if (conn.responseCode !in 200..299) return@build

                val reader = BufferedReader(
                    InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)
                )
                reader.use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        currentCoroutineContext().ensureActive()
                        val token = parseSseLine(line!!) ?: continue
                        if (token == "[DONE]") break
                        if (token.isNotEmpty()) buf.append(token)
                    }
                }
            } finally {
                conn.disconnect()
            }

            // Emit the full completion at once — no token-by-token streaming to UI.
            // trimEnd removes any stray trailing whitespace the LLM might append.
            val text = buf.toString().trimEnd()
            if (text.isNotEmpty()) {
                emit(InlineCompletionGrayTextElement(text))
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun shouldSkip(prefix: String): Boolean {
        val lastLine = prefix.substringAfterLast('\n')
        return lastLine.isBlank()
    }

    private suspend fun openConnection(ctx: Context): HttpURLConnection? =
        withContext(Dispatchers.IO) {
            try {
                val url = URI.create(CompletionSettings.endpoint).toURL()
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                conn.setRequestProperty("Accept", "text/event-stream")
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.readTimeout    = 30_000
                val bytes = buildRequestBody(ctx).toByteArray(StandardCharsets.UTF_8)
                val out: OutputStream = conn.outputStream
                out.write(bytes)
                out.flush()
                conn
            } catch (_: Exception) {
                null
            }
        }

    private fun buildRequestBody(ctx: Context): String {
        val prefix = ctx.prefix.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        val suffix = ctx.suffix.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
        val lang   = ctx.language.replace("\"", "\\\"")
        val path   = ctx.filePath.replace("\\", "\\\\").replace("\"", "\\\"")
        return """{"prefix":"$prefix","suffix":"$suffix","language":"$lang","filePath":"$path","stream":true}"""
    }

    /**
     * Parses one SSE line. Backend sends JSON-encoded strings, e.g.:
     *   data: "hello\nworld"   →  "hello\nworld" (with actual newline)
     *   data: [DONE]           →  "[DONE]"
     * Returns null for comment/empty/non-data lines.
     */
    private fun parseSseLine(line: String): String? {
        if (!line.startsWith("data:")) return null
        val raw = line.removePrefix("data:").trimStart()
        if (raw.isEmpty()) return null
        if (raw == "[DONE]") return "[DONE]"
        return unescapeJsonString(raw)
    }

    /**
     * Decodes a JSON string literal produced by Jackson's writeValueAsString().
     * Handles: \n \r \t \" \\  and passes through unknown escapes unchanged.
     */
    private fun unescapeJsonString(s: String): String {
        if (s.length < 2 || s[0] != '"' || s[s.length - 1] != '"') return s
        val inner = s.substring(1, s.length - 1)
        val sb = StringBuilder(inner.length)
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            if (c == '\\' && i + 1 < inner.length) {
                when (inner[i + 1]) {
                    'n'  -> { sb.append('\n'); i += 2 }
                    'r'  -> { sb.append('\r'); i += 2 }
                    't'  -> { sb.append('\t'); i += 2 }
                    '"'  -> { sb.append('"');  i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c);    i++ }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private data class Context(
        val prefix: String,
        val suffix: String,
        val language: String,
        val filePath: String
    )

    companion object {
        private const val PREFIX_LIMIT = 4_000
        private const val SUFFIX_LIMIT = 500
    }
}
