package com.huanghuang.rsintegration.mods.ironsspellbooks;

import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.api.spells.ISpellContainer;
import io.redspace.ironsspellbooks.item.InkItem;
import io.redspace.ironsspellbooks.jei.ArcaneAnvilRecipe;
import io.redspace.ironsspellbooks.registries.ItemRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime recipes for the JEI-only Scroll Forge and Arcane Anvil categories. */
public final class IronSpellBooksRecipeCatalog {
    public static final RecipeType<IronSpellBooksRecipe> TYPE =
            RecipeType.simple(new ResourceLocation("rs_integration", "irons_spellbooks"));
    private static volatile Catalog catalog;

    private IronSpellBooksRecipeCatalog() {}

    public static Collection<IronSpellBooksRecipe> allRecipes() { return catalog().byId().values(); }

    @Nullable
    public static IronSpellBooksRecipe byId(ResourceLocation id) {
        if (!"rs_integration".equals(id.getNamespace())
                || !id.getPath().startsWith("irons_spellbooks/")) return null;
        return catalog().byId().get(id);
    }

    @Nullable
    public static IronSpellBooksRecipe recipeForTarget(IronSpellBooksRecipe.Machine machine,
                                                        ItemStack target) {
        if (target == null || target.isEmpty()) return null;
        return catalog().byOutput().get(OutputKey.of(machine, target));
    }

    public static void invalidate() {
        synchronized (IronSpellBooksRecipeCatalog.class) {
            catalog = null;
        }
    }

    private static Catalog catalog() {
        Catalog cached = catalog;
        if (cached != null) return cached;
        synchronized (IronSpellBooksRecipeCatalog.class) {
            cached = catalog;
            if (cached == null) catalog = cached = build();
        }
        return cached;
    }

    private static Catalog build() {
        Map<ResourceLocation, IronSpellBooksRecipe> result = new LinkedHashMap<>();
        addScrollForgeRecipes(result, findFocuses());
        addArcaneAnvilRecipes(result);
        Map<OutputKey, IronSpellBooksRecipe> byOutput = new HashMap<>();
        for (IronSpellBooksRecipe recipe : result.values()) {
            ItemStack output = recipe.getResultItem(net.minecraft.core.RegistryAccess.EMPTY);
            if (!output.isEmpty()) byOutput.putIfAbsent(OutputKey.of(recipe.machine(), output), recipe);
        }
        return new Catalog(Collections.unmodifiableMap(result), Map.copyOf(byOutput));
    }

    private static void addScrollForgeRecipes(Map<ResourceLocation, IronSpellBooksRecipe> result,
                                               Map<Object, ItemStack> focuses) {
        for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
            if (spell == SpellRegistry.none() || !spell.allowCrafting()) continue;
            int level = spell.getMinLevel();
            InkItem ink = InkItem.getInkForRarity(spell.getRarity(level));
            ItemStack focus = focuses.getOrDefault(spell.getSchoolType(), ItemStack.EMPTY);
            if (ink == null || focus.isEmpty()) continue;
            ItemStack output = scroll(spell, level);
            ResourceLocation id = id("scroll_forge", spell.getSpellResource(), level, null);
            result.put(id, new IronSpellBooksRecipe(id, IronSpellBooksRecipe.Machine.SCROLL_FORGE,
                    List.of(new ItemStack(ink), new ItemStack(Items.PAPER), focus), output,
                    spell.getSpellId()));
        }
    }

    private static void addArcaneAnvilRecipes(Map<ResourceLocation, IronSpellBooksRecipe> result) {
        for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
            for (int level = spell.getMinLevel(); level < spell.getMaxLevel(); level++) {
                ArcaneAnvilRecipe jei = new ArcaneAnvilRecipe(spell, level);
                var tuple = jei.getRecipeItems();
                if (tuple.a().isEmpty() || tuple.b().isEmpty() || tuple.c().isEmpty()) continue;
                ItemStack left = tuple.a().get(0);
                ItemStack right = tuple.b().get(0);
                ItemStack output = tuple.c().get(0);
                ResourceLocation id = id("arcane_anvil/scroll_upgrade", spell.getSpellResource(), level + 1, null);
                result.put(id, new IronSpellBooksRecipe(id, IronSpellBooksRecipe.Machine.ARCANE_ANVIL,
                        List.of(left, right), output, spell.getSpellId()));
            }
        }
    }

    private static ItemStack scroll(AbstractSpell spell, int level) {
        ItemStack stack = new ItemStack(ItemRegistry.SCROLL.get());
        ISpellContainer.createScrollContainer(spell, level, stack);
        return stack;
    }

    private static Map<Object, ItemStack> findFocuses() {
        Map<Object, ItemStack> focuses = new IdentityHashMap<>();
        for (var item : ForgeRegistries.ITEMS.getValues()) {
            ItemStack stack = new ItemStack(item);
            Object school = io.redspace.ironsspellbooks.api.registry.SchoolRegistry.getSchoolFromFocus(stack);
            if (school != null) focuses.putIfAbsent(school, stack);
        }
        return focuses;
    }

    private static ResourceLocation id(String kind, ResourceLocation spell, int level,
                                       @Nullable ResourceLocation material) {
        String path = "irons_spellbooks/" + kind + "/" + spell.getNamespace() + "/"
                + spell.getPath() + "/" + level;
        if (material != null) path += "/" + material.getNamespace() + "/" + material.getPath();
        return new ResourceLocation("rs_integration", path);
    }

    private record Catalog(Map<ResourceLocation, IronSpellBooksRecipe> byId,
                           Map<OutputKey, IronSpellBooksRecipe> byOutput) {}

    private record OutputKey(IronSpellBooksRecipe.Machine machine, ResourceLocation itemId,
                             @Nullable net.minecraft.nbt.CompoundTag tag) {
        private static OutputKey of(IronSpellBooksRecipe.Machine machine, ItemStack stack) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
            return new OutputKey(machine, itemId, stack.hasTag() ? stack.getTag().copy() : null);
        }
    }
}
