package com.zzf.codeagent.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class JsonUtilsTest {

    @Test
    public void testExtractFirstJsonObjectFromFenced() {
        String raw = "prefix ```json {\"intent\":\"SEARCH\"} ``` suffix";
        String json = JsonUtils.extractFirstJsonObject(raw);
        assertEquals("{\"intent\":\"SEARCH\"}", json);
    }

    @Test
    public void testExtractFirstJsonObjectFromInline() {
        String raw = "noise {\"a\":1,\"b\":{\"c\":2}} tail";
        String json = JsonUtils.extractFirstJsonObject(raw);
        assertEquals("{\"a\":1,\"b\":{\"c\":2}}", json);
    }

    @Test
    public void testExtractFirstJsonObjectThrowsWhenMissing() {
        assertThrows(IllegalArgumentException.class, () -> JsonUtils.extractFirstJsonObject("no json here"));
    }

    @Test
    public void testIntOrNullAndTextFallback() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = mapper.createObjectNode();
        node.put("primary", 12);
        node.put("secondary", 7);
        node.put("textB", "value");
        node.put("textA", " ");

        assertEquals(12, JsonUtils.intOrNull(node, "primary", "secondary"));
        assertEquals("value", JsonUtils.textOrFallback(node, "textA", "textB"));
    }
}
