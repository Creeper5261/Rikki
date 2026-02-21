package com.zzf.rikki.idea.completion

import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.TextRange
import com.zzf.rikki.idea.llm.LiteLlmClient
import com.zzf.rikki.idea.settings.RikkiSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Rikki inline TAB completion — standalone edition.
 * Calls the LLM API directly via [LiteLlmClient] (no backend server required).
 */
class RikkiInlineCompletionProvider : DebouncedInlineCompletionProvider() {

    private val LOG = Logger.getInstance(RikkiInlineCompletionProvider::class.java)

    override val id = InlineCompletionProviderID("com.zzf.rikki.idea.completion")

    // IntelliJ 2024.1 debounce via `delay` property
    override val delay: Duration get() = 350.milliseconds

    // IntelliJ 2025.1+ uses getDebounceDelay(): Long (no 'override' — compiles against
    // 2024.1 SDK but overrides via JVM dispatch at runtime on 2025.1+)
    @Suppress("unused")
    fun getDebounceDelay(): Long = 350L

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val s = RikkiSettings.getInstance().state
        if (!s.completionEnabled || s.apiKey.isBlank()) return false
        return event is InlineCompletionEvent.DocumentChange ||
               event is InlineCompletionEvent.DirectCall
    }

    /**
     * Called by IntelliJ 2025.1+ directly (replaces getSuggestionDebounced in their refactored API).
     * Also called on 2024.1 if the framework ever dispatches getSuggestion() first.
     * We add our own 350ms debounce; the coroutine is cancelled by the framework on the next
     * keystroke, so early-exit is safe.
     */
    override suspend fun getSuggestion(
        request: InlineCompletionRequest
    ): InlineCompletionSuggestion {
        LOG.info("Rikki getSuggestion called")
        // Manual debounce: yields to cancellation if user keeps typing
        delay(350)
        currentCoroutineContext().ensureActive()
        return doGetSuggestion(request)
    }

    /**
     * Called by IntelliJ 2024.1 after the framework's own debounce.
     * On 2025.1 this is never called (getSuggestion is called directly).
     */
    override suspend fun getSuggestionDebounced(
        request: InlineCompletionRequest
    ): InlineCompletionSuggestion {
        LOG.info("Rikki getSuggestionDebounced called")
        return doGetSuggestion(request)
    }

    private suspend fun doGetSuggestion(
        request: InlineCompletionRequest
    ): InlineCompletionSuggestion {
        val ctx = readAction {
            val doc    = request.document
            val offset = request.startOffset
            val total  = doc.textLength
            Context(
                prefix   = doc.getText(TextRange(maxOf(0, offset - PREFIX_LIMIT), offset)),
                suffix   = doc.getText(TextRange(offset, minOf(total, offset + SUFFIX_LIMIT))),
                language = request.file.language.displayName,
            )
        }

        if (ctx.prefix.substringAfterLast('\n').isBlank()) {
            return InlineCompletionSuggestion.Empty
        }

        currentCoroutineContext().ensureActive()
        LOG.info("Rikki calling LLM, language=${ctx.language}")

        return InlineCompletionSingleSuggestion.build { _ ->
            val buf = StringBuilder()
            try {
                LiteLlmClient.streamCompletion(
                    prefix   = ctx.prefix,
                    suffix   = ctx.suffix,
                    language = ctx.language,
                    onToken  = { token -> buf.append(token) }
                )
            } catch (ce: CancellationException) {
                throw ce  // MUST rethrow — never swallow CancellationException in coroutines
            } catch (_: Exception) {
                // ignore network/IO errors; use whatever tokens arrived before the error
            }
            val text = buf.toString().trimEnd()
            LOG.info("Rikki completion result length=${text.length}")
            if (text.isNotEmpty()) {
                emit(InlineCompletionGrayTextElement(text))
            }
        }
    }

    private data class Context(val prefix: String, val suffix: String, val language: String)

    companion object {
        private const val PREFIX_LIMIT = 2_000   // reduced: less context = faster LLM response
        private const val SUFFIX_LIMIT = 400
    }
}
