package com.huanghuang.rsintegration.mods.goety;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.recipe.GoetyRecipeHandler;
import com.huanghuang.rsintegration.reflection.probes.GoetyReflection;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves Goety enchant rituals whose JSON only declares a generic book.
 * Goety applies one level to the centre item at runtime, so the concrete
 * clicked output determines the exact NBT-bearing centre input.
 */
public final class GoetyDynamicRitualRecipe {
    private GoetyDynamicRitualRecipe() {}

    public static boolean isSupported(@Nullable Recipe<?> recipe) {
        if (recipe == null || !GoetyRecipeHandler.isRitualRecipe(recipe)) return false;
        Object ritual = Reflect.invoke(recipe, GoetyReflection.M_GET_RITUAL).orElse(null);
        boolean enchantRitual = ritual != null
                && ((GoetyReflection.enchantItemRitualClass != null
                    && GoetyReflection.enchantItemRitualClass.isInstance(ritual))
                    || ritual.getClass().getName().endsWith("EnchantItemRitual"));
        if (!enchantRitual) return false;
        return Reflect.invoke(recipe, GoetyReflection.M_GET_ENCHANTMENT)
                .map(Enchantment.class::isInstance).orElse(false);
    }

    /** Returns the clicked level, or level one when no concrete output was supplied. */
    public static int inferTargetLevel(Recipe<?> recipe, @Nullable ItemStack requestedOutput) {
        if (!isSupported(recipe)) return 0;
        return inferTargetLevel(enchantment(recipe), requestedOutput);
    }

    static int inferTargetLevel(Enchantment enchantment, @Nullable ItemStack requestedOutput) {
        if (enchantment == null) return 0;
        if (requestedOutput == null || requestedOutput.isEmpty()) return 1;
        if (!requestedOutput.is(Items.ENCHANTED_BOOK)) return 0;
        int level = EnchantmentHelper.getEnchantments(requestedOutput)
                .getOrDefault(enchantment, 0);
        return level >= 1 && level <= enchantment.getMaxLevel() ? level : 0;
    }

    /** Rebuilds Goety's deterministic enchanted-book output for a level. */
    public static ItemStack buildOutput(Recipe<?> recipe, int level) {
        if (!isSupported(recipe)) return ItemStack.EMPTY;
        return buildOutput(enchantment(recipe), level);
    }

    static ItemStack buildOutput(Enchantment enchantment, int level) {
        if (enchantment == null) return ItemStack.EMPTY;
        if (level < 1 || level > enchantment.getMaxLevel()) return ItemStack.EMPTY;
        return EnchantedBookItem.createForEnchantment(new EnchantmentInstance(enchantment, level));
    }

    /** Builds the exact centre stack consumed to produce the requested level. */
    public static ItemStack buildInput(Recipe<?> recipe, int level,
                                       @Nullable ItemStack requestedOutput) {
        if (!isSupported(recipe) || level < 1) return ItemStack.EMPTY;
        return buildInput(enchantment(recipe), level, requestedOutput);
    }

    static ItemStack buildInput(Enchantment enchantment, int level,
                                @Nullable ItemStack requestedOutput) {
        if (enchantment == null || level < 1) return ItemStack.EMPTY;
        if (level == 1) return new ItemStack(Items.BOOK);
        if (requestedOutput == null || requestedOutput.isEmpty()
                || !requestedOutput.is(Items.ENCHANTED_BOOK)) return ItemStack.EMPTY;
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(requestedOutput);
        if (enchantments.getOrDefault(enchantment, 0) != level) return ItemStack.EMPTY;
        enchantments.put(enchantment, level - 1);
        ItemStack input = new ItemStack(Items.ENCHANTED_BOOK);
        EnchantmentHelper.setEnchantments(enchantments, input);
        return input;
    }

    /** Validates a JEI target and returns the canonical output selected for execution. */
    public static ItemStack validatedOutput(Recipe<?> recipe, @Nullable ItemStack requestedOutput) {
        int level = inferTargetLevel(recipe, requestedOutput);
        if (level == 0) return ItemStack.EMPTY;
        ItemStack canonical = buildOutput(recipe, level);
        if (requestedOutput == null || requestedOutput.isEmpty()) return canonical;
        // The packet is client supplied. Only accept one of Goety JEI's canonical
        // level outputs, otherwise forged name/lore/extra-enchantment NBT could be
        // mistaken for a result the ritual can guarantee.
        return ItemStack.isSameItemSameTags(canonical, requestedOutput)
                ? canonical : ItemStack.EMPTY;
    }

    /** Material specs in the order expected by GoetyBatchDelegate. */
    public static List<IngredientSpec> buildMaterials(Recipe<?> recipe,
                                                       @Nullable ItemStack requestedOutput) {
        if (!isSupported(recipe)) return List.of();
        int level = inferTargetLevel(recipe, requestedOutput);
        ItemStack centre = buildInput(recipe, level, requestedOutput);
        if (centre.isEmpty()) return List.of();
        List<IngredientSpec> specs = new ArrayList<>();
        specs.add(new IngredientSpec(StrictNBTIngredient.of(centre), 1, DemandRole.CONSUMED));
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (!ingredient.isEmpty()) specs.add(new IngredientSpec(ingredient, 1, DemandRole.CONSUMED));
        }
        return List.copyOf(specs);
    }

    /** Exact activation ingredient used by the altar delegate. */
    public static Ingredient activationIngredient(Recipe<?> recipe,
                                                   @Nullable ItemStack requestedOutput) {
        if (!isSupported(recipe)) return Ingredient.EMPTY;
        int level = inferTargetLevel(recipe, requestedOutput);
        ItemStack centre = buildInput(recipe, level, requestedOutput);
        return centre.isEmpty() ? Ingredient.EMPTY : StrictNBTIngredient.of(centre);
    }

    private static Enchantment enchantment(Recipe<?> recipe) {
        return Reflect.<Enchantment>invoke(recipe, GoetyReflection.M_GET_ENCHANTMENT).orElse(null);
    }
}
