package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphAssetConservationTest extends BootstrapTest {

    @Test
    void serialProducerConsumerEqualsFlatConservationAndPreservesActualNbt() {
        MaterialBroker graph = new MaterialBroker();
        ItemStack actualIntermediate = new ItemStack(Items.IRON_INGOT, 3);
        CompoundTag runtimeTag = new CompoundTag();
        runtimeTag.putString("origin", "runtime-machine");
        actualIntermediate.setTag(runtimeTag);
        MaterialKey intermediate = MaterialKey.of(actualIntermediate);
        MaterialSource producer = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(0), 0));
        graph.publishActual(producer, intermediate, actualIntermediate);

        MaterialBroker.ReservationToken consumer = graph.reserve(new NodeId(1),
                List.of(new MaterialBroker.Request(producer, intermediate, 2)));
        assertNotNull(consumer);
        List<ItemStack> checkedOut = graph.producerFragments(consumer);
        graph.commit(consumer);
        graph.settle(consumer);
        List<ItemStack> graphSurplus = graph.drainAvailableProducerAssets();

        int flatConsumed = 2;
        int flatSurplus = actualIntermediate.getCount() - flatConsumed;
        assertEquals(flatConsumed, checkedOut.stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(checkedOut.stream().allMatch(stack ->
                "runtime-machine".equals(stack.getTag().getString("origin"))));
        assertEquals(flatSurplus, graphSurplus.stream().mapToInt(ItemStack::getCount).sum());
        assertEquals(actualIntermediate.getCount(),
                checkedOut.stream().mapToInt(ItemStack::getCount).sum()
                        + graphSurplus.stream().mapToInt(ItemStack::getCount).sum());
        assertTrue(graph.drainAvailableProducerAssets().isEmpty());
    }

    @Test
    void capOneAndCapTwoProduceSameTerminalMaterialAccounting() {
        assertEquals(runScenario(1), runScenario(2));
    }

    private static Accounting runScenario(int cap) {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource source = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(0), 0));
        broker.publishActual(source, iron, new ItemStack(Items.IRON_INGOT, 4));
        OperationBudget budget = new OperationBudget(cap, 4);
        int consumed = 0;
        for (int operation = 0; operation < 2; operation++) {
            OperationBudget.Permit permit = budget.tryAcquire();
            if (permit == null) {
                permit = budget.tryAcquire();
            }
            assertNotNull(permit);
            MaterialBroker.ReservationToken token = broker.reserve(new NodeId(operation + 1),
                    List.of(new MaterialBroker.Request(source, iron, 1)));
            broker.commit(token);
            consumed += broker.producerFragments(token).stream().mapToInt(ItemStack::getCount).sum();
            broker.settle(token);
            permit.close();
        }
        int delivered = broker.drainAvailableProducerAssets().stream()
                .mapToInt(ItemStack::getCount).sum();
        return new Accounting(consumed, delivered, budget.active());
    }

    private record Accounting(int consumed, int delivered, int activePermits) {}
}
