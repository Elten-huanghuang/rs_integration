package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.world.item.ItemStack;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** 先试算选材，再保存偏好；失败只撤回本次硬锁，保留此前可用路线。 */
final class PendingMaterialChoice {
    private final String key;
    private final ItemStack previous;
    private final ItemStack requested;

    PendingMaterialChoice(Map<String, ItemStack> locks, String key, ItemStack requested) {
        this.key = key;
        this.previous = locks.getOrDefault(key, ItemStack.EMPTY).copy();
        this.requested = requested.copyWithCount(1);
        if (requested.isEmpty()) locks.remove(key);
        else locks.put(key, this.requested.copy());
    }

    CraftingPlanPreferences.Snapshot preview(CraftingPlanPreferences.Snapshot stored) {
        Map<String, ItemStack> ingredients = new LinkedHashMap<>(stored.ingredientChoices());
        if (requested.isEmpty()) ingredients.remove(key);
        else ingredients.put(key, requested);
        return new CraftingPlanPreferences.Snapshot(stored.outputDestination(), stored.recipeChoices(),
                ingredients, stored.excludedRecipes());
    }

    void rollback(Map<String, ItemStack> locks) {
        if (previous.isEmpty()) locks.remove(key);
        else locks.put(key, previous.copy());
    }

    void commit(Path path) {
        CraftingPlanPreferences.saveIngredientChoice(path, key, requested);
    }
}
