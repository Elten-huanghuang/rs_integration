package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericBatchDelegateTest extends BootstrapTest {

    @Test
    void collectAllResultsDrainsPrimaryAndRecipeRemaindersTogether() throws Exception {
        GenericBatchDelegate delegate = new GenericBatchDelegate();
        setField(delegate, "pendingResult", new ItemStack(Items.DIAMOND, 2));
        pendingSecondary(delegate).add(new ItemStack(Items.BUCKET));
        pendingSecondary(delegate).add(new ItemStack(Items.STICK, 3));

        List<ItemStack> results = delegate.collectAllResults(null);

        assertEquals(3, results.size());
        assertEquals(2, results.get(0).getCount());
        assertEquals(Items.DIAMOND, results.get(0).getItem());
        assertEquals(Items.BUCKET, results.get(1).getItem());
        assertEquals(3, results.get(2).getCount());
        assertEquals(Items.STICK, results.get(2).getItem());
        assertTrue(delegate.collectsPhysicalSecondaryOutputs());
        assertTrue(delegate.collectAllResults(null).isEmpty());
        assertTrue(delegate.getPendingSecondary().isEmpty());
    }

    @Test
    void repeatedCraftingReusesCatalystAndReturnsItOnlyOnce() {
        GenericBatchDelegate delegate = new GenericBatchDelegate();
        ShapelessRecipe recipe = new ReusableCatalystRecipe();
        List<IngredientSpec> specs = List.of(
                new IngredientSpec(Ingredient.of(Items.SHEARS), 1, DemandRole.CATALYST),
                new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1, DemandRole.CONSUMED));
        List<IBatchDelegate.MaterialReservationScope> scopes = List.of(
                IBatchDelegate.MaterialReservationScope.PER_WORKER_REUSABLE,
                IBatchDelegate.MaterialReservationScope.PER_OPERATION);

        assertTrue(delegate.captureRepeatedCraftingOutputs(
                recipe, specs, scopes,
                List.of(new ItemStack(Items.SHEARS), new ItemStack(Items.IRON_INGOT, 3)),
                3, RegistryAccess.EMPTY));

        List<ItemStack> results = delegate.collectAllResults(null);
        assertEquals(2, results.size());
        assertEquals(Items.DIAMOND, results.get(0).getItem());
        assertEquals(3, results.get(0).getCount());
        assertEquals(Items.SHEARS, results.get(1).getItem());
        assertEquals(1, results.get(1).getCount());
    }

    private static final class ReusableCatalystRecipe extends ShapelessRecipe {
        private ReusableCatalystRecipe() {
            super(new ResourceLocation("test", "reusable_catalyst_batch"), "",
                    CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                    NonNullList.of(Ingredient.EMPTY,
                            Ingredient.of(Items.SHEARS), Ingredient.of(Items.IRON_INGOT)));
        }

        @Override
        public NonNullList<ItemStack> getRemainingItems(CraftingContainer container) {
            NonNullList<ItemStack> remainders = NonNullList.withSize(
                    container.getContainerSize(), ItemStack.EMPTY);
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack input = container.getItem(slot);
                if (input.is(Items.SHEARS)) {
                    remainders.set(slot, input.copyWithCount(1));
                }
            }
            return remainders;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> pendingSecondary(GenericBatchDelegate delegate) throws Exception {
        Field field = GenericBatchDelegate.class.getDeclaredField("pendingSecondary");
        field.setAccessible(true);
        return (List<ItemStack>) field.get(delegate);
    }

    private static void setField(GenericBatchDelegate delegate, String name, Object value) throws Exception {
        Field field = GenericBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }
}
