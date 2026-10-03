package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class WRAlchemyRecipeHandlerTest extends BootstrapTest {
    private WRAlchemyRecipeHandler handler() {
        return new WRAlchemyRecipeHandler(fluid -> InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid));
    }

    @Test
    void reservesItemAndFluidAmountWithoutReservingEnergy() {
        TestRecipe recipe = new TestRecipe(new ItemStack(Items.DIAMOND), FluidStack.EMPTY,
                List.of(new FluidInput(List.of(new FluidStack(Fluids.WATER, 750)))));
        WRAlchemyRecipeHandler handler = handler();
        List<IngredientSpec> inputs = handler.getMachineInputs(recipe, false).stream()
                .map(WRAlchemyRecipeHandler.MachineInput::material).toList();
        assertEquals(2, inputs.size());
        assertEquals(1, inputs.get(0).count());
        assertTrue(inputs.get(0).ingredient().test(new ItemStack(Items.IRON_INGOT)));
        assertEquals(750, inputs.get(1).count());
        assertTrue(inputs.get(1).ingredient().test(token(new FluidStack(Fluids.WATER, 750))));
        assertTrue(handler.preferHandlerIngredients());
        assertFalse(handler.cacheByRecipeClass());
        assertTrue(handler.isCompatibleBinding(recipe, "alchemy_machine||block.wizards_reborn.alchemy_machine"));
        assertFalse(handler.isCompatibleBinding(recipe, "block.wizards_reborn.alchemy_boiler"));
    }

    @Test
    void fluidOnlyOutputRetainsMillibucketsAndNbt() {
        FluidStack fluid = new FluidStack(Fluids.WATER, 2000);
        fluid.getOrCreateTag().putString("variant", "alchemy");
        TestRecipe recipe = new TestRecipe(ItemStack.EMPTY, fluid, List.of());
        ItemStack result = handler().getResultItem(recipe, RegistryAccess.EMPTY);
        assertEquals(2000, result.getCount());
        assertTrue(fluid.isFluidStackIdentical(InkFluidSupport.fluid(result)));
        assertTrue(handler().getSecondaryOutputs(recipe, RegistryAccess.EMPTY).isEmpty());
        assertEquals(2000, fluid.getAmount());
    }

    @Test
    void mixedOutputDeclaresBothActualProducts() {
        TestRecipe recipe = new TestRecipe(new ItemStack(Items.DIAMOND, 2),
                new FluidStack(Fluids.LAVA, 500), List.of());
        ItemStack result = handler().getResultItem(recipe, RegistryAccess.EMPTY);
        assertTrue(result.is(Items.DIAMOND));
        assertEquals(2, result.getCount());
        List<ItemStack> secondary = handler().getSecondaryOutputs(recipe, RegistryAccess.EMPTY);
        assertEquals(1, secondary.size());
        assertTrue(recipe.fluidOutput.isFluidStackIdentical(InkFluidSupport.fluid(secondary.get(0))));
    }

    @Test
    void fluidAlternativesMatchIdentityAndNbtStrictly() {
        FluidStack water = new FluidStack(Fluids.WATER, 500);
        water.getOrCreateTag().putString("variant", "required");
        FluidStack lava = new FluidStack(Fluids.LAVA, 500);
        TestRecipe recipe = new TestRecipe(new ItemStack(Items.DIAMOND), FluidStack.EMPTY,
                List.of(new FluidInput(List.of(water, lava))));
        Ingredient ingredient = handler().getIngredients(recipe).get(1).ingredient();
        assertTrue(ingredient.test(token(water)));
        assertTrue(ingredient.test(token(lava)));
        assertFalse(ingredient.test(token(new FluidStack(Fluids.WATER, 500))));
        assertFalse(ingredient.test(new ItemStack(Items.WATER_BUCKET)));
    }

    @Test
    void rejectsFluidAlternativesWithDifferentAmounts() {
        TestRecipe recipe = new TestRecipe(new ItemStack(Items.DIAMOND), FluidStack.EMPTY,
                List.of(new FluidInput(List.of(new FluidStack(Fluids.WATER, 250),
                        new FluidStack(Fluids.LAVA, 500)))));
        assertThrows(IllegalArgumentException.class, () -> handler().getIngredients(recipe));
    }

    @Test
    void freeWaterIsExcludedFromReservationButRetainsItsPhysicalTankPosition() {
        TestRecipe recipe = new TestRecipe(new ItemStack(Items.DIAMOND), FluidStack.EMPTY,
                List.of(new FluidInput(List.of(new FluidStack(Fluids.WATER, 750))),
                        new FluidInput(List.of(new FluidStack(Fluids.LAVA, 500)))));
        assertEquals(2, handler().getIngredients(recipe).size());
        var physical = handler().getMachineInputs(recipe, true);
        assertEquals(3, physical.size());
        assertTrue(physical.get(1).freeWater());
        assertEquals(750, physical.get(1).material().count());
        assertFalse(physical.get(2).freeWater());
        assertEquals(500, handler().getIngredients(recipe).get(1).count());
    }

    @Test
    void disablingFreeWaterInUnifiedConfigReservesNativeWaterAgain() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        config.set("autoCrafting.freeWaterMachines", List.of());
        try {
            RSIntegrationConfig.SERVER_SPEC.setConfig(config);
            TestRecipe recipe = new TestRecipe(new ItemStack(Items.DIAMOND), FluidStack.EMPTY,
                    List.of(new FluidInput(List.of(new FluidStack(Fluids.WATER, 750)))));
            assertEquals(2, handler().getIngredients(recipe).size());
            assertEquals(750, handler().getIngredients(recipe).get(1).count());
        } finally {
            RSIntegrationConfig.SERVER_SPEC.setConfig(null);
        }
    }

    @Test
    void freeWaterDoesNotReplaceTaggedWaterOrMixedFluidAlternatives() {
        FluidStack tagged = new FluidStack(Fluids.WATER, 500);
        tagged.getOrCreateTag().putString("variant", "required");
        TestRecipe recipe = new TestRecipe(new ItemStack(Items.DIAMOND), FluidStack.EMPTY,
                List.of(new FluidInput(List.of(tagged)), new FluidInput(List.of(
                        new FluidStack(Fluids.WATER, 500), new FluidStack(Fluids.LAVA, 500)))));
        assertEquals(3, handler().getIngredients(recipe).size());
        assertTrue(handler().getMachineInputs(recipe, true).stream().noneMatch(WRAlchemyRecipeHandler.MachineInput::freeWater));
    }

    private static ItemStack token(FluidStack fluid) {
        return InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), fluid);
    }

    public record FluidInput(List<FluidStack> fluids) {
        public List<FluidStack> getFluids() { return fluids; }
    }

    public static class TestRecipe extends ShapelessRecipe {
        private final FluidStack fluidOutput;
        private final List<FluidInput> fluidInputs;

        TestRecipe(ItemStack output, FluidStack fluidOutput, List<FluidInput> fluidInputs) {
            super(new ResourceLocation("test", "alchemy"), "", CraftingBookCategory.MISC, output,
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.IRON_INGOT)));
            this.fluidOutput = fluidOutput;
            this.fluidInputs = fluidInputs;
        }

        public List<FluidInput> getFluidInputs() { return fluidInputs; }
        public FluidStack getFluidResult() { return fluidOutput; }
        public Object getAlchemyPotion() { return null; }
        public Object getAlchemyPotionIngredient() { return null; }
        public int getWissen() { return 1500; }
        public int getSteam() { return 300; }
    }
}
