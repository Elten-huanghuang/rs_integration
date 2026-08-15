package com.huanghuang.rsintegration.mods.arsnouveau;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Resolves the concrete centre item and output for Ars' NBT-transforming recipes. */
public final class ArsDynamicApparatusRecipe {
    private ArsDynamicApparatusRecipe() {}

    public static boolean isSupported(Recipe<?> recipe) {
        return recipe != null && ArsRecipeClassifier.isDynamicApparatus(
                ArsTileAccess.recipeTypeId(recipe));
    }

    /**
     * Derives the exact centre item represented by Ars' JEI output. Enchantment
     * recipes render books, while armor upgrades render the selected armor with
     * its tier advanced by one.
     */
    public static ItemStack deriveInput(Recipe<?> recipe, @Nullable ItemStack requestedOutput) {
        if (recipe == null) {
            return ItemStack.EMPTY;
        }
        String typeId = ArsTileAccess.recipeTypeId(recipe);
        if (ArsRecipeClassifier.TYPE_ENCHANTMENT.equals(typeId)) {
            int level = Reflect.<Integer>getField(recipe, "enchantLevel").orElse(0);
            Enchantment enchantment = Reflect.<Enchantment>getField(recipe, "enchantment").orElse(null);
            if (level < 1 || enchantment == null) return ItemStack.EMPTY;
            return buildEnchantmentInput(enchantment, level);
        }
        if (ArsRecipeClassifier.TYPE_ARMOR_UPGRADE.equals(typeId)) {
            if (requestedOutput == null || requestedOutput.isEmpty()) return ItemStack.EMPTY;
            int tier = Reflect.<Integer>getField(recipe, "tier").orElse(-1);
            if (tier < 1) return ItemStack.EMPTY;
            ItemStack input = requestedOutput.copyWithCount(1);
            if (!setArmorTier(input, tier - 1)) return ItemStack.EMPTY;
            if (tier == 1) stripDefaultTierZeroPerkData(input);
            return input;
        }
        return ItemStack.EMPTY;
    }

    /** Recomputes the recipe output through Ars itself and rejects forged NBT targets. */
    public static ItemStack validatedOutput(Recipe<?> recipe, @Nullable ItemStack requestedOutput) {
        if (recipe != null && ArsRecipeClassifier.TYPE_ENCHANTMENT.equals(
                ArsTileAccess.recipeTypeId(recipe))) {
            ItemStack canonical = canonicalEnchantmentOutput(recipe);
            if (canonical.isEmpty()) return ItemStack.EMPTY;
            if (requestedOutput != null && !requestedOutput.isEmpty()
                    && !ItemStack.isSameItemSameTags(canonical, requestedOutput)) {
                return ItemStack.EMPTY;
            }
            return canonical;
        }
        ItemStack input = deriveInput(recipe, requestedOutput);
        if (input.isEmpty()) return ItemStack.EMPTY;
        ItemStack machineInput = prepareMachineInput(recipe, input, requestedOutput);
        if (machineInput.isEmpty()) return ItemStack.EMPTY;
        ItemStack computed = computeOutput(recipe, machineInput);
        if (computed.isEmpty() || requestedOutput == null
                || !ItemStack.isSameItemSameTags(computed, requestedOutput)) {
            return ItemStack.EMPTY;
        }
        return computed.copyWithCount(1);
    }

    /** Returns the deterministic enchanted-book result used by recipe indexing. */
    public static ItemStack canonicalEnchantmentOutput(Recipe<?> recipe) {
        if (recipe == null || !ArsRecipeClassifier.TYPE_ENCHANTMENT.equals(
                ArsTileAccess.recipeTypeId(recipe))) {
            return ItemStack.EMPTY;
        }
        int level = Reflect.<Integer>getField(recipe, "enchantLevel").orElse(0);
        Enchantment enchantment = Reflect.<Enchantment>getField(recipe, "enchantment").orElse(null);
        if (level < 1 || enchantment == null) return ItemStack.EMPTY;
        return buildEnchantmentOutput(buildEnchantmentInput(enchantment, level), enchantment, level);
    }

    public static List<IngredientSpec> buildMaterials(Recipe<?> recipe,
                                                       @Nullable ItemStack requestedOutput) {
        ItemStack input = deriveInput(recipe, requestedOutput);
        if (input.isEmpty() || validatedOutput(recipe, requestedOutput).isEmpty()) return List.of();

        List<IngredientSpec> specs = new ArrayList<>();
        specs.add(new IngredientSpec(StrictNBTIngredient.of(input), 1, DemandRole.CONSUMED));
        List<Ingredient> pedestalItems = Reflect.<List<Ingredient>>getField(recipe, "pedestalItems")
                .orElse(List.of());
        for (Ingredient ingredient : pedestalItems) {
            if (ingredient != null && !ingredient.isEmpty()) {
                specs.add(new IngredientSpec(ingredient, 1, DemandRole.CONSUMED));
            }
        }
        return List.copyOf(specs);
    }

