package com.huanghuang.rsintegration.mods.ironsspellbooks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

final class CatalogBuildSteps {
    private final Deque<Runnable> steps = new ArrayDeque<>();

    void then(Runnable step) {
        steps.addLast(step);
    }

    void afterCurrent(Runnable step) {
        steps.addFirst(step);
    }

    // 子步骤先于当前循环的下一项执行，保持原有遍历及配方顺序。
    <T> void each(Iterator<T> items, Consumer<T> action) {
        steps.addFirst(new Runnable() {
            @Override
            public void run() {
                if (!items.hasNext()) return;
                T item = items.next();
                if (items.hasNext()) steps.addFirst(this);
                action.accept(item);
            }
        });
    }

    boolean advance(BooleanSupplier budgetExpired) {
        while (!steps.isEmpty()) {
            if (budgetExpired.getAsBoolean()) return false;
            steps.removeFirst().run();
        }
        return true;
    }
}
