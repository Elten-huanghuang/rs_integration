package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * 根据配方处理器声明的即时批次契约，判断同类图节点能否汇总。
 */
public final class CraftNodeMergePolicy {
    private CraftNodeMergePolicy() {}

    public static Predicate<CraftNode> forLevel(Level level) {
        Objects.requireNonNull(level, "level");
        Map<ResourceLocation, Boolean> decisions = new HashMap<>();
        return node -> decisions.computeIfAbsent(node.recipeId(),
                ignored -> canMerge(level, node));
    }

    private static boolean canMerge(Level level, CraftNode node) {
        if (ModType.GENERIC.id().equals(node.modTypeId())) return true;
        Recipe<?> recipe = level.getRecipeManager().byKey(node.recipeId()).orElse(null);
        if (recipe == null) return false;
        ModRecipeHandler handler = ModRecipeHandlers.claimFor(recipe).winner();
        if (handler == null || !handler.modType().id().equals(node.modTypeId())) return false;
        return handler.instantRecipePolicy(recipe).enabled();
    }
}
