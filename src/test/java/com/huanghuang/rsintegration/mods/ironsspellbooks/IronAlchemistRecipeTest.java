package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IronAlchemistRecipeTest extends BootstrapTest {
    @Test
    void scrollRecyclingRequiresActualProductionButBottlingRemainsDeterministic() {
        IronSpellBooksRSModule.INSTANCE.registerModType();
        ItemStack fluid = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.WATER, 250));
        IronSpellBooksRecipe recycle = new IronSpellBooksRecipe(new ResourceLocation("test", "recycle"),
                IronSpellBooksRecipe.Machine.ALCHEMIST_CAULDRON,
                List.of(new ItemStack(Items.PAPER)), fluid, "test:spell");
        IronSpellBooksRecipe bottle = IronSpellBooksRecipe.bottleRecipe(
                new ResourceLocation("test", "bottle"), fluid, new ItemStack(Items.INK_SAC));
        IronSpellBooksRecipeHandler handler = new IronSpellBooksRecipeHandler();
        assertTrue(recycle.isScrollRecycling());
        assertFalse(handler.hasDeterministicPrimaryOutput(recycle));
        assertFalse(handler.supportsBackgroundPlanning(recycle));
        assertTrue(handler.requiresTargetedProduction(recycle, fluid));
        assertTrue(handler.hasDeterministicPrimaryOutput(bottle));
        assertTrue(handler.supportsBackgroundPlanning(bottle));
        assertFalse(handler.requiresTargetedProduction(bottle, new ItemStack(Items.INK_SAC)));
    }

    @Test
    void bottlingConsumes250MbAndOneBottleAndRetainsFluidQuality() {
        InkFluidItem tokenItem = InkFluidTestFixtures.tokenItem();
        ItemStack fluid = InkFluidSupport.token(tokenItem, new FluidStack(Fluids.WATER, 250));
        IronSpellBooksRecipe recipe = IronSpellBooksRecipe.bottleRecipe(new ResourceLocation("test", "bottle"),
                fluid, new ItemStack(Items.INK_SAC));
        List<IngredientSpec> specs = new IronSpellBooksRecipeHandler().getIngredients(recipe);
        assertEquals(2, specs.size());
        assertEquals(250, specs.get(0).count());
        assertEquals(1, specs.get(1).count());
        assertTrue(specs.get(0).ingredient().test(fluid.copyWithCount(1)));
        assertFalse(specs.get(0).ingredient().test(
                InkFluidSupport.token(tokenItem, new FluidStack(Fluids.LAVA, 250))));
        ItemStack namedBottle = new ItemStack(Items.GLASS_BOTTLE);
        namedBottle.getOrCreateTag().putString("custom", "test");
        assertTrue(specs.get(1).ingredient().test(namedBottle));
        assertEquals(Items.INK_SAC, recipe.getResultItem(RegistryAccess.EMPTY).getItem());
        assertEquals(1, recipe.getResultItem(RegistryAccess.EMPTY).getCount());
    }

    @Test
    void recycleAndBottleRecipesClassifyAsCauldronNotScrollForgeOrAnvil() {
        IronSpellBooksRSModule.INSTANCE.registerModType();
        ItemStack fluid = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.WATER, 250));
        IronSpellBooksRecipe recipe = IronSpellBooksRecipe.bottleRecipe(new ResourceLocation("test", "bottle"),
                fluid, new ItemStack(Items.INK_SAC));
        assertEquals(IronSpellBooksRSModule.ALCHEMIST_CAULDRON_TYPE, ModType.classifyRecipe(recipe).id());
    }
}
