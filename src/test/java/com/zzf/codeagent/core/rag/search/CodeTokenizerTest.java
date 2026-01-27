package com.zzf.codeagent.core.rag.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CodeTokenizerTest {

    @Test
    public void testTokenizeHandlesNullAndSeparators() {
        CodeTokenizer tokenizer = new CodeTokenizer();

        List<String> empty = tokenizer.tokenize(null);
        assertEquals(0, empty.size());

        List<String> tokens = tokenizer.tokenize("UserService login_user $value");
        assertEquals(List.of("UserService", "login_user", "$value"), tokens);
    }
}
