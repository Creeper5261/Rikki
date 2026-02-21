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
import com.zzf.rikki.idea.llm.LiteLlmClient
import com.zzf.rikki.idea.settings.RikkiSettings
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Rikki inline TAB completion — standalone edition.
 * Calls the LLM API directly via [LiteLlmClient] (no backend server required).
 */
class RikkiInlineCompletionProvider : DebouncedInlineCompletionProvider() {

    override val id = InlineCompletionProviderID("com.zzf.rikki.idea.completion")

    override val delay: Duration get() = 350.milliseconds

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        val s = RikkiSettings.getInstance().state
        if (!s.completionEnabled || s.apiKey.isBlank()) return false
        return event is InlineCompletionEvent.DocumentChange ||
               event is InlineCompletionEvent.DirectCall
    }

    override suspend fun getSuggestionDebounced(
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

        return InlineCompletionSingleSuggestion.build { _ ->
            val buf = StringBuilder()
            LiteLlmClient.streamCompletion(
                prefix   = ctx.prefix,
                suffix   = ctx.suffix,
                language = ctx.language,
                onToken  = { token -> buf.append(token) }
            )
            val text = buf.toString().trimEnd()
            if (text.isNotEmpty()) {
                emit(InlineCompletionGrayTextElement(text))
            }
        }
    }

    private data class Context(val prefix: String, val suffix: String, val language: String)

    companion object {
        private const val PREFIX_LIMIT = 4_000
        private const val SUFFIX_LIMIT = 500
    }
}
