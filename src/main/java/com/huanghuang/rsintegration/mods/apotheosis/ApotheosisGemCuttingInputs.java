package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

final class ApotheosisGemCuttingInputs {
    private final List<IngredientSpec> reservationSlots;
    private final int dustEnd;

    ApotheosisGemCuttingInputs(List<IngredientSpec> recipeSlots) {
        List<IngredientSpec> slots = new ArrayList<>();
        slots.add(recipeSlots.get(0));
        // 每个消耗品独立预留，避免要求一整槽的材料拥有相同 NBT。
        appendUnits(slots, recipeSlots.get(1));
        dustEnd = slots.size();
        appendUnits(slots, recipeSlots.get(2));
        reservationSlots = List.copyOf(slots);
    }

    List<IngredientSpec> reservationSlots() { return reservationSlots; }

    @Nullable
    MenuInputs forBatch(List<ItemStack> materials, int executions) {
        if (materials.size() != reservationSlots.size() || executions <= 0) return null;
        ItemStack gems = materials.get(0);
        if (gems.isEmpty() || gems.getCount() != 2L * executions) return null;
        ItemStack dust = menuStack(materials, 1, dustEnd, executions);
        ItemStack rarityMaterial = menuStack(materials, dustEnd, materials.size(), executions);
        if (dust.isEmpty() || rarityMaterial.isEmpty()) return null;
        return new MenuInputs(gems.copyWithCount(1), dust, rarityMaterial);
    }

    private static void appendUnits(List<IngredientSpec> slots, IngredientSpec spec) {
        IngredientSpec unit = new IngredientSpec(spec.ingredient(), 1, spec.consumption());
        for (int i = 0; i < spec.count(); i++) slots.add(unit);
    }

    private static ItemStack menuStack(List<ItemStack> materials, int first, int end, int executions) {
        if (first >= end) return ItemStack.EMPTY;
        ItemStack template = materials.get(first);
        for (int i = first; i < end; i++) {
            ItemStack stack = materials.get(i);
            if (stack.isEmpty() || stack.getItem() != template.getItem()
                    || stack.getCount() != executions) return ItemStack.EMPTY;
        }
        // 原生菜单只检查物品类型和数量；原始堆及其 NBT 仍由账本负责结算。
        return new ItemStack(template.getItem(), end - first);
    }

    record MenuInputs(ItemStack gem, ItemStack dust, ItemStack rarityMaterial) {}
}
