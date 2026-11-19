package com.huanghuang.rsintegration.crafting.graph;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 各轮遍历在第一次进入该轮时才取得迭代器，允许前一轮填充索引。 */
public abstract class GraphWork {
    private final ArrayDeque<BooleanSupplier> stages = new ArrayDeque<>();

    protected final <T> void scan(Supplier<Iterator<T>> items, Consumer<T> visit) {
        stages.add(new BooleanSupplier() {
            Iterator<T> cursor;
            public boolean getAsBoolean() {
                if (cursor == null) cursor = items.get();
                if (cursor.hasNext()) visit.accept(cursor.next());
                return !cursor.hasNext();
            }
        });
    }

    protected final void once(Runnable action) {
        scan(() -> List.of(action).iterator(), Runnable::run);
    }

    public final boolean advance(long budgetNanos) {
        long start = System.nanoTime();
        while (!stages.isEmpty()) {
            if (stages.element().getAsBoolean()) stages.remove();
            if (System.nanoTime() - start >= budgetNanos) break;
        }
        return stages.isEmpty();
    }

    protected final void requireComplete() {
        if (!stages.isEmpty()) throw new IllegalStateException("图遍历尚未完成");
    }
}
