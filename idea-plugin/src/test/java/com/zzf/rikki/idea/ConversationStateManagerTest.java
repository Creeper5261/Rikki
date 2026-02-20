package com.zzf.rikki.idea;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConversationStateManagerTest {
    @Test
    void manager_should_resolve_find_last_and_snapshot_uniquely() {
        AtomicInteger seq = new AtomicInteger();
        MessageStateStore<String> store = new MessageStateStore<>(
                "__pending__",
                () -> "p-" + seq.incrementAndGet(),
                id -> "m-" + id + "-" + seq.incrementAndGet()
        );
        ConversationStateManager<String> manager = new ConversationStateManager<>("__pending__", store);
        manager.bindPending("pending-ui");

        String resolved = manager.resolve("msg-1");
        assertEquals("pending-ui", resolved);
        assertSame(resolved, manager.find("msg-1"));
        assertSame(resolved, manager.last());

        manager.resolve("msg-2");
        List<String> snapshot = manager.uniqueSnapshot();
        assertEquals(2, snapshot.size());
        assertEquals("pending-ui", snapshot.get(0));
        assertEquals("m-msg-2-1", snapshot.get(1));
    }
}
