package com.zzf.rikki.idea.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiteLlmClientTest {

    @Test
    void buildFimBody_shouldIncludePromptAndSuffix() {
        String body = LiteLlmClient.buildFimBody("deepseek-chat", "pre\n", "suf");

        assertTrue(body.contains("\"model\":\"deepseek-chat\""));
        assertTrue(body.contains("\"prompt\":\"pre\\n\""));
        assertTrue(body.contains("\"suffix\":\"suf\""));
    }

    @Test
    void extractFimText_shouldDecodeEscapedText() {
        assertEquals("line1\nline2", LiteLlmClient.extractFimText("{\"choices\":[{\"text\":\"line1\\nline2\"}]}"));
    }

    @Test
    void buildChatBody_shouldIncludeCursorLanguageAndChatMessages() {
        String body = LiteLlmClient.buildChatBody("gpt-4.1-nano", "pre", "suf", "Java");

        assertTrue(body.contains("\"model\":\"gpt-4.1-nano\""));
        assertTrue(body.contains("Language: Java"));
        assertTrue(body.contains("pre<|CURSOR|>suf"));
        assertTrue(body.contains("\"messages\":["));
    }

    @Test
    void extractChatContent_shouldHandleNullAndEscapedPayload() {
        assertNull(LiteLlmClient.extractChatContent("{\"choices\":[{\"delta\":{\"content\":null}}]}"));
        assertEquals("a\tb\"c", LiteLlmClient.extractChatContent("{\"choices\":[{\"delta\":{\"content\":\"a\\tb\\\"c\"}}]}"));
    }

    @Test
    void extractEscapedString_shouldDecodeBackslashAndNewline() {
        assertEquals("a\\b\nc", LiteLlmClient.extractEscapedString("\"a\\\\b\\nc\"", 1));
    }
}
