package com.huanghuang.rsintegration.unifiedgrid.client;

import com.refinedmods.refinedstorage.api.autocrafting.ICraftingPatternProvider;
import com.refinedmods.refinedstorage.screen.grid.stack.IGridStack;
import com.refinedmods.refinedstorage.screen.grid.stack.ItemGridStack;
import com.refinedmods.refinedstorage.util.ItemStackKey;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** JEI 内部汇总使用宽计数防止多个 NBT 变体相加溢出；资源和网络数量仍为 int。 */
public final class UnifiedGridJeiTracker {
    private UnifiedGridJeiTracker() { }

    public static void attach(UnifiedGridView view, Map<ItemStackKey, Integer> storedItems) {
        Map<UUID, ItemStackKey> keys = new HashMap<>();
        Map<ItemStackKey, Long> totals = new HashMap<>();
        for (IGridStack stack : view.getAllStacks()) {
            if (!(stack instanceof ItemGridStack item) || item.isCraftable()
                    || item.getStack().getItem() instanceof ICraftingPatternProvider) continue;
            // 与本项目 IngredientTrackerMixin 的忽略 NBT 汇总保持一致。
            ItemStack template = item.getStack().copyWithCount(1);
            template.setTag(null);
            ItemStackKey key = new ItemStackKey(template);
            keys.put(item.getId(), key);
            totals.merge(key, (long) Math.max(0, item.getQuantity()), Long::sum);
        }
        totals.forEach((key, total) -> storedItems.put(key, clamp(total)));
        view.addQuantityListener(change -> {
            ItemStackKey key = keys.get(change.stack().getId());
            if (key == null) return;
            long total = totals.get(key) + (long) change.after() - change.before();
            totals.put(key, total);
            storedItems.put(key, clamp(total));
        });
    }

    private static int clamp(long total) { return (int) Math.min(Integer.MAX_VALUE, Math.max(0, total)); }
}
