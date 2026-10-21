package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftingResolver;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/** 将网络层的字符串强制配方键解析成服务端资源标识。 */
final class CraftForcedRecipeOverrides {
    private CraftForcedRecipeOverrides() {}

    record Resolution(
            @Nullable Map<ResourceLocation, ResourceLocation> overrides,
            @Nullable String invalidKey,
            @Nullable String invalidRecipe) {
        boolean valid() {
            return invalidKey == null && invalidRecipe == null;
        }
    }

    static Resolution resolve(
            ServerPlayer player, Map<String, String> forcedRecipes) {
        if (forcedRecipes.isEmpty()) {
            return new Resolution(null, null, null);
        }
        Map<ResourceLocation, ResourceLocation> overrides = new HashMap<>();
        for (var entry : forcedRecipes.entrySet()) {
            ResourceLocation itemKey = ResourceLocation.tryParse(entry.getKey());
            ResourceLocation forcedId = ResourceLocation.tryParse(entry.getValue());
            boolean validKey = itemKey != null
                    && (CraftingResolver.isStackPreferenceKey(itemKey)
                    || BuiltInRegistries.ITEM.containsKey(itemKey));
            if (!validKey || forcedId == null
                    || CraftRecipeResolver.resolve(
                    player.serverLevel(), forcedId) == null) {
                return new Resolution(
                        null, String.valueOf(entry.getKey()),
                        String.valueOf(entry.getValue()));
            }
            overrides.put(itemKey, forcedId);
        }
        return new Resolution(overrides, null, null);
    }
}
