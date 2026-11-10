package com.huanghuang.rsintegration.feature.foodorder;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/** 完整食物目录与跨 tick 保留的可用性判定进度。 */
final class FoodOrderListProgress {
    private final List<Item> foods;
    private final List<FoodOrderEntry> entries;
    private int cursor;
    private int orderable;

    FoodOrderListProgress(List<Item> foods) {
        this.foods = List.copyOf(foods);
        entries = new ArrayList<>(foods.size());
        for (Item food : foods) {
            entries.add(new FoodOrderEntry(new ItemStack(food), FoodOrderKind.WAITING,
                    null, Component.translatable(FoodOrderKind.WAITING.detailKey())));
        }
    }

    void advance(long deadline, BiFunction<Item, Long, FoodOrderEntry> evaluator) {
        int start = cursor;
        // 每轮至少完成一种食物；一份食物的有限配方检查作为不可拆分的工作单元。
        while (cursor < foods.size() && (cursor == start || System.nanoTime() < deadline)) {
            FoodOrderEntry entry = evaluator.apply(foods.get(cursor), deadline);
            entries.set(cursor++, entry);
            if (entry.orderable()) {
                orderable++;
            }
        }
    }

    FoodOrderSnapshot snapshot(long epoch, Component sources, boolean indexing) {
        List<FoodOrderEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(entry -> entry.kind().rank()));
        return new FoodOrderSnapshot(epoch, sorted, sources, foods.size(), orderable,
                indexing, cursor < foods.size());
    }
}
