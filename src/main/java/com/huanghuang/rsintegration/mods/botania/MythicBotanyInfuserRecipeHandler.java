package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Optional, classloader-safe recipe projection for MythicBotany's Mana Infuser. */
final class MythicBotanyInfuserRecipeHandler implements ModRecipeHandler {
    static final ResourceLocation SERIALIZER_ID = new ResourceLocation("mythicbotany", "infuser");
    private static final String RECIPE_CLASS = "mythicbotany.infuser.InfuserRecipe";

    @Override
    public @Nonnull ModType modType() {
        return ModType.byId("mythicbotany_mana_infuser");
    }

    @Override
    public boolean canHandle(@Nonnull Recipe<?> recipe) {
        ResourceLocation serializerId = BuiltInRegistries.RECIPE_SERIALIZER.getKey(recipe.getSerializer());
        return isInfuserSerializerId(serializerId) || isInfuserRecipeClass(recipe.getClass());
    }

    static boolean isInfuserSerializerId(@Nullable ResourceLocation serializerId) {
        return SERIALIZER_ID.equals(serializerId);
    }

    static boolean isInfuserRecipeClass(Class<?> recipeClass) {
        for (Class<?> current = recipeClass;
             current != null && current != Object.class;
             current = current.getSuperclass()) {
            if (isInfuserRecipeClassName(current.getName())) return true;
        }
        return false;
    }

    static boolean isInfuserRecipeClassName(String className) {
        return RECIPE_CLASS.equals(className);
    }

    @Override
    public @Nonnull ItemStack getResultItem(@Nonnull Recipe<?> recipe,
                                             @Nonnull RegistryAccess access) {
        ItemStack output = recipe.getResultItem(access);
        return output == null ? ItemStack.EMPTY : output.copy();
    }

    @Override
    public @Nullable List<IngredientSpec> getIngredients(@Nonnull Recipe<?> recipe) {
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1));
        }
        return specs.isEmpty() ? null : specs;
    }
}
