package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.CountedOperationMaterials.Origin;
import com.huanghuang.rsintegration.crafting.batch.OperationStartContext.ReservedMaterial;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class OperationStartContextTest extends BootstrapTest {
    @Test
    void reorderedReservationsPlaceEachRoleInItsDeclaredSparseSlot() {
        var iron = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1);
        var plan = new MaterialPlan(List.of(
                new MaterialPlan.Entry("tip", iron, MaterialPlan.Allocation.SUPPLEMENTAL, 4),
                new MaterialPlan.Entry("base", iron, MaterialPlan.Allocation.GRAPH, 0)));
        ItemStack tip = new ItemStack(Items.IRON_INGOT);
        tip.getOrCreateTag().putString("variant", "polished");
        var context = new OperationStartContext(mock(ServerPlayer.class), new ExtractionLedger(),
                OperationStartContext.MaterialOwnership.CHAIN_RESERVED, plan,
                List.of(new ReservedMaterial("base", new ItemStack(Items.IRON_INGOT)),
                        new ReservedMaterial("tip", tip)), null, null);
        var placed = context.legacyMaterials();
        assertEquals(Items.IRON_INGOT, placed.get(0).getItem());
        assertNull(placed.get(0).getTag());
        assertEquals("polished", placed.get(4).getTag().getString("variant"));
        assertEquals(ItemStack.EMPTY, placed.get(1));
    }

    @Test
    void sameInputFromTwoSourcesFillsOneSlotAndDebitsEachSourceExactlyOnce() {
        var iron = new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 3);
        var pool = new CountedOperationMaterials.Pool()
                .add(Origin.PRODUCER, new ItemStack(Items.IRON_INGOT), 1)
                .add(Origin.INITIAL, new ItemStack(Items.IRON_INGOT), 2);
        var counted = new CountedOperationMaterials.Builder(1, 1).add(iron, pool).build();
        var plan = new MaterialPlan(List.of(new MaterialPlan.Entry("base", iron, MaterialPlan.Allocation.GRAPH, 4)));
        var context = OperationStartContext.chainReserved(mock(ServerPlayer.class), new ExtractionLedger(),
                plan, counted.batchMaterials(0, 0, 1), null);
        assertEquals(3, context.legacyMaterials().get(4).getCount());
        assertEquals(1, counted.materials(Origin.PRODUCER, 0, 1).get(0).getCount());
        assertEquals(2, counted.materials(Origin.INITIAL, 0, 1).get(0).getCount());
    }

    @Test
    void legacyContextsHaveNoFabricatedOperationIdentity() {
        ServerPlayer player = mock(ServerPlayer.class);
        OperationStartContext context = new OperationStartContext(
                player,
                new ExtractionLedger(),
                OperationStartContext.MaterialOwnership.CHAIN_RESERVED,
                MaterialPlan.none(),
                List.of(),
                InputBufferPlan.none(),
                null
        );

        assertNull(context.transactionId());
        assertNull(context.operationId());
        assertEquals(1, context.attempt());
    }

    @Test
    void operationIdentityRequiresBothIdsAndPositiveAttempt() {
        ServerPlayer player = mock(ServerPlayer.class);
        OperationStartContext context = new OperationStartContext(
                player,
                new ExtractionLedger(),
                OperationStartContext.MaterialOwnership.CHAIN_RESERVED,
                MaterialPlan.none(),
                List.of(),
                InputBufferPlan.none(),
                null
        );
        UUID transactionId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();

        OperationStartContext identified =
                context.withOperationIdentity(transactionId, operationId, 2);

        assertEquals(transactionId, identified.transactionId());
        assertEquals(operationId, identified.operationId());
        assertEquals(2, identified.attempt());
        assertThrows(IllegalArgumentException.class,
                () -> context.withOperationIdentity(
                        transactionId, operationId, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new OperationStartContext(
                        player,
                        new ExtractionLedger(),
                        OperationStartContext.MaterialOwnership.CHAIN_RESERVED,
                        MaterialPlan.none(),
                        List.of(),
                        InputBufferPlan.none(),
                        null,
                        transactionId,
                        null,
                        1
                ));
    }
}
