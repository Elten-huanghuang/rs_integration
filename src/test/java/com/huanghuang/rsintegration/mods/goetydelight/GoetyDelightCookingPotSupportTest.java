package com.huanghuang.rsintegration.mods.goetydelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.huanghuang.rsintegration.mods.goetydelight.GoetyDelightCookingPotSupport.APOCALYPTIUM_POT;
import static com.huanghuang.rsintegration.mods.goetydelight.GoetyDelightCookingPotSupport.CURSED_POT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class GoetyDelightCookingPotSupportTest extends BootstrapTest {
    @BeforeAll
    static void initializeModLogger() throws ClassNotFoundException {
        var context = mock(FMLJavaModLoadingContext.class);
        when(context.getModEventBus()).thenReturn(mock(IEventBus.class));
        try (var loading = mockStatic(FMLJavaModLoadingContext.class)) {
            loading.when(FMLJavaModLoadingContext::get).thenReturn(context);
            Class.forName(RSIntegrationMod.class.getName());
        }
    }

    @Test
    void nativeWorkstationRulesFilterMetalPotsBeforeCheckingTheirMode() {
        assertTrue(GoetyDelightCookingPotSupport.mayUseWorkstation(CURSED_POT, CURSED_POT, false));
        assertTrue(GoetyDelightCookingPotSupport.mayUseWorkstation(APOCALYPTIUM_POT, CURSED_POT, false));
        assertTrue(GoetyDelightCookingPotSupport.mayUseWorkstation(APOCALYPTIUM_POT, APOCALYPTIUM_POT, false));
        assertFalse(GoetyDelightCookingPotSupport.mayUseWorkstation(CURSED_POT, APOCALYPTIUM_POT, false));
        assertTrue(GoetyDelightCookingPotSupport.mayUseWorkstation(CURSED_POT, APOCALYPTIUM_POT, true));
        assertFalse(GoetyDelightCookingPotSupport.mayUseWorkstation(null, CURSED_POT, true));
    }

    @Test
    void nativeRecipeSelectionSeesFoodOnlyAndRejectsAConflictingRecipe() {
        Recipe<?> requested = mock(Recipe.class);
        Recipe<?> other = mock(Recipe.class);
        when(requested.getId()).thenReturn(new ResourceLocation("goetydelight", "requested"));
        when(other.getId()).thenReturn(new ResourceLocation("goetydelight", "other"));
        List<ItemStack> materials = List.of(new ItemStack(Items.POTATO), new ItemStack(Items.BOWL));

        assertTrue(GoetyDelightCookingPotSupport.matchesNativeChoice(
                new SelectingPot(requested), requested, materials, true));
        assertFalse(GoetyDelightCookingPotSupport.matchesNativeChoice(
                new SelectingPot(other), requested, materials, true));
        assertFalse(GoetyDelightCookingPotSupport.matchesNativeChoice(
                new SelectingPot(requested), requested, materials, false));
    }

    @Test
    void necroticCookingChecksNativeSoulEnergyThreshold() {
        SelectingPot pot = new SelectingPot(null);
        pot.souls = 3;
        assertFalse(GoetyDelightCookingPotSupport.canPowerNecroticCooking(pot));
        pot.souls = 4;
        assertTrue(GoetyDelightCookingPotSupport.canPowerNecroticCooking(pot));
    }

    @Test
    void modifiedFoodNbtDoesNotPreventOutputCollection() {
        ItemStack actual = new ItemStack(Items.MUSHROOM_STEW, 2);
        CompoundTag changedData = new CompoundTag();
        changedData.putBoolean("SoulInfused", true);
        actual.setTag(changedData);

        assertTrue(GoetyDelightCookingPotSupport.matchesOutputIgnoringNbt(
                actual, new ItemStack(Items.MUSHROOM_STEW, 2)));
    }

    @Test
    void ignoringNbtStillRequiresTheExpectedItemAndCount() {
        assertFalse(GoetyDelightCookingPotSupport.matchesOutputIgnoringNbt(
                new ItemStack(Items.RABBIT_STEW, 1),
                new ItemStack(Items.MUSHROOM_STEW, 1)));
        assertFalse(GoetyDelightCookingPotSupport.matchesOutputIgnoringNbt(
                new ItemStack(Items.MUSHROOM_STEW, 1),
                new ItemStack(Items.MUSHROOM_STEW, 2)));
        assertFalse(GoetyDelightCookingPotSupport.matchesOutputIgnoringNbt(
                ItemStack.EMPTY, new ItemStack(Items.MUSHROOM_STEW)));
    }

    @Test
    void drainingCookingSlotsLeavesSoulSourceInThePot() {
        ItemStackHandler inventory = new ItemStackHandler(10);
        inventory.setStackInSlot(0, new ItemStack(Items.POTATO));
        inventory.setStackInSlot(7, new ItemStack(Items.BOWL));
        inventory.setStackInSlot(8, new ItemStack(Items.MUSHROOM_STEW));
        inventory.setStackInSlot(9, new ItemStack(Items.TOTEM_OF_UNDYING));

        var cookingSlots = GoetyDelightCookingPotSupport.cookingSlots(inventory);
        assertEquals(9, cookingSlots.getSlots());
        for (int slot = 0; slot < cookingSlots.getSlots(); slot++) {
            cookingSlots.extractItem(slot, 64, false);
        }

        assertTrue(inventory.getStackInSlot(0).isEmpty());
        assertTrue(inventory.getStackInSlot(7).isEmpty());
        assertTrue(inventory.getStackInSlot(8).isEmpty());
        assertTrue(inventory.getStackInSlot(9).is(Items.TOTEM_OF_UNDYING));
        assertNull(GoetyDelightCookingPotSupport.cookingSlots(new ItemStackHandler(8)));
    }

    private static final class SelectingPot extends BlockEntity {
        private final Recipe<?> choice;
        private int souls;

        private SelectingPot(Recipe<?> choice) {
            super(BlockEntityType.FURNACE, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState());
            this.choice = choice;
        }

        protected Recipe<?> findRecipe(RecipeWrapper input) {
            return input.getContainerSize() == 9 && input.getItem(0).is(Items.POTATO)
                    && input.getItem(1).isEmpty() ? choice : null;
        }

        protected int getSoulEnergyAvailable() {
            return souls;
        }
    }
}
