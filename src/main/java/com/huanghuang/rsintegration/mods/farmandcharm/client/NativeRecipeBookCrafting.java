package com.huanghuang.rsintegration.mods.farmandcharm.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressTracker;
import com.huanghuang.rsintegration.crafting.planning.PlanningRequestIds;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;

import java.util.Map;
import java.util.Set;

/** Sends a native Let's Do recipe-book selection through RSI's plan flow. */
public final class NativeRecipeBookCrafting {
    private static final String COOKING_POT_RECIPE_CLASS =
            "net.satisfy.farm_and_charm.recipe.CookingPotRecipe";
    private static final String ROASTER_RECIPE_CLASS =
            "net.satisfy.farm_and_charm.recipe.RoasterRecipe";
    private static final Set<String> SUPPORTED_RECIPE_CLASSES = Set.of(
            COOKING_POT_RECIPE_CLASS, ROASTER_RECIPE_CLASS);

    private NativeRecipeBookCrafting() {}

    public static boolean isSupportedRecipe(Recipe<?> recipe) {
        return recipe != null && isSupportedRecipeClass(recipe.getClass().getName());
    }

    static boolean isSupportedRecipeClass(String className) {
        return className != null && SUPPORTED_RECIPE_CLASSES.contains(className);
    }

    /** Starts the same preview used by the JEI/EMI craft buttons. */
    public static void request(Recipe<?> recipe) {
        if (!isSupportedRecipe(recipe)) return;
        ResourceLocation recipeId = recipe.getId();
        if (recipeId == null) return;

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;

        long requestId = PlanningRequestIds.next();
        PlanningProgressTracker.start(requestId, recipeId);
        BatchCraftNetworkHandler.CHANNEL.sendToServer(new GenericCraftPacket(
                recipeId, true, Map.of(), null, null, 1, false,
                null, null, requestId));
        RSIntegrationMod.LOGGER.debug(
                "[RSI-LetsDo] Native recipe-book craft requested: recipe={}", recipeId);
    }

    public static void tellSelectionRequired() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.sendSystemMessage(Component.translatable(
                    "gui.rs_integration.native_recipe_book_craft.select_recipe"));
        }
    }
}
