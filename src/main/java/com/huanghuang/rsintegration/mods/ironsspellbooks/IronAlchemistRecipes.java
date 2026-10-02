package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.RSIntegrationMod;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import io.redspace.ironsspellbooks.item.InkItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** 配方和墨水映射来自运行中的法术注册表，兼容没有流体炼金锅的旧版本。 */
final class IronAlchemistRecipes {
    private IronAlchemistRecipes() {}

    static void addRecipes(Map<ResourceLocation, IronSpellBooksRecipe> recipes,
                           List<AbstractSpell> spells, List<InkItem> inks,
                           IronSpellCatalogDiagnostics diagnostics) {
        for (InkItem ink : inks) {
            FluidStack fluid = inkFluid(ink);
            if (fluid.isEmpty()) continue;
            ResourceLocation inkId = ForgeRegistries.ITEMS.getKey(ink);
            ResourceLocation id = new ResourceLocation("rs_integration", "irons_spellbooks/bottle/" + inkId.getPath());
            recipes.put(id, IronSpellBooksRecipe.bottleRecipe(id, InkFluidSupport.token(fluid), new ItemStack(ink)));
        }
        try {
            Method inkFromScroll = Class.forName("io.redspace.ironsspellbooks.block.alchemist_cauldron.AlchemistCauldronTile")
                    .getMethod("getInkFromScroll", ItemStack.class);
            for (AbstractSpell spell : spells) {
                diagnostics.read("alchemist_spell", spell.getClass().getName(), 0, () -> {
                    for (int level = spell.getMinLevel(); level <= spell.getMaxLevel(); level++) {
                        int scrollLevel = level;
                        diagnostics.read("alchemist_scroll", spell.getClass().getName(), level, () -> {
                            ItemStack scroll = IronSpellBooksRecipeCatalog.scrollFor(spell, scrollLevel);
                            Object item = inkFromScroll.invoke(null, scroll);
                            if (!(item instanceof Item ink)) return null;
                            FluidStack fluid = inkFluid(ink);
                            if (fluid.isEmpty()) return null;
                            ResourceLocation spellId = new ResourceLocation(spell.getSpellId());
                            ResourceLocation id = new ResourceLocation("rs_integration", "irons_spellbooks/recycle/"
                                    + spellId.getNamespace() + "/" + spellId.getPath() + "/" + scrollLevel);
                            recipes.put(id, new IronSpellBooksRecipe(id, IronSpellBooksRecipe.Machine.ALCHEMIST_CAULDRON,
                                    List.of(scroll), InkFluidSupport.token(fluid), spell.getSpellId()));
                            return null;
                        });
                    }
                    return null;
                });
            }
        } catch (ReflectiveOperationException | LinkageError failure) {
            RSIntegrationMod.LOGGER.warn("[RSI-IronSpells] 炼金锅卷轴回收配方不可用", failure);
        }
    }

    static FluidStack inkFluid(Item ink) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(ink);
        if (id == null) return FluidStack.EMPTY;
        var fluid = ForgeRegistries.FLUIDS.getValue(id);
        return fluid == null || fluid == Fluids.EMPTY ? FluidStack.EMPTY
                : new FluidStack(fluid, InkFluidSupport.BOTTLE_AMOUNT);
    }

}
