package com.zzf.codeagent.idea;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MessageStateStoreTest {
    @Test
    void resolve_should_promote_pending_entry_to_identified_entry() {
        AtomicInteger seq = new AtomicInteger(0);
        MessageStateStore<String> store = new MessageStateStore<>(
                "__pending__",
                () -> "pending-" + seq.incrementAndGet(),
                id -> "id-" + id + "-" + seq.incrementAndGet()
        );
        Map<String, String> map = new LinkedHashMap<>();

        String pending = store.resolve(map, "");
        String promoted = store.resolve(map, "msg-1");

        assertSame(pending, promoted);
        assertEquals(1, map.size());
        assertEquals(pending, map.get("msg-1"));
    }

    @Test
    void last_should_return_last_inserted_or_create_pending_when_empty() {
        MessageStateStore<String> store = new MessageStateStore<>(
                "__pending__",
                () -> "pending",
                id -> "id-" + id
        );
        Map<String, String> map = new LinkedHashMap<>();

        String created = store.last(map);
        assertEquals("pending", created);

        map.put("a", "first");
        map.put("b", "last");
        assertEquals("last", store.last(map));
    }
}
