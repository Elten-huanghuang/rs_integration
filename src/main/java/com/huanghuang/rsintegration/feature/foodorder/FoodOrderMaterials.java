package com.huanghuang.rsintegration.feature.foodorder;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 复用正式的材料分配器生成缺料说明，避免重复槽位和替代材料重复计算库存。 */
final class FoodOrderMaterials {
    private static final int MAX_ROWS = 32;
    private static final int MAX_ALTERNATIVE_NAMES = 3;

    private FoodOrderMaterials() {}

    static Component describe(List<IngredientSpec> specs, Map<StackKey, Long> available) {
        return describe(specs, available, Long.MAX_VALUE);
    }

    static Component describe(List<IngredientSpec> specs, Map<StackKey, Long> available, long deadline) {
        MutableComponent detail = Component.translatable(FoodOrderKind.MISSING.detailKey());
        List<IngredientSpec> remaining = new ArrayList<>(specs);
        Map<Component, Long> shortages = new LinkedHashMap<>();
        boolean partial = false;
        for (int attempt = 0; attempt < specs.size(); attempt++) {
            if (System.nanoTime() >= deadline) {
                partial = true;
                break;
            }
            DirectMaterialAllocator.Result allocation = DirectMaterialAllocator.allocate(remaining, available);
            if (allocation.feasible()) {
                break;
            }
            int index = allocation.missingIngredientIndex();
            if (index < 0 || index >= remaining.size() || allocation.missingCount() <= 0L) {
                break;
            }
            IngredientSpec spec = remaining.get(index);
            long missing = Math.min(spec.count(), allocation.missingCount());
            Component label = ingredientName(spec);
            shortages.merge(label, missing, Long::sum);
            // 只移除已确认缺少的需求，再次分配其余槽位；同一份库存始终只计一次。
            remaining.set(index, new IngredientSpec(spec.ingredient(),
                    spec.count() - (int) missing, spec.consumption()));
            if (shortages.size() >= MAX_ROWS) {
                partial = true;
                break;
            }
        }
        if (!shortages.isEmpty()) {
            detail.append("\n").append(Component.translatable("rsi.food_order.materials.heading"));
            shortages.forEach((name, count) -> detail.append("\n").append(
                    Component.translatable("rsi.food_order.materials.row", name, count)));
        }
        if (partial) {
            detail.append("\n").append(Component.translatable("rsi.food_order.materials.partial"));
        }
        return detail;
    }

    private static Component ingredientName(IngredientSpec spec) {
        ItemStack[] alternatives = spec.ingredient().getItems();
        MutableComponent name = Component.empty();
        int shown = 0;
        for (ItemStack stack : alternatives) {
            if (stack.isEmpty()) {
                continue;
            }
            if (shown > 0) {
                name.append(Component.translatable("rsi.food_order.materials.or"));
            }
            name.append(stack.getHoverName());
            if (++shown >= MAX_ALTERNATIVE_NAMES) {
                break;
            }
        }
        if (shown == 0) {
            return Component.translatable("rsi.food_order.materials.unknown");
        }
        if (alternatives.length > shown) {
            name.append(Component.translatable("rsi.food_order.materials.alternatives",
                    alternatives.length - shown));
        }
        return name;
    }
}
