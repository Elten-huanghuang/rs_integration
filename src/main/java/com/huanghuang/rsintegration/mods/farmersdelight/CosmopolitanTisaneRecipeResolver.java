package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.compat.jei.JeiRecipeIdNormalizer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Rebuilds Cosmopolitan's JEI-only tisane recipes on the logical server. */
public final class CosmopolitanTisaneRecipeResolver {

    private static final String MOD_ID = "cosmopolitan";
    private static final String MAKER_CLASS =
            "com.gumillea.cosmopolitan.core.util.jei.TisaneRecipeMaker";

    private static volatile Map<ResourceLocation, Recipe<?>> recipes = Map.of();
    private static volatile boolean initialized;

    private CosmopolitanTisaneRecipeResolver() {}

    @Nullable
    public static Recipe<?> resolve(ResourceLocation recipeId) {
        if (!isSupportedId(recipeId) || !ModList.get().isLoaded(MOD_ID)) return null;
        ensureInitialized();
        return recipes.get(recipeId);
    }

    public static boolean isSupportedId(@Nullable ResourceLocation recipeId) {
        return JeiRecipeIdNormalizer.isCosmopolitanTisane(recipeId);
    }

    private static void ensureInitialized() {
        if (initialized) return;
        synchronized (CosmopolitanTisaneRecipeResolver.class) {
            if (initialized) return;
            Map<ResourceLocation, Recipe<?>> rebuilt = new LinkedHashMap<>();
            try {
                Class<?> maker = Class.forName(MAKER_CLASS);
                Method createRecipes = maker.getMethod("createRecipes");
                Object value = createRecipes.invoke(null);
                if (value instanceof Iterable<?> entries) {
                    for (Object entry : entries) {
                        if (entry instanceof Recipe<?> recipe && isSupportedId(recipe.getId())) {
                            rebuilt.put(recipe.getId(), recipe);
                        }
                    }
                }
                RSIntegrationMod.LOGGER.info(
                        "[RSI-Cosmopolitan] Rebuilt {} dynamic tisane recipes for server crafting",
                        rebuilt.size());
            } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Cosmopolitan] Failed to rebuild dynamic tisane recipes", exception);
            }
            recipes = Map.copyOf(rebuilt);
            initialized = true;
        }
    }
}
