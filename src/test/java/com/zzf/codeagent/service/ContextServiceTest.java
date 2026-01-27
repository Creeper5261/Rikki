package com.zzf.codeagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ContextServiceTest {

    @Test
    public void testResolveDeepSeekApiKeyPrefersConfig() {
        ContextService service = new ContextService();
        ReflectionTestUtils.setField(service, "deepSeekApiKey", "config-key");

        String apiKey = service.resolveDeepSeekApiKey();

        assertEquals("config-key", apiKey);
    }

    @Test
    public void testFixMojibakeIfNeeded() {
        ContextService service = new ContextService();
        String original = "你好你好你好你好你好";
        String mojibake = new String(original.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);

        String fixed = service.fixMojibakeIfNeeded(mojibake);

        assertEquals(original, fixed);
        assertEquals("hello", service.fixMojibakeIfNeeded("hello"));
    }

    @Test
    public void testRootMessage() {
        ContextService service = new ContextService();
        Throwable t = new RuntimeException("outer", new IllegalStateException("inner"));

        String msg = service.rootMessage(t);

        assertEquals("inner", msg);
    }

    @Test
    public void testTruncate() {
        ContextService service = new ContextService();

        assertEquals("abc", service.truncate("abc", 10));
        assertEquals("ab", service.truncate("abc", 2));
        assertEquals("", service.truncate(null, 3));
    }
}
