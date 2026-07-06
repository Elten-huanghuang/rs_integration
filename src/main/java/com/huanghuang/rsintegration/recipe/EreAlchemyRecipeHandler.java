package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class EreAlchemyRecipeHandler extends AbstractRecipeHandler {

    static {
        registerRecipePrefixes(EreAlchemyRecipeHandler.class, "com.rekindled.embers.");
    }

    @Override
    public ModType modType() { return ModType.byId(ModIds.ID_EMBERS_ALCHEMY); }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // AlchemyRecipe has a public final 'output' ItemStack field.
        // Some other Embers recipe classes (e.g. stamping) may have an
        // 'output' field of type Either<ItemStack, Ingredient> — guard it.
        return Reflect.getField(recipe, "output")
                .filter(o -> o instanceof ItemStack)
                .map(o -> (ItemStack) o)
                .filter(s -> !s.isEmpty())
                .map(ItemStack::copy)
                .orElse(ItemStack.EMPTY);
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();

        // tablet — center Ingredient
        Reflect.findField(recipe.getClass(), "tablet").ifPresent(f -> {
            if (!Ingredient.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                Ingredient ing = (Ingredient) f.get(recipe);
                if (ing != null && !ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });

        // aspects — ArrayList<Ingredient> (element items, M aspects)
        Reflect.findField(recipe.getClass(), "aspects").ifPresent(f -> {
            if (!List.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                List<Ingredient> aspects = (List<Ingredient>) f.get(recipe);
                if (aspects != null) {
                    for (Ingredient ing : aspects) {
                        if (ing != null && !ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });

        // inputs — ArrayList<Ingredient> (material items, N pedestals)
        Reflect.findField(recipe.getClass(), "inputs").ifPresent(f -> {
            if (!List.class.isAssignableFrom(f.getType())) return;
            f.setAccessible(true);
            try {
                List<Ingredient> inputs = (List<Ingredient>) f.get(recipe);
                if (inputs != null) {
                    for (Ingredient ing : inputs) {
                        if (ing != null && !ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
                    }
                }
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI] Reflection probe failed", e); }
        });

        return specs.isEmpty() ? null : specs;
    }
}
