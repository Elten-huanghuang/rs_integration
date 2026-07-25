package com.huanghuang.rsintegration.util;

import java.util.ArrayDeque;
import java.util.Deque;

/** Per-call marker for external insertions that intentionally destroy rather than store items. */
public final class ExternalItemProgressSuppression {

    private static final ThreadLocal<Deque<Boolean>> OPERATIONS = new ThreadLocal<>();

    private ExternalItemProgressSuppression() {}

    public static void beginOperation() {
        Deque<Boolean> operations = OPERATIONS.get();
        if (operations == null) {
            operations = new ArrayDeque<>();
            OPERATIONS.set(operations);
        }
        operations.push(false);
    }

    public static void suppress() {
        Deque<Boolean> operations = OPERATIONS.get();
        if (operations == null || operations.isEmpty()) {
            operations = new ArrayDeque<>();
            OPERATIONS.set(operations);
            operations.push(true);
            return;
        }
        operations.pop();
        operations.push(true);
    }

    public static boolean consume() {
        Deque<Boolean> operations = OPERATIONS.get();
        if (operations == null || operations.isEmpty()) return false;
        boolean suppressed = operations.pop();
        if (operations.isEmpty()) OPERATIONS.remove();
        return suppressed;
    }
}
