package com.zzf.rikki.idea.completion;

import com.intellij.codeInsight.inline.completion.InlineCompletionEvent;
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest;
import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider;
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.zzf.rikki.idea.llm.LiteLlmClient;
import com.zzf.rikki.idea.settings.RikkiSettings;
import kotlin.coroutines.Continuation;

import java.util.concurrent.CancellationException;

public abstract class RikkiInlineCompletionProvider extends DebouncedInlineCompletionProvider {
    private static final Logger LOG = Logger.getInstance(RikkiInlineCompletionProvider.class);
    static final long DEBOUNCE_MILLIS = 350L;
    static final int PREFIX_LIMIT = 2_000;
    static final int SUFFIX_LIMIT = 400;

    @Override
    public final Object getSuggestion(InlineCompletionRequest request, Continuation<? super InlineCompletionSuggestion> continuation) {
        LOG.info("Rikki getSuggestion called");
        InlineCompletionBridge.sleepWithCancellation(continuation, DEBOUNCE_MILLIS);
        return computeSuggestion(request, continuation);
    }

    @Override
    public final Object getSuggestionDebounced(InlineCompletionRequest request, Continuation<? super InlineCompletionSuggestion> continuation) {
        LOG.info("Rikki getSuggestionDebounced called");
        return computeSuggestion(request, continuation);
    }

    @Override
    public final boolean isEnabled(InlineCompletionEvent event) {
        RikkiSettings.State state = RikkiSettings.getInstance().getState();
        CompletionConfigResolver.CompletionConfig config = CompletionConfigResolver.resolve(state);
        return isCompletionEnabled(config)
                && (event instanceof InlineCompletionEvent.DocumentChange || event instanceof InlineCompletionEvent.DirectCall);
    }

    public final String getId_S2YkoFA() {
        return "com.zzf.rikki.idea.completion";
    }

    private Object computeSuggestion(InlineCompletionRequest request, Continuation<? super InlineCompletionSuggestion> continuation) {
        Context context = readContext(request);
        if (shouldSkipForPrefix(context.prefix())) {
            return InlineCompletionBridge.emptySuggestion();
        }
        InlineCompletionBridge.ensureActive(continuation);
        StringBuilder buffer = new StringBuilder();
        try {
            LiteLlmClient.streamCompletion(context.prefix(), context.suffix(), context.language(), buffer::append);
        } catch (CancellationException e) {
            throw e;
        } catch (Exception ignored) {
        }
        String text = trimTrailingWhitespace(buffer.toString());
        LOG.info("Rikki completion result length=" + text.length());
        if (text.isEmpty()) {
            return InlineCompletionBridge.emptySuggestion();
        }
        return InlineCompletionBridge.singleSuggestion(text);
    }

    static Context captureContext(String documentText, int offset, String language) {
        int safeOffset = Math.max(0, Math.min(offset, documentText.length()));
        String prefix = documentText.substring(Math.max(0, safeOffset - PREFIX_LIMIT), safeOffset);
        String suffix = documentText.substring(safeOffset, Math.min(documentText.length(), safeOffset + SUFFIX_LIMIT));
        return new Context(prefix, suffix, language);
    }

    static boolean isCompletionEnabled(CompletionConfigResolver.CompletionConfig config) {
        if (config == null || !config.enabled()) {
            return false;
        }
        return !config.requiresApiKey() || (config.apiKey() != null && !config.apiKey().isBlank());
    }

    static boolean shouldSkipForPrefix(String prefix) {
        String value = prefix == null ? "" : prefix;
        return value.substring(value.lastIndexOf('\n') + 1).isBlank();
    }

    private static Context readContext(InlineCompletionRequest request) {
        return ReadAction.compute(() -> {
            int offset = request.getEditor().getCaretModel().getPrimaryCaret().getOffset();
            int total = request.getDocument().getTextLength();
            String prefix = request.getDocument().getText(new TextRange(Math.max(0, offset - PREFIX_LIMIT), offset));
            String suffix = request.getDocument().getText(new TextRange(offset, Math.min(total, offset + SUFFIX_LIMIT)));
            return new Context(prefix, suffix, request.getFile().getLanguage().getDisplayName());
        });
    }

    private static String trimTrailingWhitespace(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return end == value.length() ? value : value.substring(0, end);
    }

    record Context(String prefix, String suffix, String language) {
    }
}
