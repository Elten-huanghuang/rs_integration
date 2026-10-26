package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 补做规划不能消费尚未执行步骤已经需要的库存。 */
final class SupplementalMaterialPlan {
    private SupplementalMaterialPlan() {}

    /** 每个机器槽必须由同一种物品填满；相同谓词的不同槽依次扣减库存。 */
    static boolean hasInputs(Map<StackKey, Long> available, List<IngredientSpec> inputs) {
        Map<StackKey, Long> remaining = new LinkedHashMap<>(available);
        for (IngredientSpec input : inputs) {
            if (input == null || input.isEmpty()) continue;
            StackKey selected = null;
            for (Map.Entry<StackKey, Long> entry : remaining.entrySet()) {
                StackKey key = entry.getKey();
                if (entry.getValue() >= input.count() && IngredientMatcher.test(input.ingredient(),
                        new MaterialKey(key.item(), key.tag()).toStack(1))) {
                    selected = key;
                    break;
                }
            }
            if (selected == null) return false;
            remaining.put(selected, remaining.get(selected) - input.count());
        }
        return true;
    }

    static Map<StackKey, Long> afterProtecting(Map<StackKey, Long> available,
                                                List<IngredientSpec> protectedInputs) {
        Map<StackKey, Long> remaining = new LinkedHashMap<>(available);
        for (IngredientSpec input : protectedInputs) {
            long protect = input.count();
            for (Map.Entry<StackKey, Long> entry : remaining.entrySet()) {
                if (protect <= 0) break;
                StackKey key = entry.getKey();
                if (entry.getValue() <= 0 || !IngredientMatcher.test(input.ingredient(),
                        new MaterialKey(key.item(), key.tag()).toStack(1))) continue;
                long take = Math.min(protect, entry.getValue());
                entry.setValue(entry.getValue() - take);
                protect -= take;
            }
        }
        return remaining;
    }

    /** 与下游保护取并集，避免同一份产物被重复预留或被补做配方重新消耗。 */
    static void protectProduced(Map<StackKey, Long> available, Map<StackKey, Long> usable,
                                ProductionTarget target, long produced) {
        if (target.material() == null) return;
        long protect = produced;
        for (Map.Entry<StackKey, Long> entry : available.entrySet()) {
            if (protect <= 0L) break;
            StackKey key = entry.getKey();
            ItemStack stack = new MaterialKey(key.item(), key.tag()).toStack(1);
            if (!MaterialMatcher.matchesOutputDeclaration(target.material(), stack)) continue;
            long count = Math.max(0L, entry.getValue());
            int take = (int) Math.min(protect, count);
            usable.computeIfPresent(key, (ignored, remaining) -> Math.min(remaining, count - take));
            protect -= take;
        }
    }
}
