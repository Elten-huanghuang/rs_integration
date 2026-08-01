package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.SmithingTrimRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.SimpleContainer;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class SmithingRecipeHandler implements ModRecipeHandler {

    @Override
    public ModType modType() { return ModType.byId("smithing"); }

    @Override
    public boolean canHandle(Recipe<?> recipe) {
        return recipe instanceof SmithingTransformRecipe
                || recipe instanceof SmithingTrimRecipe;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access);
    }

    public static ItemStack selectAvailableBase(SmithingTransformRecipe recipe,
                                                java.util.Map<com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey, Integer> available,
                                                int needed) {
        return available.entrySet().stream()
                .filter(entry -> entry.getValue() >= needed)
                .map(entry -> entry.getKey().toStack())
                .filter(recipe::isBaseIngredient)
                .sorted(java.util.Comparator
                        .comparing((ItemStack stack) -> stack.hasTag())
                        .thenComparing(stack -> String.valueOf(stack.getTag())))
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    public static ItemStack assembleTransform(SmithingTransformRecipe recipe,
                                              List<ItemStack> extracted,
                                              RegistryAccess access) {
        List<ItemStack> pool = extracted.stream().map(ItemStack::copy)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        ItemStack template = takeMatching(pool, recipe::isTemplateIngredient);
        ItemStack base = takeMatching(pool, recipe::isBaseIngredient);
        ItemStack addition = takeMatching(pool, recipe::isAdditionIngredient);
        if (template.isEmpty() || base.isEmpty() || addition.isEmpty()) return ItemStack.EMPTY;
        return recipe.assemble(new SimpleContainer(template, base, addition), access);
    }

    public static ItemStack assembleWithBase(SmithingTransformRecipe recipe,
                                             ItemStack base, RegistryAccess access) {
        List<IngredientSpec> specs = new SmithingRecipeHandler().getIngredients(recipe);
        if (specs == null || specs.size() < 3 || base.isEmpty()) return ItemStack.EMPTY;
        List<ItemStack> inputs = new ArrayList<>(3);
        inputs.add(first(specs.get(0).ingredient()));
        inputs.add(base.copyWithCount(1));
        inputs.add(first(specs.get(2).ingredient()));
        return assembleTransform(recipe, inputs, access);
    }

    public static List<IngredientSpec> requireExactBase(SmithingTransformRecipe recipe,
                                                        List<IngredientSpec> specs,
                                                        @Nullable ItemStack base) {
        if (base == null || base.isEmpty() || !recipe.isBaseIngredient(base)
                || specs.size() < 2) return specs;
        List<IngredientSpec> exact = new ArrayList<>(specs);
        IngredientSpec original = exact.get(1);
        exact.set(1, new IngredientSpec(StrictNBTIngredient.of(base.copyWithCount(1)),
                original.count(), original.role()));
        return List.copyOf(exact);
    }

    private static ItemStack first(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack takeMatching(List<ItemStack> pool,
                                          java.util.function.Predicate<ItemStack> predicate) {
        for (ItemStack stack : pool) {
            if (!stack.isEmpty() && predicate.test(stack)) return stack.split(1);
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        List<Ingredient> ingredients = new ArrayList<>();
        Class<?> clazz = recipe.getClass();
        while (clazz != null && clazz != Object.class) {
            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                if (f.getType() == Ingredient.class) {
                    f.setAccessible(true);
                    try {
                        Ingredient ing = (Ingredient) f.get(recipe);
                        if (ing != null && !ing.isEmpty()) ingredients.add(ing);
                    } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Smithing] field access failed", e); }
                }
            }
            clazz = clazz.getSuperclass();
        }
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }
}
