package com.huanghuang.rsintegration.mods.biomancy;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BiomancyBioLabInputLayoutTest extends BootstrapTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void centerIngredientAlwaysGoesIntoSlotFour(int sideCount) {
        List<IngredientSpec> specs = new ArrayList<>();
        List<ItemStack> materials = new ArrayList<>();
        for (int side = 0; side < sideCount; side++) {
            specs.add(new IngredientSpec(Ingredient.of(Items.DIAMOND), side + 1));
            materials.add(new ItemStack(Items.DIAMOND, side + 1));
        }
        Ingredient reactant = Ingredient.of(Items.POTION);
        specs.add(new IngredientSpec(reactant, 1));
        materials.add(new ItemStack(Items.POTION));
        ItemStackHandler inventory = new ItemStackHandler(5);
        assertTrue(new BiomancyBatchDelegate().placeBioLabInputs(inventory, specs, reactant, materials));
        for (int side = 0; side < sideCount; side++) {
            assertEquals(Items.DIAMOND, inventory.getStackInSlot(side).getItem());
            assertEquals(side + 1, inventory.getStackInSlot(side).getCount());
        }
        for (int side = sideCount; side < 4; side++) assertTrue(inventory.getStackInSlot(side).isEmpty());
        assertEquals(Items.POTION, inventory.getStackInSlot(4).getItem());
        assertEquals(1, inventory.getStackInSlot(4).getCount());
        assertEquals(1, materials.get(materials.size() - 1).getCount());
    }

    @Test
    void identicalSideAndCenterItemsKeepTheirSeparateRoles() {
        Ingredient apples = Ingredient.of(Items.APPLE);
        ItemStackHandler inventory = new ItemStackHandler(5);
        assertTrue(new BiomancyBatchDelegate().placeBioLabInputs(inventory,
                List.of(new IngredientSpec(apples, 3), new IngredientSpec(apples, 1)), apples,
                List.of(new ItemStack(Items.APPLE, 3), new ItemStack(Items.APPLE))));
        assertEquals(3, inventory.getStackInSlot(0).getCount());
        assertEquals(1, inventory.getStackInSlot(4).getCount());
        for (int side = 1; side < 4; side++) assertTrue(inventory.getStackInSlot(side).isEmpty());
    }

    @Test
    void recipeWithoutReactantLeavesCenterEmpty() {
        ItemStackHandler inventory = new ItemStackHandler(5);
        assertTrue(new BiomancyBatchDelegate().placeBioLabInputs(inventory,
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 2)), Ingredient.EMPTY,
                List.of(new ItemStack(Items.DIAMOND, 2))));
        assertEquals(2, inventory.getStackInSlot(0).getCount());
        assertTrue(inventory.getStackInSlot(4).isEmpty());
    }

    @Test
    void occupiedCenterDoesNotCauseReactantToFallBackToSideSlot() {
        ItemStackHandler inventory = new ItemStackHandler(5);
        ItemStack original = new ItemStack(Items.STONE);
        inventory.setStackInSlot(4, original);
        Ingredient reactant = Ingredient.of(Items.POTION);
        assertFalse(new BiomancyBatchDelegate().placeBioLabInputs(inventory,
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 2), new IngredientSpec(reactant, 1)),
                reactant, List.of(new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.POTION))));
        assertEquals(Items.STONE, inventory.getStackInSlot(4).getItem());
        for (int side = 0; side < 4; side++) assertTrue(inventory.getStackInSlot(side).isEmpty());
    }

    @Test
    void invalidCenterMaterialRejectsBeforeAnyInputIsInserted() {
        ItemStackHandler inventory = new ItemStackHandler(5);
        Ingredient reactant = Ingredient.of(Items.POTION);
        assertFalse(new BiomancyBatchDelegate().placeBioLabInputs(inventory,
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 2), new IngredientSpec(reactant, 1)),
                reactant, List.of(new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.STONE))));
        for (int slot = 0; slot < 5; slot++) assertTrue(inventory.getStackInSlot(slot).isEmpty());
    }

    @Test
    void lockedCenterRejectsBeforeAnySideMaterialIsInserted() {
        ItemStackHandler inventory = new ItemStackHandler(5) {
            @Override
            public boolean isItemValid(int slot, ItemStack stack) {
                return slot != 4;
            }
        };
        Ingredient reactant = Ingredient.of(Items.POTION);
        assertFalse(new BiomancyBatchDelegate().placeBioLabInputs(inventory,
                List.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 2), new IngredientSpec(reactant, 1)),
                reactant, List.of(new ItemStack(Items.DIAMOND, 2), new ItemStack(Items.POTION))));
        for (int slot = 0; slot < 5; slot++) assertTrue(inventory.getStackInSlot(slot).isEmpty());
    }

    @Test
    void fifthSideMaterialCannotUseCenterSlot() {
        List<IngredientSpec> specs = new ArrayList<>();
        List<ItemStack> materials = new ArrayList<>();
        for (int side = 0; side < 5; side++) {
            specs.add(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1));
            materials.add(new ItemStack(Items.DIAMOND));
        }
        assertNull(BiomancyBioLabInputLayout.plan(specs, Ingredient.EMPTY, materials));
    }
}
