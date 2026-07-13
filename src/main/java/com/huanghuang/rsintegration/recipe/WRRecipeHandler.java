package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class WRRecipeHandler extends AbstractRecipeHandler {

    static {
        registerRecipePrefixes(WRRecipeHandler.class, "mod.maxbogomol.wizards_reborn.");
    }

    @Override
    public ModType modType() { return ModType.byId("wizards_reborn"); }

    @Override
    public ItemStack getResultItem(Recipe<?> recipe, RegistryAccess access) {
        // CrystalRitualRecipe.getResultItem(RegistryAccess) returns EMPTY and
        // getResultItem() returns RUNIC_PEDESTAL (the machine block icon).
        // Crystal rituals produce runtime-determined effects (breeding,
        // fertility, infusion), not statically-declared items.  Returning
        // EMPTY skips them in RecipeIndex (no corruption) and avoids the
        // plan builder showing the machine block as the output.
        String className = recipe.getClass().getName();
        if (className.endsWith("CrystalRitualRecipe")) return ItemStack.EMPTY;

        // ArcaneIteratorRecipe enchantment recipes declare no "output" in their
        // JSON, so getResultItem()/assemble()/field-scan all return EMPTY. The
        // enchanted book is built at runtime by the block entity from the
        // recipe's `enchantment` field (new ENCHANTED_BOOK + addEnchantment at
        // min level). Reconstruct it here so the plan builder doesn't reject the
        // recipe as "unsupported machine" and the UI shows the real book icon.
        if (className.endsWith("ArcaneIteratorRecipe")) {
            ItemStack book = buildEnchantedBookOutput(recipe);
            if (!book.isEmpty()) return book;
            // Not an enchantment recipe (e.g. arcanum_lens declares a static
            // "output") — fall through to the standard probe chain below.
        }

        // Probe getResult/getOutput/getOutputCopy/getAssembledItem first —
        // these are the canonical output methods that don't delegate to
        // the deprecated 0-arg getResultItem() which WR abuses to return
        // machine block icons.  Fall back to getResultItem last because
        // some recipes (e.g. ArcaneIteratorRecipe) only expose output
        // through it.
        for (String name : new String[]{"getResult", "getOutput", "getOutputCopy", "getAssembledItem", "getResultItem"}) {
            for (java.lang.reflect.Method m : recipe.getClass().getMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!ItemStack.class.isAssignableFrom(m.getReturnType())) continue;
                if (m.getParameterCount() == 1) {
                    try {
                        Object r = m.invoke(recipe, access);
                        if (r instanceof ItemStack s && !s.isEmpty()) return s;
                    } catch (Exception e) {
                        RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
                    }
                } else if (m.getParameterCount() == 0) {
                    try {
                        Object r = m.invoke(recipe);
                        if (r instanceof ItemStack s && !s.isEmpty()) return s;
                    } catch (Exception e) {
                        RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
                    }
                }
            }
        }

        // Field-scanning fallback: some WR recipes (e.g. ArcaneIteratorRecipe)
        // may not expose output through the standard methods at runtime if
        // Forge SRG→MCP remapping hasn't been applied yet or the recipe uses
        // a different output mechanism.  Scan for common output field names.
        return scanOutputField(recipe);
    }

    private static ItemStack scanOutputField(Object recipe) {
        Class<?> scan = recipe.getClass();
        while (scan != null && scan != Object.class) {
            for (java.lang.reflect.Field f : scan.getDeclaredFields()) {
                if (!ItemStack.class.isAssignableFrom(f.getType())) continue;
                String fn = f.getName();
                if (fn.equals("output") || fn.equals("result") || fn.equals("resultItem")) {
                    f.setAccessible(true);
                    try {
                        ItemStack s = (ItemStack) f.get(recipe);
                        if (s != null && !s.isEmpty()) return s.copy();
                    } catch (Exception e) {
                        RSIntegrationMod.LOGGER.debug("[RSI-Recipe] reflection probe failed", e);
                    }
                }
            }
            scan = scan.getSuperclass();
        }
        return ItemStack.EMPTY;
    }

    /**
     * Reconstruct the enchanted-book output for an ArcaneIteratorRecipe whose
     * JSON declares no static {@code output}. The block entity builds this at
     * runtime as {@code new ItemStack(Items.ENCHANTED_BOOK)} enchanted with the
     * recipe's {@code enchantment} at (canEnchantBook + 1); for a fresh book
     * that lands on the enchantment's minimum level. Handles both vanilla
     * {@link Enchantment} recipes and WR arcane-enchantment recipes (via WR's
     * own {@code ArcaneEnchantmentUtil}, reflected because it is addon API).
     *
     * @return the enchanted book, or EMPTY if the recipe carries no enchantment
     *         (e.g. arcanum_lens, which declares a static output instead).
     */
    private static ItemStack buildEnchantedBookOutput(Recipe<?> recipe) {
        // The JEI/plan icon shows the recipe's minimum-level book. The block
        // entity computes the real level at craft time (input level + 1), so
        // the min-level book is the correct display for a fresh first craft.
        return buildEnchantedBookOutput(recipe, -1);
    }

    /**
     * Build the enchanted-book output for an ArcaneIteratorRecipe at a specific
     * level. Handles vanilla {@link Enchantment} recipes and WR arcane-enchantment
     * recipes (via WR's own {@code ArcaneEnchantmentUtil}, reflected because it is
     * addon API).
     *
     * @param level the enchantment level to stamp, or a negative value to use the
     *              enchantment's minimum level (vanilla) / level 1 (arcane) — i.e.
     *              the level the machine produces from a fresh, unenchanted book.
     * @return the enchanted book, or EMPTY if the recipe carries no enchantment
     *         (e.g. arcanum_lens, which declares a static output instead).
     */
    public static ItemStack buildEnchantedBookOutput(Recipe<?> recipe, int level) {
        try {
            // Vanilla enchantment path: matches the block entity's
            // EnchantedBookItem.addEnchantment(book, EnchantmentInstance).
            if (invokeBool(recipe, "hasEnchantment")) {
                Object ench = invoke(recipe, "getEnchantment");
                if (ench instanceof Enchantment enchantment) {
                    int lvl = level >= 1 ? level : enchantment.getMinLevel();
                    ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                    EnchantedBookItem.addEnchantment(book,
                            new EnchantmentInstance(enchantment, lvl));
                    return book;
                }
            }
            // Arcane enchantment path: the block entity calls
            // ArcaneEnchantmentUtil.addItemArcaneEnchantment(book, arcaneEnch),
            // which stamps (current level + 1) → level 1 for a fresh book.
            // addArcaneEnchantment(book, ench, lvl) sets an exact level (the
            // 3-arg variant writes the level straight into NBT, not additive).
            if (invokeBool(recipe, "hasArcaneEnchantment")) {
                Object arcaneEnch = invoke(recipe, "getArcaneEnchantment");
                if (arcaneEnch != null) {
                    ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
                    Class<?> arcaneEnchClass = Class.forName(
                            "mod.maxbogomol.wizards_reborn.api.arcaneenchantment.ArcaneEnchantment");
                    Class<?> util = Class.forName(
                            "mod.maxbogomol.wizards_reborn.api.arcaneenchantment.ArcaneEnchantmentUtil");
                    if (level >= 1) {
                        util.getMethod("addArcaneEnchantment", ItemStack.class, arcaneEnchClass, int.class)
                                .invoke(null, book, arcaneEnch, level);
                    } else {
                        util.getMethod("addItemArcaneEnchantment", ItemStack.class, arcaneEnchClass)
                                .invoke(null, book, arcaneEnch);
                    }
                    return book;
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Recipe] Failed to build ArcaneIterator book output", e);
        }
        return ItemStack.EMPTY;
    }

    /**
     * Infer the target enchantment level the player clicked, given an
     * ArcaneIteratorRecipe and the concrete output {@link ItemStack} JEI rendered
     * in the recipe's output slot (e.g. "Curse of Withering II"). Because levels
     * I/II/III share one recipe id and declare no static output, the level is only
     * discoverable by comparing the clicked stack against books this handler builds
     * at each candidate level — reusing the exact same construction path so there
     * is no drift between what we require and what the machine produces.
     *
     * @return the matched level (>= 1), or 1 when no match is found (safe default:
     *         plain-book center → level I, the pre-existing behavior).
     */
    public static int inferTargetLevel(Recipe<?> recipe, @Nullable ItemStack clicked) {
        if (clicked == null || clicked.isEmpty()) return 1;
        // WR enchant books cap well below this; 10 is a generous scan ceiling that
        // covers every vanilla/arcane enchantment without risking a runaway loop.
        for (int lvl = 1; lvl <= 10; lvl++) {
            ItemStack candidate = buildEnchantedBookOutput(recipe, lvl);
            if (candidate.isEmpty()) break; // not an enchant recipe → no leveling
            if (ItemStack.isSameItemSameTags(candidate, clicked)) return lvl;
        }
        return 1;
    }

    private static boolean invokeBool(Object target, String method) throws Exception {
        java.lang.reflect.Method m = Reflect.findMethod(target.getClass(), method, new Class<?>[0]);
        return m != null && (boolean) m.invoke(target);
    }

    @Nullable
    private static Object invoke(Object target, String method) throws Exception {
        java.lang.reflect.Method m = Reflect.findMethod(target.getClass(), method, new Class<?>[0]);
        return m != null ? m.invoke(target) : null;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public List<IngredientSpec> getIngredients(Recipe<?> recipe) {
        // WR uses the standard extractIngredients probe chain
        List<Ingredient> ingredients = CraftPacketUtils.extractIngredients(recipe);
        if (ingredients == null || ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        return specs.isEmpty() ? null : specs;
    }
}
