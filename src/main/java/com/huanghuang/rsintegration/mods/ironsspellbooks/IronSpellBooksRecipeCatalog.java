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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Runtime recipes for the JEI-only Scroll Forge and Arcane Anvil categories. */
public final class IronSpellBooksRecipeCatalog {
    public static final RecipeType<IronSpellBooksRecipe> TYPE =
            RecipeType.simple(new ResourceLocation("rs_integration", "irons_spellbooks"));

    private IronSpellBooksRecipeCatalog() {}

    public static Collection<IronSpellBooksRecipe> allRecipes() { return build().values(); }

    @Nullable
    public static IronSpellBooksRecipe byId(ResourceLocation id) {
        if (!"rs_integration".equals(id.getNamespace())
                || !id.getPath().startsWith("irons_spellbooks/")) return null;
        return build().get(id);
    }

    @Nullable
    public static IronSpellBooksRecipe recipeForTarget(IronSpellBooksRecipe.Machine machine,
                                                        ItemStack target) {
        if (target == null || target.isEmpty()) return null;
        for (IronSpellBooksRecipe recipe : allRecipes()) {
            if (recipe.machine() == machine
                    && ItemStack.isSameItemSameTags(recipe.getResultItem(net.minecraft.core.RegistryAccess.EMPTY), target)) {
                return recipe;
            }
        }
        return null;
    }

    private static Map<ResourceLocation, IronSpellBooksRecipe> build() {
        Map<ResourceLocation, IronSpellBooksRecipe> result = new LinkedHashMap<>();
        addScrollForgeRecipes(result);
        addArcaneAnvilRecipes(result);
        return result;
    }

    private static void addScrollForgeRecipes(Map<ResourceLocation, IronSpellBooksRecipe> result) {
        for (AbstractSpell spell : SpellRegistry.getEnabledSpells()) {
            if (spell == SpellRegistry.none() || !spell.allowCrafting()) continue;
            int level = spell.getMinLevel();
            InkItem ink = InkItem.getInkForRarity(spell.getRarity(level));
            ItemStack focus = findFocus(spell);
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

    private static ItemStack findFocus(AbstractSpell spell) {
        for (var item : ForgeRegistries.ITEMS.getValues()) {
            ItemStack stack = new ItemStack(item);
            if (io.redspace.ironsspellbooks.api.registry.SchoolRegistry.getSchoolFromFocus(stack)
                    == spell.getSchoolType()) return stack;
        }
        return ItemStack.EMPTY;
    }

    private static ResourceLocation id(String kind, ResourceLocation spell, int level,
                                       @Nullable ResourceLocation material) {
        String path = "irons_spellbooks/" + kind + "/" + spell.getNamespace() + "/"
                + spell.getPath() + "/" + level;
        if (material != null) path += "/" + material.getNamespace() + "/" + material.getPath();
        return new ResourceLocation("rs_integration", path);
    }
}
