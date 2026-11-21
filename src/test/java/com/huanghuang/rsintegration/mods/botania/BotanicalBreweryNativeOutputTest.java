package com.huanghuang.rsintegration.mods.botania;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.NativeItemOutput;
import com.huanghuang.rsintegration.crafting.batch.MachineOperation.CraftPhase;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;
import vazkii.botania.api.recipe.BotanicalBreweryRecipe;
import vazkii.botania.common.block.block_entity.BreweryBlockEntity;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotanicalBreweryNativeOutputTest extends BootstrapTest {
    @Test
    void rejectsOtherBrewsButKeepsRuntimeTags() {
        var expected = new ItemStack(Items.POTION);
        expected.getOrCreateTag().putString("brewKey", "botania:speed");
        var actual = expected.copy();
        actual.getOrCreateTag().putInt("swigsLeft", 2);
        assertTrue(BotanicalBreweryBatchDelegate.matchesOutput(actual, expected));
        actual.getOrCreateTag().putString("brewKey", "botania:strength");
        assertFalse(BotanicalBreweryBatchDelegate.matchesOutput(actual, expected));
        assertFalse(BotanicalBreweryBatchDelegate.matchesOutput(new ItemStack(Items.POTION), expected));
    }

    @Test
    void waitsForNativeRecipeToClearBeforeAllowingTheNextCraft() throws Exception {
        var delegate = new BotanicalBreweryBatchDelegate();
        var brewery = mock(BreweryBlockEntity.class);
        var recipe = mock(BotanicalBreweryRecipe.class);
        when(brewery.getItemHandler()).thenReturn(new SimpleContainer(7));
        set(delegate, "brewery", brewery);
        set(delegate, "recipe", recipe);
        set(delegate, "received", true);

        brewery.recipe = recipe;
        assertEquals(CraftPhase.WORKING, delegate.observeMachineCraft(null, brewery).phase());
        brewery.recipe = null;
        assertEquals(CraftPhase.DONE, delegate.observeMachineCraft(null, brewery).phase());
    }

    @Test
    void falsePositivePlacementReturnsUninstalledInputWithoutRemovingReplacement() throws Exception {
        var delegate = spy(new BotanicalBreweryBatchDelegate());
        var level = mock(ServerLevel.class);
        var brewery = mock(BreweryBlockEntity.class, withSettings().extraInterfaces(NativeItemOutput.class));
        var recipe = mock(BotanicalBreweryRecipe.class);
        var inventory = new SimpleContainer(7);
        when(level.hasChunkAt(BlockPos.ZERO)).thenReturn(true);
        when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(brewery);
        when(brewery.getItemHandler()).thenReturn(inventory);
        set(delegate, "level", level);
        set(delegate, "pos", BlockPos.ZERO);
        set(delegate, "brewery", brewery);
        set(delegate, "recipe", recipe);
        var materials = List.of(new ItemStack(Items.GLASS_BOTTLE),
                new ItemStack(Items.SUGAR), new ItemStack(Items.REDSTONE));
        doReturn(materials.stream().map(stack -> new IngredientSpec(Ingredient.of(stack), 1)).toList())
                .when(delegate).recipeSlots();
        try (var reception = TaskOutputReception.open()) {
            delegate.bindProduction(reception.writer());
            var placements = new AtomicInteger();
            when(brewery.addItem(isNull(), any(ItemStack.class), isNull())).thenAnswer(call -> {
                int slot = placements.getAndIncrement();
                if (slot < 2) {
                    ItemStack input = call.getArgument(1);
                    inventory.setItem(slot, input.split(1));
                }
                return true;
            });

            assertFalse(delegate.startPreparedOperation(null, materials, ExtractionLedger.external()));
            var replacement = new ItemStack(Items.SUGAR);
            inventory.setItem(1, replacement);
            delegate.clearMachineState(brewery, null);

            assertTrue(inventory.getItem(0).isEmpty());
            assertSame(replacement, inventory.getItem(1));
            var recovered = delegate.failureRecoveredInputs();
            assertEquals(2, recovered.size());
            assertTrue(recovered.stream().anyMatch(stack -> stack.is(Items.GLASS_BOTTLE)));
            assertTrue(recovered.stream().anyMatch(stack -> stack.is(Items.REDSTONE)));
        }
    }

    private static void set(BotanicalBreweryBatchDelegate delegate, String name, Object value) throws Exception {
        var field = BotanicalBreweryBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(delegate, value);
    }
}
