package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.MaterialLocks;
import com.huanghuang.rsintegration.crafting.planning.PlanCache;
import com.huanghuang.rsintegration.crafting.planning.PlanningPreferences;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/** 预览与确认共用的计划缓存键规则。 */
final class CraftPlanCacheKeys {
    private CraftPlanCacheKeys() {}

    static PlanCache.Key key(UUID playerId, ResourceLocation recipeId,
                             Map<String, String> forcedRecipes, int repeatCount,
                             @Nullable ItemStack clickedOutput,
                             @Nullable ModType terminalType) {
        return key(playerId, recipeId, forcedRecipes, repeatCount,
                clickedOutput, terminalType, Map.of());
    }

    static PlanCache.Key key(UUID playerId, ResourceLocation recipeId,
                             Map<String, String> forcedRecipes, int repeatCount,
                             @Nullable ItemStack clickedOutput,
                             @Nullable ModType terminalType,
                             Map<String, ItemStack> materialLocks) {
        return key(playerId, recipeId, forcedRecipes, repeatCount,
                clickedOutput, terminalType, materialLocks, PlanningPreferences.empty());
    }

    static PlanCache.Key key(UUID playerId, ResourceLocation recipeId,
                             Map<String, String> forcedRecipes, int repeatCount,
                             @Nullable ItemStack clickedOutput,
                             @Nullable ModType terminalType,
                             Map<String, ItemStack> materialLocks,
                             PlanningPreferences preferences) {
        return new PlanCache.Key(playerId, recipeId, forcedRecipes, repeatCount,
                clickedOutputToken(clickedOutput) + "|"
                        + (terminalType == null ? "generic" : terminalType.id()),
                MaterialLocks.cacheTokens(materialLocks), preferences);
    }

    private static String clickedOutputToken(@Nullable ItemStack clicked) {
        if (clicked == null || clicked.isEmpty()) return "0";
        String key = BuiltInRegistries.ITEM.getKey(clicked.getItem()).toString();
        CompoundTag tag = clicked.getTag();
        return tag != null ? key + "#" + tag : key;
    }
}
