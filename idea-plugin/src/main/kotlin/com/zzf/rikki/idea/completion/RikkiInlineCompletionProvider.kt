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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
        if (!s.completionEnabled) return false
        val key  = s.completionEffectiveApiKey()
        val prov = s.completionEffectiveProvider()
        if (key.isBlank() && prov != "OLLAMA") return false
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
            val doc   = request.document
            // Read the live caret position (after the debounce has elapsed) rather than
            // request.startOffset. For DocumentChange events, startOffset is captured at
            // event time when the caret may not yet have advanced past the just-inserted
            // character, causing that character to be excluded from the prefix and then
            // echoed back at the start of the LLM's completion.
            val offset = request.editor.caretModel.primaryCaret.offset
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
            // A Channel bridges the IO dispatcher (where tokens stream in) and the flow's
            // coroutine context (where emit must be called). Tokens are emitted progressively
            // so that ghost text appears as soon as the first token arrives rather than after
            // the entire completion finishes streaming.
            val channel = Channel<String>(Channel.UNLIMITED)
            try {
                coroutineScope {
                    launch(Dispatchers.IO) {
                        try {
                            LiteLlmClient.streamCompletion(
                                prefix   = ctx.prefix,
                                suffix   = ctx.suffix,
                                language = ctx.language,
                                onToken  = { token -> if (token.isNotEmpty()) channel.send(token) }
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e  // never swallow
                            // network / parse errors: close the channel with what we have
                        } finally {
                            channel.close()
                        }
                    }
                    // Emit in the flow's coroutine context — no context-invariant violation
                    for (token in channel) {
                        currentCoroutineContext().ensureActive()
                        emit(InlineCompletionGrayTextElement(token))
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) { /* ignore */ }
        }
    }

    private data class Context(val prefix: String, val suffix: String, val language: String)

    companion object {
        private const val PREFIX_LIMIT = 2_000   // reduced: less context = faster LLM response
        private const val SUFFIX_LIMIT = 400
    }
}
