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

    @Test
    void branchedProducerAssetsRemainReservableAndRecoverable() {
        MaterialBroker broker = new MaterialBroker();
        NodeId boardsNode = new NodeId(1);
        NodeId slabNode = new NodeId(0);
        NodeId terminalNode = new NodeId(2);
        MaterialSource boardsSource = new MaterialSource.ProducerOutput(
                new OutputPortId(boardsNode, 0));
        MaterialSource slabSource = new MaterialSource.ProducerOutput(
                new OutputPortId(slabNode, 0));
        MaterialKey boards = MaterialKey.of(new ItemStack(Items.OAK_PLANKS));
        MaterialKey slabs = MaterialKey.of(new ItemStack(Items.OAK_SLAB));

        broker.publishActual(boardsSource, boards, new ItemStack(Items.OAK_PLANKS, 20));
        MaterialBroker.ReservationToken slabInputs = broker.reserve(slabNode,
                List.of(new MaterialBroker.Request(boardsSource, boards, 12)));
        assertNotNull(slabInputs);
        broker.commit(slabInputs);
        broker.settle(slabInputs);
        broker.publishActual(slabSource, slabs, new ItemStack(Items.OAK_SLAB, 24));

        MaterialBroker.ReservationToken terminalInputs = broker.reserve(terminalNode, List.of(
                new MaterialBroker.Request(slabSource, slabs, 24),
                new MaterialBroker.Request(boardsSource, boards, 4)));
        assertNotNull(terminalInputs);
        broker.release(terminalInputs);

        // Cancelling before the terminal craft returns every unconsumed
        // intermediate exactly once: 24 slabs and 8 remaining boards.
        List<ItemStack> recovered = broker.drainAvailableProducerAssets();
        assertEquals(24, recovered.stream().filter(stack -> stack.is(Items.OAK_SLAB))
                .mapToInt(ItemStack::getCount).sum());
        assertEquals(8, recovered.stream().filter(stack -> stack.is(Items.OAK_PLANKS))
                .mapToInt(ItemStack::getCount).sum());
        assertTrue(broker.drainAvailableProducerAssets().isEmpty());
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
