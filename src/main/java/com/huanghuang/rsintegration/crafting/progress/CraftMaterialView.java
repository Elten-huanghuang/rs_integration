package com.huanghuang.rsintegration.crafting.progress;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 材料总览的权威数量，独立于加工次数。available 只代表任务实际持有的未投料物资。 */
public record CraftMaterialView(List<Row> rows, boolean complete) {
    public static final int MAX_ROWS = CraftProgressSnapshot.MAX_NODE_DETAILS;
    public static final CraftMaterialView EMPTY = new CraftMaterialView(List.of(), false);
    public CraftMaterialView {
        rows = List.copyOf(rows);
        if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("too many monitor materials");
    }

    public record Row(MaterialKey material, long available, long processing, long crafting,
                      long scheduled, long toExtract, long produced, boolean uncertain) {
        public Row {
            if (material == null || available < 0 || processing < 0 || crafting < 0 || scheduled < 0 || toExtract < 0 || produced < 0) {
                throw new IllegalArgumentException("invalid material quantities");
            }
        }
    }

    public static final class Builder {
        private final Map<MaterialKey, Row> rows = new LinkedHashMap<>();
        private boolean complete = true;

        public void incomplete() { complete = false; }

        public void add(MaterialKey key, long available, long processing, long crafting, long scheduled,
                        long toExtract, long produced, boolean uncertain) {
            if (!rows.containsKey(key) && rows.size() >= MAX_ROWS) { complete = false; return; }
            Row old = rows.getOrDefault(key, new Row(key, 0, 0, 0, 0, 0, 0, false));
            rows.put(key, new Row(key, sum(old.available(), available), sum(old.processing(), processing),
                    sum(old.crafting(), crafting), sum(old.scheduled(), scheduled), sum(old.toExtract(), toExtract),
                    sum(old.produced(), produced), old.uncertain() || uncertain));
        }

        public void held(List<ItemStack> stacks) {
            for (var stack : stacks) if (!stack.isEmpty()) add(MaterialKey.of(stack), stack.getCount(), 0, 0, 0, 0, 0, false);
        }

        public void processing(List<ItemStack> stacks) {
            for (var stack : stacks) if (!stack.isEmpty()) add(MaterialKey.of(stack), 0, stack.getCount(), 0, 0, 0, 0, false);
        }

        public CraftMaterialView build() { return new CraftMaterialView(List.copyOf(rows.values()), complete); }
        private static long sum(long left, long right) { return SaturatingLongMath.add(left, right); }
    }
}
