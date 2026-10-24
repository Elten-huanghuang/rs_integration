package com.huanghuang.rsintegration.crafting.planning;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentTaskQueueTest {
    @Test
    void prependSharesTheExistingTail() {
        PersistentTaskQueue<String> shared =
                PersistentTaskQueue.from(List.of("shared-1", "shared-2"));

        PersistentTaskQueue<String> left = shared.prepend("left");
        PersistentTaskQueue<String> right = shared.prepend("right");

        assertSame(shared, left.tail());
        assertSame(shared, right.tail());
        assertEquals(List.of("left", "shared-1", "shared-2"), values(left));
        assertEquals(List.of("right", "shared-1", "shared-2"), values(right));
    }

    @Test
    void equalityAndHashDoNotDependOnConstructionShape() {
        PersistentTaskQueue<String> fromList =
                PersistentTaskQueue.from(List.of("a", "b", "c"));
        PersistentTaskQueue<String> fromPrepend =
                PersistentTaskQueue.from(List.of("c")).prepend("b").prepend("a");

        assertEquals(fromList, fromPrepend);
        assertEquals(fromList.hashCode(), fromPrepend.hashCode());
        assertFalse(fromList.isEmpty());
        assertTrue(PersistentTaskQueue.empty().isEmpty());
    }

    private static List<String> values(PersistentTaskQueue<String> queue) {
        List<String> values = new ArrayList<>();
        queue.forEach(values::add);
        return List.copyOf(values);
    }
}