    /**
     * Converts the canonical RS material into Ars' JEI-compatible reagent.
     * Tier-one armor upgrades consume a normal tier-zero armor stack, while
     * Ars' JEI output carries an explicit empty perk compound. Recreating that
     * transient compound here keeps the material match canonical and the
     * physical result byte-for-byte equal to the requested JEI output.
     */
    public static ItemStack prepareMachineInput(Recipe<?> recipe, ItemStack materialInput,
                                                @Nullable ItemStack requestedOutput) {
        if (recipe == null || materialInput == null || materialInput.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!ArsRecipeClassifier.TYPE_ARMOR_UPGRADE.equals(ArsTileAccess.recipeTypeId(recipe))) {
            return materialInput.copyWithCount(1);
        }
        int tier = Reflect.<Integer>getField(recipe, "tier").orElse(-1);
        if (tier != 1 || requestedOutput == null || requestedOutput.isEmpty()) {
            return materialInput.copyWithCount(1);
        }
        ItemStack machineInput = requestedOutput.copyWithCount(1);
        return setArmorTier(machineInput, 0) ? machineInput : ItemStack.EMPTY;
    }

    private static ItemStack invokeGetResult(Recipe<?> recipe, ItemStack input) {
        try {
            for (Class<?> type = recipe.getClass(); type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().equals("getResult") || method.getParameterCount() != 3
                            || !ItemStack.class.isAssignableFrom(method.getReturnType())) continue;
                    method.setAccessible(true);
                    Object result = method.invoke(recipe, List.of(), input.copy(), null);
                    return result instanceof ItemStack stack ? stack : ItemStack.EMPTY;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-ArsApparatus] Failed to compute dynamic recipe output for {}",
                    recipe.getId(), exception);
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack computeOutput(Recipe<?> recipe, ItemStack input) {
        if (ArsRecipeClassifier.TYPE_ENCHANTMENT.equals(ArsTileAccess.recipeTypeId(recipe))) {
            int level = Reflect.<Integer>getField(recipe, "enchantLevel").orElse(0);
            Enchantment enchantment = Reflect.<Enchantment>getField(recipe, "enchantment").orElse(null);
            if (level < 1 || enchantment == null) return ItemStack.EMPTY;
            return buildEnchantmentOutput(input, enchantment, level);
        }
        return invokeGetResult(recipe, input);
    }

    static ItemStack buildEnchantmentInput(Enchantment enchantment, int level) {
        if (enchantment == null || level < 1) return ItemStack.EMPTY;
        if (level == 1) return new ItemStack(Items.BOOK);
        return EnchantedBookItem.createForEnchantment(
                new EnchantmentInstance(enchantment, level - 1));
    }

    static ItemStack buildEnchantmentOutput(ItemStack input, Enchantment enchantment, int level) {
        if (input == null || input.isEmpty() || enchantment == null || level < 1) {
            return ItemStack.EMPTY;
        }
        ItemStack output = input.is(Items.BOOK)
                ? new ItemStack(Items.ENCHANTED_BOOK) : input.copyWithCount(1);
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(output);
        enchantments.put(enchantment, level);
        EnchantmentHelper.setEnchantments(enchantments, output);
        return output;
    }

    static void stripDefaultTierZeroPerkData(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !stack.hasTag()) return;
        CompoundTag root = stack.getTag();
        if (root == null || !root.contains("an_stack_perks", Tag.TAG_COMPOUND)) return;
        CompoundTag perks = root.getCompound("an_stack_perks");
        if (perks.getInt("tier") != 0
                || !perks.getString("color").isEmpty()
                || !perks.getList("perks", Tag.TAG_COMPOUND).isEmpty()) {
            return;
        }
        for (String key : perks.getAllKeys()) {
            if (!key.equals("tier") && !key.equals("color") && !key.equals("perks")) return;
        }
        root.remove("an_stack_perks");
    }

    private static boolean setArmorTier(ItemStack stack, int tier) {
        try {
            Class<?> perkUtil = Class.forName("com.hollingsworth.arsnouveau.api.util.PerkUtil");
            Method getHolder = null;
            for (Method method : perkUtil.getMethods()) {
                if (method.getName().equals("getPerkHolder") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == ItemStack.class) {
                    getHolder = method;
                    break;
                }
            }
            if (getHolder == null) return false;
            Object holder = getHolder.invoke(null, stack);
            if (holder == null) return false;
            Method setTier = null;
            for (Method method : holder.getClass().getMethods()) {
                if (method.getName().equals("setTier") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0] == int.class) {
                    setTier = method;
                    break;
                }
            }
            if (setTier == null) return false;
            setTier.invoke(holder, tier);
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            RSIntegrationMod.LOGGER.warn("[RSI-ArsApparatus] Failed to derive armor upgrade input", exception);
            return false;
        }
    }
}
