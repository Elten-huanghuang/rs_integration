package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterialLocksTest extends BootstrapTest {
    private static final ResourceLocation RECIPE = new ResourceLocation("test", "table");

    @Test
    void keyIsStableAcrossCandidateOrderAndScopedToRecipe() {
        Ingredient first = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS);
        Ingredient reversed = Ingredient.of(Items.BIRCH_PLANKS, Items.OAK_PLANKS);

        assertEquals(MaterialLocks.key(RECIPE, first), MaterialLocks.key(RECIPE, reversed));
        assertNotEquals(MaterialLocks.key(RECIPE, first),
                MaterialLocks.key(new ResourceLocation("test", "child"), first));
    }

    @Test
    void legalSelectionNarrowsEveryEquivalentSlotToOneConcreteItem() {
        Ingredient planks = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS);
        String key = MaterialLocks.key(RECIPE, planks);
        ResolutionContext context = new ResolutionContext(null, Map.of(),
                List.of(new ItemStack(Items.OAK_PLANKS, 3),
                        new ItemStack(Items.BIRCH_PLANKS, 64)), null)
                .withMaterialLocks(Map.of(key, new ItemStack(Items.OAK_PLANKS)));

        Ingredient firstSlot = context.lockedIngredient(RECIPE, planks);
        Ingredient secondSlot = context.lockedIngredient(RECIPE, planks);

        assertEquals(3, context.countMatching(firstSlot));
        assertEquals(3, context.countMatching(secondSlot));
        assertFalse(IngredientMatcher.test(firstSlot, new ItemStack(Items.BIRCH_PLANKS)));
    }

    @Test
    void serverCanonicalizesSelectionAndRejectsNonMember() {
        Ingredient planks = Ingredient.of(Items.OAK_PLANKS, Items.BIRCH_PLANKS);
        String key = MaterialLocks.key(RECIPE, planks);
        ItemStack forged = new ItemStack(Items.OAK_PLANKS, 64);
        CompoundTag clientOnly = new CompoundTag();
        clientOnly.putString("client", "forged");
        forged.setTag(clientOnly);

        Ingredient narrowed = MaterialLocks.narrow(RECIPE, planks, Map.of(key, forged));
        assertFalse(narrowed.getItems()[0].hasTag());
        assertEquals(1, narrowed.getItems()[0].getCount());
        assertThrows(IllegalArgumentException.class, () -> MaterialLocks.narrow(
                RECIPE, planks, Map.of(key, new ItemStack(Items.DIAMOND))));
    }
}
