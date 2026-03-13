package com.zzf.rikki.idea.settings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RikkiCredentialsTest {

    @AfterEach
    void tearDown() {
        RikkiCredentials.clearForTest();
    }

    @Test
    void get_shouldReadInjectedCacheCaseInsensitively() {
        RikkiCredentials.injectForTest("openai", "token-1");

        assertEquals("token-1", RikkiCredentials.get("OPENAI"));
        assertEquals("token-1", RikkiCredentials.get("openai"));
    }

    @Test
    void get_shouldReturnEmptyStringForMissingKey() {
        assertEquals("", RikkiCredentials.get("MISSING"));
    }
}
