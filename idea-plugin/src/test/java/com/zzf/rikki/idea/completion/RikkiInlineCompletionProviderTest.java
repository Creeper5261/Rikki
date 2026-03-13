package com.zzf.rikki.idea.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RikkiInlineCompletionProviderTest {

    @Test
    void captureContext_shouldClampOffsetAndRespectPrefixSuffixLimits() {
        String document = "x".repeat(2100) + "middle" + "y".repeat(500);
        RikkiInlineCompletionProvider.Context context = RikkiInlineCompletionProvider.captureContext(document, 2100, "Java");

        assertEquals(2000, context.prefix().length());
        assertEquals(400, context.suffix().length());
        assertEquals("Java", context.language());
        assertTrue(context.prefix().chars().allMatch(ch -> ch == 'x'));
        assertTrue(context.suffix().startsWith("middle"));
    }

    @Test
    void completionHelpers_shouldReflectDebounceAndBlankLineRules() {
        assertEquals(350L, RikkiInlineCompletionProvider.DEBOUNCE_MILLIS);
        assertTrue(RikkiInlineCompletionProvider.shouldSkipForPrefix("line1\n    "));
        assertFalse(RikkiInlineCompletionProvider.shouldSkipForPrefix("line1\n    foo"));
    }

    @Test
    void isCompletionEnabled_shouldRequireApiKeyUnlessProviderIsLocal() {
        CompletionConfigResolver.CompletionConfig openAi = new CompletionConfigResolver.CompletionConfig(
                true, "OPENAI", "gpt-4.1-nano", "https://api.openai.com/v1", "", false
        );
        CompletionConfigResolver.CompletionConfig ollama = new CompletionConfigResolver.CompletionConfig(
                true, "OLLAMA", "qwen2.5-coder:7b", "http://localhost:11434/v1", "", true
        );

        assertFalse(RikkiInlineCompletionProvider.isCompletionEnabled(openAi));
        assertTrue(RikkiInlineCompletionProvider.isCompletionEnabled(ollama));
    }
}
