package com.zzf.codeagent.idea;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatSseAdapterTest {
    @Test
    void consume_should_parse_events_and_flush_tail_payload() {
        ChatSseAdapter adapter = new ChatSseAdapter();
        List<String> captured = new ArrayList<>();
        List<String> lines = List.of(
                "event: message",
                "data: hello",
                "",
                "event: tool_call",
                "data: line1",
                "data: line2"
        );

        adapter.consume(lines, (event, data) -> captured.add(event + "|" + data));

        assertEquals(2, captured.size());
        assertEquals("message|hello", captured.get(0));
        assertEquals("tool_call|line1\nline2", captured.get(1));
    }
}
