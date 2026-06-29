package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

final class GoetyRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.GOETY; }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        if (!recipe.getClass().getName().startsWith("com.Polarice3.Goety."))
            return false;

        // Filter out rituals that can't be automated safely:
        // ConvertRitual (converts mobs), TeleportRitual (teleports players).
        // SummonRitual is allowed — spawns entities.
        var ritualOpt = Reflect.invoke(recipe, "getRitual");
        if (ritualOpt.isPresent()) {
            String name = ritualOpt.get().getClass().getSimpleName();
            if (name.equals("ConvertRitual")
                    || name.equals("TeleportRitual")) {
                return false;
            }
        }

        // Filter out sacrificial rituals — auto-craft cannot perform entity sacrifices.
        // Even CraftItemRitual/EnchantItemRitual can require sacrifices.
        try {
            if ((boolean) recipe.getClass().getMethod("requiresSacrifice").invoke(recipe)) {
                return false;
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Goety] requiresSacrifice probe failed", e); }

        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // Try standard getResultItem first (may work on some subclasses)
        ItemStack result = recipe.getResultItem(access);
        if (!result.isEmpty()) return result;

        // Try to find output through the ritual object
        try {
            var ritualObj = Class.forName("com.Polarice3.Goety.common.crafting.RitualRecipe")
                    .getMethod("getRitual").invoke(recipe);
            if (ritualObj != null) {
                // Ritual has getOutput() → ItemStack
                for (var m : ritualObj.getClass().getMethods()) {
                    if ((m.getName().equals("getOutput") || m.getName().equals("getResult"))
                            && ItemStack.class.isAssignableFrom(m.getReturnType())
                            && m.getParameterCount() == 0) {
                        ItemStack s = (ItemStack) m.invoke(ritualObj);
                        if (!s.isEmpty()) return s;
                    }
                }
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Goety] Ritual output probe failed", e); }

        // Scan fields for an output ItemStack
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (var f : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try {
                    ItemStack s = (ItemStack) f.get(recipe);
                    if (s != null && !s.isEmpty()) return s.copy();
                } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Goety] Field probe {} failed", f.getName(), e); }
            }
            scan = scan.getSuperclass();
        }

        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // Use vanilla getIngredients() — RitualRecipe extends Recipe so this always works.
        // getIngredientsList() may not exist in all Goety versions.
        var ingredients = recipe.getIngredients();
        List<IngredientSpec> result = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) {
                result.add(new IngredientSpec(ing, 1));
            }
        }

        // Include the activation item (scroll/wand) so it appears in the
        // crafting plan tree and is accounted for during material reservation.
        try {
            var act = Reflect.invoke(recipe, "getActivationItem");
            if (act.isPresent() && act.get() instanceof Ingredient aing && !aing.isEmpty()) {
                result.add(new IngredientSpec(aing, 1));
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Goety] getActivationItem probe failed", e); }

        return result.isEmpty() ? null : result;
    }
}
