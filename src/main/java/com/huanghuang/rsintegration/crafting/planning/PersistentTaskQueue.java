package com.huanghuang.rsintegration.crafting.planning;

import javax.annotation.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/** 不可变持久队列；分支只新增自己的前缀，后续任务与父状态共享。 */
final class PersistentTaskQueue<T> implements Iterable<T> {
    @Nullable
    private final T head;
    @Nullable
    private final PersistentTaskQueue<T> tail;
    private final int size;
    private final int hash;

    private PersistentTaskQueue(@Nullable T head, @Nullable PersistentTaskQueue<T> tail,
                                int size, int hash) {
        this.head = head;
        this.tail = tail;
        this.size = size;
        this.hash = hash;
    }

    static <T> PersistentTaskQueue<T> empty() {
        return new PersistentTaskQueue<>(null, null, 0, 1);
    }

    static <T> PersistentTaskQueue<T> from(List<? extends T> values) {
        PersistentTaskQueue<T> queue = empty();
        for (int index = values.size() - 1; index >= 0; index--) {
            queue = queue.prepend(values.get(index));
        }
        return queue;
    }

    PersistentTaskQueue<T> prepend(T value) {
        T nonNull = Objects.requireNonNull(value, "value");
        int nextHash = 31 * hash + nonNull.hashCode();
        return new PersistentTaskQueue<>(nonNull, this, size + 1, nextHash);
    }

    T head() {
        if (head == null) throw new NoSuchElementException("empty task queue");
        return head;
    }

    PersistentTaskQueue<T> tail() {
        if (tail == null) throw new NoSuchElementException("empty task queue");
        return tail;
    }

    boolean isEmpty() {
        return size == 0;
    }

    @Override
    public Iterator<T> iterator() {
        return new Iterator<>() {
            private PersistentTaskQueue<T> current = PersistentTaskQueue.this;

            @Override
            public boolean hasNext() {
                return !current.isEmpty();
            }

            @Override
            public T next() {
                T value = current.head();
                current = current.tail();
                return value;
            }
        };
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof PersistentTaskQueue<?> queue)
                || size != queue.size || hash != queue.hash) {
            return false;
        }
        Iterator<T> left = iterator();
        Iterator<?> right = queue.iterator();
        while (left.hasNext() && right.hasNext()) {
            if (!Objects.equals(left.next(), right.next())) return false;
        }
        return !left.hasNext() && !right.hasNext();
    }

    @Override
    public int hashCode() {
        return hash;
    }
}
