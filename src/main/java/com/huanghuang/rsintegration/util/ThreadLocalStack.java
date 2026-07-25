package com.huanghuang.rsintegration.util;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Restores the previous thread-local value after a nested call returns. */
public final class ThreadLocalStack<T> {

    private final ThreadLocal<Deque<T>> values = new ThreadLocal<>();

    public void push(T value) {
        Deque<T> stack = values.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            values.set(stack);
        }
        stack.push(Objects.requireNonNull(value));
    }

    @Nullable
    public T peek() {
        Deque<T> stack = values.get();
        return stack == null ? null : stack.peek();
    }

    public void pop() {
        Deque<T> stack = values.get();
        if (stack == null || stack.isEmpty()) return;
        stack.pop();
        if (stack.isEmpty()) values.remove();
    }
}
