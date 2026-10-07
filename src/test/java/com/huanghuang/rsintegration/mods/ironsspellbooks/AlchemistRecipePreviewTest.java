package com.huanghuang.rsintegration.mods.ironsspellbooks;

import com.huanghuang.rsintegration.mods.ironsspellbooks.client.AlchemistCauldronRecipeCategory;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import mezz.jei.api.forge.ForgeTypes;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import mezz.jei.api.recipe.IFocusGroup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AlchemistRecipePreviewTest extends BootstrapTest {
    @Test
    void recyclingPreviewKeepsConcreteScrollAndUsesNativeFluidSlotsForWaterAndInk() {
        ItemStack scroll = new ItemStack(Items.PAPER);
        scroll.getOrCreateTag().putString("spell", "specific-scroll");
        ItemStack ink = token();
        IronSpellBooksRecipe recipe = new IronSpellBooksRecipe(new ResourceLocation("test", "recycle"),
                IronSpellBooksRecipe.Machine.ALCHEMIST_CAULDRON, List.of(scroll), ink, "test:spell");
        IRecipeLayoutBuilder builder = mock(IRecipeLayoutBuilder.class);
        IRecipeSlotBuilder input = slot(), water = slot(), output = slot();
        when(builder.addInputSlot(8, 8)).thenReturn(input);
        when(builder.addInputSlot(36, 8)).thenReturn(water);
        when(builder.addOutputSlot(118, 8)).thenReturn(output);

        category().setRecipe(builder, recipe, mock(IFocusGroup.class));

        verify(input).addIngredients(recipe.inputIngredients().get(0));
        assertTrue(recipe.inputIngredients().get(0).test(scroll));
        verify(water).addIngredient(eq(ForgeTypes.FLUID_STACK), argThat(fluid -> fluid.getFluid() == Fluids.WATER
                && fluid.getAmount() == 250));
        verify(output).addIngredient(eq(ForgeTypes.FLUID_STACK), argThat(fluid -> fluid.getFluid() == Fluids.LAVA
                && fluid.getAmount() == 250));
        verify(output, never()).addItemStack(any());
    }

    @Test
    void bottlingPreviewStillUsesInkFluidInputAndOrdinaryItemOutput() {
        ItemStack ink = token();
        IronSpellBooksRecipe recipe = IronSpellBooksRecipe.bottleRecipe(new ResourceLocation("test", "bottle"),
                ink, new ItemStack(Items.INK_SAC));
        IRecipeLayoutBuilder builder = mock(IRecipeLayoutBuilder.class);
        IRecipeSlotBuilder input = slot(), bottle = slot(), output = slot();
        when(builder.addInputSlot(8, 8)).thenReturn(input);
        when(builder.addInputSlot(36, 8)).thenReturn(bottle);
        when(builder.addOutputSlot(118, 8)).thenReturn(output);
        doReturn(slot()).when(builder).addInvisibleIngredients(any());

        category().setRecipe(builder, recipe, mock(IFocusGroup.class));

        verify(input).addIngredient(eq(ForgeTypes.FLUID_STACK), argThat(fluid -> fluid.getFluid() == Fluids.LAVA
                && fluid.getAmount() == 250));
        verify(bottle).addIngredients(recipe.inputIngredients().get(1));
        verify(output).addItemStack(argThat(stack -> stack.is(Items.INK_SAC)));
    }

    @Test
    void cauldronCategoryDoesNotMisidentifyScrollForgeOrArcaneAnvilCandidates() {
        for (IronSpellBooksRecipe.Machine machine : IronSpellBooksRecipe.Machine.values()) {
            IronSpellBooksRecipe recipe = new IronSpellBooksRecipe(new ResourceLocation("test", "machine"),
                    machine, List.of(new ItemStack(Items.PAPER)), new ItemStack(Items.INK_SAC), "");
            assertEquals(machine == IronSpellBooksRecipe.Machine.ALCHEMIST_CAULDRON, category().isHandled(recipe));
        }
    }

    @Test
    void brewingPreviewUsesExactFluidRatioAndPlacesPhysicalByproductSeparately() {
        ItemStack inputFluid = InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.WATER, 1000));
        IronSpellBooksRecipe recipe = IronSpellBooksRecipe.brewRecipe(new ResourceLocation("test", "brew"),
                inputFluid, Ingredient.of(Items.AMETHYST_SHARD), List.of(token(), new ItemStack(Items.GLASS_BOTTLE)));
        IRecipeLayoutBuilder builder = mock(IRecipeLayoutBuilder.class);
        IRecipeSlotBuilder fluid = slot(), reagent = slot(), output = slot(), byproduct = slot();
        when(builder.addInputSlot(8, 8)).thenReturn(fluid);
        when(builder.addInputSlot(36, 8)).thenReturn(reagent);
        when(builder.addOutputSlot(118, 8)).thenReturn(output);
        when(builder.addOutputSlot(90, 30)).thenReturn(byproduct);
        doReturn(slot()).when(builder).addInvisibleIngredients(any());

        category().setRecipe(builder, recipe, mock(IFocusGroup.class));

        verify(fluid).addIngredient(eq(ForgeTypes.FLUID_STACK), argThat(stack -> stack.getAmount() == 1000));
        verify(reagent).addIngredients(recipe.inputIngredients().get(1));
        verify(reagent, never()).addIngredient(eq(ForgeTypes.FLUID_STACK), any());
        verify(builder, times(2)).addInputSlot(anyInt(), anyInt());
        verify(output).addIngredient(eq(ForgeTypes.FLUID_STACK), argThat(stack -> stack.getAmount() == 250));
        verify(byproduct).addItemStack(argThat(stack -> stack.is(Items.GLASS_BOTTLE) && stack.getCount() == 1));
    }

    private static AlchemistCauldronRecipeCategory category() {
        // 槽位声明测试无需真实 JEI 图标和 Minecraft 窗口。
        return mock(AlchemistCauldronRecipeCategory.class, CALLS_REAL_METHODS);
    }

    private static IRecipeSlotBuilder slot() { return mock(IRecipeSlotBuilder.class, RETURNS_SELF); }
    private static ItemStack token() {
        return InkFluidSupport.token(InkFluidTestFixtures.tokenItem(), new FluidStack(Fluids.LAVA, 250));
    }
}
