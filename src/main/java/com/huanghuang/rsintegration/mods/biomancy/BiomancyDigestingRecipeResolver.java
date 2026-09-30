package com.huanghuang.rsintegration.mods.biomancy;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;

public final class BiomancyDigestingRecipeResolver {
    private static final String DISPLAY_MARKER = "_jei_";
    private static final String RECIPE_PACKAGE = "com.github.elenterius.biomancy.crafting.recipe.";

    private BiomancyDigestingRecipeResolver() {}

    @Nullable
    public static Recipe<?> resolve(Level level, ResourceLocation id) {
        return resolve(level.getRecipeManager(), level.registryAccess(), id);
    }

    @Nullable
    static Recipe<?> resolve(RecipeManager manager, RegistryAccess access, ResourceLocation id) {
        Recipe<?> direct = manager.byKey(id).orElse(null);
        if (direct != null) return direct;
        ResourceLocation sourceId = sourceId(id);
        if (sourceId == null) return null;
        Recipe<?> source = manager.byKey(sourceId).orElse(null);
        if (source == null) return null;
        try {
            Class<?> foodRecipe = Class.forName(RECIPE_PACKAGE + "FoodDigestingRecipe");
            if (!foodRecipe.isInstance(source)) return null;
            Object ingredient = source.getClass().getMethod("getIngredient").invoke(source);
            if (!(ingredient instanceof Ingredient foods)) return null;
            ItemStack food = findFood(foods, sourceId, id);
            if (food.isEmpty()) return null;
            SimpleContainer input = new SimpleContainer(food.copyWithCount(1));
            ItemStack output = assemble(source, input, access);
            if (output.isEmpty()) return null;
            int duration = ((Number) source.getClass().getMethod("getCraftingTimeTicks", Container.class)
                    .invoke(source, input)).intValue();
            int cost = ((Number) source.getClass().getMethod("getCraftingCostNutrients", Container.class)
                    .invoke(source, input)).intValue();
            Constructor<?> constructor = Class.forName(RECIPE_PACKAGE + "StaticDigestingRecipe")
                    .getConstructor(ResourceLocation.class, ItemStack.class, int.class, int.class, Ingredient.class);
            Object result = constructor.newInstance(id, output.copy(), duration, cost, Ingredient.of(food));
            return result instanceof Recipe<?> recipe ? recipe : null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static ItemStack assemble(Recipe<?> source, Container input, RegistryAccess access) {
        return ((Recipe<Container>) source).assemble(input, access);
    }

    @Nullable
    static ResourceLocation sourceId(ResourceLocation id) {
        String path = id.getPath();
        int marker = path.lastIndexOf(DISPLAY_MARKER);
        if (marker <= 0 || marker + DISPLAY_MARKER.length() >= path.length()) return null;
        return ResourceLocation.tryParse(id.getNamespace() + ":" + path.substring(0, marker));
    }

    static ItemStack findFood(Ingredient foods, ResourceLocation sourceId, ResourceLocation displayId) {
        for (ItemStack food : foods.getItems()) {
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(food.getItem());
            String suffix = itemId.getNamespace() + "." + itemId.getPath();
            if (displayId.getNamespace().equals(sourceId.getNamespace())
                    && displayId.getPath().equals(sourceId.getPath() + DISPLAY_MARKER + suffix)) {
                return food.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }
}
