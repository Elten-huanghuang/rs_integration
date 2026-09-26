package com.huanghuang.rsintegration.mods.vanilla;
import java.lang.reflect.Field;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.ISmithingRecipeAccessor;
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
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import java.util.Comparator;
import java.util.function.Predicate;
import java.util.Map;
import java.util.stream.Collectors;

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

    /**
     * Smithing slots are positional.  CraftTweaker exposes a generic ingredient
     * list for some smithing recipe implementations, but that list is not a
     * reliable source of the vanilla template/base/addition order.  Always use
     * this handler before the generic CraftTweaker probes.
     */
    @Override
    public boolean preferHandlerIngredients() {
        return true;
    }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        return recipe.getResultItem(access);
    }

    @Override
    public boolean useClickedPrimaryOutput(Recipe<?> recipe, ItemStack declared, ItemStack clicked) {
        return clicked.hasTag() || ModRecipeHandler.super.useClickedPrimaryOutput(recipe, declared, clicked);
    }

    @Override
    public boolean hasRuntimeDependentPrimaryNbt(Recipe<?> recipe) {
        return recipe instanceof SmithingTransformRecipe;
    }

    @Override
    public boolean supportsBackgroundPlanning(Recipe<?> recipe) {
        // SmithingTransformRecipe copies the selected base tag into its output.
        // ImmutableRecipeGraphProjector binds that state from the snapshot before
        // recursive search, so this is deterministic despite runtime-dependent NBT.
        return recipe instanceof SmithingTransformRecipe;
    }

    public static ItemStack selectAvailableBase(SmithingTransformRecipe recipe,
                                                Map<CraftingResolver.StackKey, Integer> available,
                                                int needed) {
        return available.entrySet().stream()
                .filter(entry -> entry.getValue() >= needed)
                .map(entry -> entry.getKey().toStack())
                .filter(recipe::isBaseIngredient)
                .sorted(Comparator
                        .comparing((ItemStack stack) -> stack.hasTag())
                        .thenComparing(stack -> String.valueOf(stack.getTag())))
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    public static ItemStack assembleTransform(SmithingTransformRecipe recipe,
                                              List<ItemStack> extracted,
                                              RegistryAccess access) {
        List<ItemStack> pool = extracted.stream().map(ItemStack::copy)
                .collect(Collectors.toCollection(ArrayList::new));
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

    /**
     * SmithingTransformRecipe copies the base stack's tag into its output.
     * When a downstream recipe requests a tagged output (for example an
     * Unbreakable sword), carry that requirement into the base ingredient so
     * recursive planning cannot silently choose a tagless upgrade.
     */
    public static List<IngredientSpec> requireDemandedOutputTag(
            SmithingTransformRecipe recipe, List<IngredientSpec> specs,
            @Nullable ItemStack demandedOutput) {
        if (demandedOutput == null || demandedOutput.isEmpty()
                || !demandedOutput.hasTag() || specs == null || specs.size() < 2) {
            return specs;
        }
        IngredientSpec original = specs.get(1);
        if (original == null || original.isEmpty()) return specs;
        ItemStack[] bases = original.ingredient().getItems();
        for (ItemStack base : bases) {
            if (base == null || base.isEmpty()) continue;
            ItemStack taggedBase = base.copyWithCount(1);
            taggedBase.setTag(demandedOutput.getTag().copy());
            return requireExactBase(recipe, specs, taggedBase);
        }
        return specs;
    }

    private static ItemStack first(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack takeMatching(List<ItemStack> pool,
                                          Predicate<ItemStack> predicate) {
        for (ItemStack stack : pool) {
            if (!stack.isEmpty() && predicate.test(stack)) return stack.split(1);
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        if (recipe instanceof ISmithingRecipeAccessor accessor) {
            return List.of(
                    new IngredientSpec(accessor.rsi$getTemplate(), 1),
                    new IngredientSpec(accessor.rsi$getBase(), 1),
                    new IngredientSpec(accessor.rsi$getAddition(), 1));
        }

        List<Ingredient> ingredients = new ArrayList<>();
        Class<?> clazz = recipe.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field f : clazz.getDeclaredFields()) {
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
