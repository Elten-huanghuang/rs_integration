package com.huanghuang.rsintegration.mods.farmersrespite.kettle;

import com.huanghuang.rsintegration.mods.farmersrespite.FarmersRespiteRecipeHandler;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.server.level.ServerLevel;
import com.huanghuang.rsintegration.crafting.batch.MachineOperation.CraftPhase;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FRKettleRecipeSupportTest extends BootstrapTest {

    @Test
    void observationAndEarlyCollectionLeaveIntermediateBottlesForTheNextPhase() throws Exception {
        var kettle = new FRKettleBatchDelegate();
        var inventory = new ItemStackHandler(5);
        var be = mock(BlockEntity.class);
        var level = mock(ServerLevel.class);
        var input = new FluidStack(Fluids.WATER, 250);
        var brewed = new FluidStack(Fluids.LAVA, 250);
        var bottle = new ItemStack(Items.GLASS_BOTTLE);
        var phase = FRKettleBatchDelegate.class.getDeclaredField("nativeInputPhase");
        phase.setAccessible(true);
        for (Object value : phase.getType().getEnumConstants()) {
            if ("EMPTYING_INPUT".equals(value.toString())) phase.set(kettle, value);
        }
        set(kettle, "recipe", mock(Recipe.class));
        set(kettle, "recipeFluidIn", input);
        set(kettle, "recipeFluidOut", brewed);
        set(kettle, "pourContainer", bottle);
        set(kettle, "inputPourContainer", bottle);
        set(kettle, "bottlesPerCraft", 1);
        set(kettle, "inputBottlesPerCraft", 1);
        inventory.setStackInSlot(4, bottle.copy());
        kettle.advanceNativePhase(inventory, be, input);
        assertEquals("BREWING", phase.get(kettle).toString());
        for (int read = 0; read < 2; read++) {
            assertEquals(CraftPhase.WORKING, kettle.observeMachineCraft(level, be).phase());
            assertTrue(kettle.outputSource().collectFinal(null).stacks().isEmpty());
            assertTrue(inventory.getStackInSlot(4).is(Items.GLASS_BOTTLE));
            assertTrue(inventory.getStackInSlot(3).isEmpty());
        }
        kettle.advanceNativePhase(inventory, be, brewed);
        assertEquals("BOTTLING_OUTPUT", phase.get(kettle).toString());
        assertTrue(inventory.getStackInSlot(4).isEmpty());
        assertEquals(1, inventory.getStackInSlot(3).getCount());
        inventory.setStackInSlot(3, ItemStack.EMPTY);
        kettle.advanceNativePhase(inventory, be, brewed);
        assertTrue(inventory.getStackInSlot(3).isEmpty());
    }

    private static void set(FRKettleBatchDelegate kettle, String name, Object value) throws Exception {
        var field = FRKettleBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(kettle, value);
    }

    @Test
    void fullKettleBecomesFourBottles() {
        assertEquals(4, FRKettleRecipeSupport.bottleCount(1000));
        assertEquals(1, FRKettleRecipeSupport.bottleCount(250));
        assertEquals(0, FRKettleRecipeSupport.bottleCount(0));
    }

    @Test
    void waterIsFreeButDrinkFluidsAreDependencies() {
        assertTrue(FarmersRespiteRecipeHandler.isWater(new FluidStack(Fluids.WATER, 1000)));
        assertTrue(FarmersRespiteRecipeHandler.isWater(new FluidStack(Fluids.FLOWING_WATER, 1000)));
        assertFalse(FarmersRespiteRecipeHandler.isWater(FluidStack.EMPTY));
    }

    @Test
    void onlyWaterMayBypassNativeContainerInput() {
        assertFalse(FRKettleBatchDelegate.requiresNativeBottledInput(
                new FluidStack(Fluids.WATER, 1000)));
        assertFalse(FRKettleBatchDelegate.requiresNativeBottledInput(
                new FluidStack(Fluids.FLOWING_WATER, 1000)));
        assertTrue(FRKettleBatchDelegate.requiresNativeBottledInput(
                new FluidStack(Fluids.LAVA, 1000)));
    }

    @Test
    void pouringInputUsesRecipeItemSemanticsInsteadOfExactRuntimeTags() {
        ItemStack expected = new ItemStack(Items.POTION);
        ItemStack actual = new ItemStack(Items.POTION, 4);
        CompoundTag runtimeTag = new CompoundTag();
        runtimeTag.putString("runtime", "variant");
        actual.setTag(runtimeTag);

        assertTrue(FRKettleBatchDelegate.matchesPouringItem(expected, actual));
        assertFalse(ItemStack.isSameItemSameTags(expected, actual));
    }

    @Test
    void ownedOutputSlotIsClearedWithoutDependingOnExtractionRules() {
        ItemStackHandler inventory = new ItemStackHandler(5) {
            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return ItemStack.EMPTY;
            }
        };
        inventory.setStackInSlot(4, new ItemStack(Items.APPLE, 4));

        ItemStack result = FRKettleBatchDelegate.takeOwnedSlot(inventory, 4);

        assertEquals(4, result.getCount());
        assertTrue(result.is(Items.APPLE));
        assertTrue(inventory.getStackInSlot(4).isEmpty());
    }

    @Test
    void bottledFluidIsRemovedAfterSolidIngredientsWithoutLosingRuntimeTag() {
        ItemStack honey = new ItemStack(Items.HONEY_BOTTLE);
        ItemStack coffee = new ItemStack(Items.POTION, 4);
        CompoundTag purity = new CompoundTag();
        purity.putInt("Purity", 3);
        coffee.setTag(purity);
        List<ItemStack> pool = new java.util.ArrayList<>(List.of(honey.copy(), coffee.copy()));

        List<ItemStack> solids = FRKettleBatchDelegate.takeMatchingMaterials(
                pool, net.minecraft.world.item.crafting.Ingredient.of(Items.HONEY_BOTTLE), 1);
        List<ItemStack> fluids = FRKettleBatchDelegate.takeMatchingMaterials(
                pool, net.minecraft.world.item.crafting.Ingredient.of(Items.POTION), 4);

        assertEquals(1, solids.size());
        assertTrue(solids.get(0).is(Items.HONEY_BOTTLE));
        assertEquals(4, fluids.get(0).getCount());
        assertEquals(3, fluids.get(0).getTag().getInt("Purity"));
        assertTrue(pool.stream().allMatch(ItemStack::isEmpty));
    }
}
