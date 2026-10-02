package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IronAlchemistJeiBridgeTest extends BootstrapTest {
    private final ItemStack scroll = new ItemStack(Items.PAPER);
    private IronSpellBooksRecipe recycle() {
        ItemStack fluid = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(),
                InkFluidTestFixtures.ink("common_ink", 250));
        return new IronSpellBooksRecipe(new ResourceLocation("test", "recycle"),
                IronSpellBooksRecipe.Machine.ALCHEMIST_CAULDRON, List.of(scroll), fluid, "test:spell");
    }

    @Test void nativeScrollDisplayResolvesToExecutableRecycleRecipe() {
        IronSpellBooksRecipe recipe = recycle();
        assertSame(recipe, IronAlchemistJeiBridge.resolve(Ingredient.of(Items.PAPER), water(),
                List.of(InkFluidTestFixtures.ink("common_ink", 250)), ItemStack.EMPTY, scroll, ignored -> recipe));
    }

    @Test void unrelatedBrewingAndWrongQualityOutputsAreNotExposed() {
        IronSpellBooksRecipe recipe = recycle();
        assertNull(IronAlchemistJeiBridge.resolve(Ingredient.of(Items.PAPER), water(),
                List.of(InkFluidTestFixtures.ink("rare_ink", 250)), ItemStack.EMPTY, scroll, ignored -> recipe));
        assertNull(IronAlchemistJeiBridge.resolve(Ingredient.of(Items.PAPER), water(),
                List.of(InkFluidTestFixtures.ink("common_ink", 250)), new ItemStack(Items.DIAMOND), scroll, ignored -> recipe));
        assertNull(IronAlchemistJeiBridge.resolve(Ingredient.of(Items.PAPER), new FluidStack(Fluids.LAVA, 250),
                List.of(InkFluidTestFixtures.ink("common_ink", 250)), ItemStack.EMPTY, scroll, ignored -> recipe));
    }

    @Test void displayedInputMustMatchNativeAndExecutableRecipes() {
        IronSpellBooksRecipe recipe = recycle();
        assertNull(IronAlchemistJeiBridge.resolve(Ingredient.of(Items.PAPER), water(),
                List.of(InkFluidTestFixtures.ink("common_ink", 250)), ItemStack.EMPTY, new ItemStack(Items.STICK), ignored -> recipe));
    }

    @Test void onlyBottleRecipesBelongInCustomJeiRegistration() {
        IronSpellBooksRecipe recycle = recycle();
        assertFalse(recycle.isInkBottling());
        assertTrue(IronSpellBooksRecipe.bottleRecipe(new ResourceLocation("test", "bottle"),
                recycle.getResultItem(null), new ItemStack(Items.INK_SAC)).isInkBottling());
    }

    private FluidStack water() { return new FluidStack(Fluids.WATER, 250); }
}
