package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Selects the unresolved raw-material leaves shown by the plan material bill. */
public final class MissingMaterialBookmarkList {
    private MissingMaterialBookmarkList() {}

    public static List<ItemStack> from(PlanResponse plan) {
        return plan.materials().entrySet().stream()
                .filter(entry -> !entry.getValue().isEnough())
                .map(entry -> entry.getKey().stack(1))
                .toList();
    }
}
