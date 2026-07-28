package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialBroker;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.graph.NodeId;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ExecutionTransactionEquivalenceTest extends BootstrapTest {

    @Test
    void successfulConsumptionAndCatalystReturnMatch() {
        Outcome flat = runFlatSuccess();
        Outcome graph = runGraphSuccess();

        assertEquals(flat, graph);
        assertEquals(new Outcome(1, 1, 1), graph);
    }

    @Test
    void failureAfterReservationRestoresBothPaths() {
        Outcome flat = runFlatFailure();
        Outcome graph = runGraphFailure();

        assertEquals(flat, graph);
        assertEquals(new Outcome(2, 1, 0), graph);
    }

    private static Outcome runFlatSuccess() {
        List<ItemStack> inventory = new ArrayList<>(List.of(
                new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.BUCKET)));
        ItemStack output = StackPoolTransaction.execute(inventory, new ArrayList<>(),
                (initial, producer) -> {
                    initial.get(0).shrink(1);
                    return new ItemStack(Items.IRON_NUGGET);
                });
        assertNotNull(output);
        return new Outcome(inventory.get(0).getCount(), inventory.get(1).getCount(), output.getCount());
    }

    private static Outcome runGraphSuccess() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey bucket = MaterialKey.of(new ItemStack(Items.BUCKET));
        MaterialSource.InitialPool ironSource = new MaterialSource.InitialPool(iron);
        MaterialSource.InitialPool catalystSource = new MaterialSource.InitialPool(bucket);
        broker.publish(ironSource, iron, 2);
        broker.publish(catalystSource, bucket, 1);

        MaterialBroker.ReservationToken consumed = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(ironSource, iron, 1)));
        MaterialBroker.ReservationToken catalyst = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(catalystSource, bucket, 1)));
        assertNotNull(consumed);
        assertNotNull(catalyst);
        broker.commit(consumed);
        broker.commit(catalyst);
        broker.settle(consumed);
        broker.refund(catalyst);

        return new Outcome(broker.available(ironSource, iron),
                broker.available(catalystSource, bucket), 1);
    }

    private static Outcome runFlatFailure() {
        List<ItemStack> inventory = new ArrayList<>(List.of(
                new ItemStack(Items.IRON_INGOT, 2), new ItemStack(Items.BUCKET)));
        ItemStack output = StackPoolTransaction.execute(inventory, new ArrayList<>(),
                (initial, producer) -> {
                    initial.get(0).shrink(1);
                    initial.get(1).shrink(1);
                    return null;
                });
        assertNull(output);
        return new Outcome(inventory.get(0).getCount(), inventory.get(1).getCount(), 0);
    }

    private static Outcome runGraphFailure() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey bucket = MaterialKey.of(new ItemStack(Items.BUCKET));
        MaterialSource.InitialPool ironSource = new MaterialSource.InitialPool(iron);
        MaterialSource.InitialPool catalystSource = new MaterialSource.InitialPool(bucket);
        broker.publish(ironSource, iron, 2);
        broker.publish(catalystSource, bucket, 1);

        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0), List.of(
                new MaterialBroker.Request(ironSource, iron, 1),
                new MaterialBroker.Request(catalystSource, bucket, 1)));
        assertNotNull(token);
        broker.commit(token);
        broker.refund(token);

        return new Outcome(broker.available(ironSource, iron),
                broker.available(catalystSource, bucket), 0);
    }

    private record Outcome(int iron, int catalyst, int output) {}
}
