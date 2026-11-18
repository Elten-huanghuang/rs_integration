package com.huanghuang.rsintegration.crafting;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/** 配方、槽位和候选各自保存继续位置，深度不再占用 Java 调用栈。 */
final class PlanningStack {
    private final Deque<Runnable> cleanup = new ArrayDeque<>();
    private Runnable next;

    void resume(Runnable action) {
        if (next != null) throw new IllegalStateException("规划帧已安排");
        next = action;
    }

    <T> void complete(Consumer<T> result, T value) {
        resume(() -> result.accept(value));
    }

    void enter(Runnable onExit) {
        cleanup.push(onExit);
    }

    void leave() {
        cleanup.pop().run();
    }

    <T> T resolve(Consumer<Consumer<T>> start) {
        Job<T> job = start(start);
        job.advance(Long.MAX_VALUE);
        return job.result();
    }

    <T> Job<T> start(Consumer<Consumer<T>> start) {
        return new Job<>(start);
    }

    final class Job<T> {
        private T value;
        private boolean done;

        private Job(Consumer<Consumer<T>> start) {
            resume(() -> start.accept(result -> {
                value = result;
                done = true;
            }));
        }

        boolean advance(long sliceNanos) {
            long started = System.nanoTime();
            try {
                while (next != null) {
                    Runnable action = next;
                    next = null;
                    action.run();
                    if (next != null && System.nanoTime() - started >= sliceNanos) return false;
                }
                if (!done || !cleanup.isEmpty()) throw new IllegalStateException("规划帧未完成");
                return true;
            } catch (RuntimeException | LinkageError failure) {
                while (!cleanup.isEmpty()) leave();
                next = null;
                throw failure;
            }
        }

        T result() {
            if (!done) throw new IllegalStateException("规划尚未完成");
            return value;
        }
    }
}
