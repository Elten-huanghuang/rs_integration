package com.huanghuang.rsintegration.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ThreadLocalStackTest {

    @Test
    void nestedPopRestoresOuterValue() {
        ThreadLocalStack<String> stack = new ThreadLocalStack<>();
        stack.push("outer");
        stack.push("inner");

        assertEquals("inner", stack.peek());
        stack.pop();
        assertEquals("outer", stack.peek());
        stack.pop();
        assertNull(stack.peek());
    }

    @Test
    void emptyPopIsHarmless() {
        ThreadLocalStack<String> stack = new ThreadLocalStack<>();

        stack.pop();

        assertNull(stack.peek());
    }
}
