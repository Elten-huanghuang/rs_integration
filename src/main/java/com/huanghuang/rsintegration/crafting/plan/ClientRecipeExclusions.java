package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** 客户端 JEI 快捷书签使用的递归禁用偏好缓存。 */
public final class ClientRecipeExclusions {
    private static Set<String> excluded;

    private ClientRecipeExclusions() {}

    public static synchronized boolean isExcluded(ResourceLocation recipeId) {
        return recipeId != null && snapshot().contains(recipeId.toString());
    }

    public static synchronized boolean toggle(ResourceLocation recipeId) {
        if (!isStableRecipeId(recipeId)) return false;
        boolean excludedNow = !snapshot().contains(recipeId.toString());
        if (!CraftingPlanPreferences.setExcluded(
                preferencesPath(), recipeId.toString(), excludedNow)) {
            return false;
        }
        Set<String> updated = new LinkedHashSet<>(snapshot());
        if (excludedNow) updated.add(recipeId.toString());
        else updated.remove(recipeId.toString());
        excluded = Set.copyOf(updated);
        return true;
    }

    public static synchronized boolean exclude(ResourceLocation recipeId) {
        if (!isStableRecipeId(recipeId)) return false;
        if (snapshot().contains(recipeId.toString())) return true;
        if (!CraftingPlanPreferences.setExcluded(
                preferencesPath(), recipeId.toString(), true)) {
            return false;
        }
        Set<String> updated = new LinkedHashSet<>(snapshot());
        updated.add(recipeId.toString());
        excluded = Set.copyOf(updated);
        return true;
    }

    public static synchronized boolean clear(ResourceLocation recipeId) {
        if (!isStableRecipeId(recipeId) || !snapshot().contains(recipeId.toString())) {
            return true;
        }
        if (!CraftingPlanPreferences.setExcluded(
                preferencesPath(), recipeId.toString(), false)) {
            return false;
        }
        Set<String> updated = new LinkedHashSet<>(snapshot());
        updated.remove(recipeId.toString());
        excluded = Set.copyOf(updated);
        return true;
    }

    public static synchronized void invalidate() {
        excluded = null;
    }

    /** JEI 会话内 identityHashCode 生成的 pseudo-ID 不能进入服务端偏好。 */
    public static boolean isStableRecipeId(ResourceLocation recipeId) {
        return recipeId != null && !recipeId.getPath().startsWith("rsi_bookmark/");
    }

    private static Set<String> snapshot() {
        if (excluded == null) {
            excluded = Set.copyOf(
                    CraftingPlanPreferences.load(preferencesPath()).excludedRecipes());
        }
        return excluded;
    }

    private static Path preferencesPath() {
        return FMLPaths.CONFIGDIR.get()
                .resolve("rs_integration").resolve("crafting_plan.json");
    }
}
