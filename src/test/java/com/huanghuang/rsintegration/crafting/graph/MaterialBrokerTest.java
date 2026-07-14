package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaterialBrokerTest extends BootstrapTest {

    @Test
    void competingNodesCannotReserveTheSameUnits() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialSource source = new MaterialSource.InitialPool(iron);
        broker.publish(source, iron, 1);

        MaterialBroker.ReservationToken first = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, iron, 1)));
        MaterialBroker.ReservationToken second = broker.reserve(new NodeId(1),
                List.of(new MaterialBroker.Request(source, iron, 1)));

        assertNotNull(first);
        assertNull(second);
        assertEquals(0, broker.available(source, iron));
    }

    @Test
    void releaseMakesUndispatchedAssetsAvailableAgain() {
        MaterialBroker broker = brokerWithDiamonds(2);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, 2)));

        broker.release(token);

        assertEquals(MaterialBroker.ReservationState.RELEASED, broker.state(token));
        assertEquals(2, broker.available(source, diamond));
    }

    @Test
    void committedAssetsCanSettleButCannotRefundAfterSettlement() {
        MaterialBroker broker = brokerWithDiamonds(1);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, 1)));

        broker.commit(token);
        broker.settle(token);

        assertEquals(MaterialBroker.ReservationState.SETTLED, broker.state(token));
        assertThrows(IllegalStateException.class, () -> broker.refund(token));
        assertEquals(0, broker.available(source, diamond));
    }

    @Test
    void committedRefundReturnsAssetsExactlyOnce() {
        MaterialBroker broker = brokerWithDiamonds(1);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, 1)));

        broker.commit(token);
        broker.refund(token);

        assertEquals(1, broker.available(source, diamond));
        assertThrows(IllegalStateException.class, () -> broker.refund(token));
        assertEquals(1, broker.available(source, diamond));
    }

    @Test
    void multiRequestReservationIsAtomic() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey iron = MaterialKey.of(new ItemStack(Items.IRON_INGOT));
        MaterialKey gold = MaterialKey.of(new ItemStack(Items.GOLD_INGOT));
        MaterialSource ironSource = new MaterialSource.InitialPool(iron);
        MaterialSource goldSource = new MaterialSource.InitialPool(gold);
        broker.publish(ironSource, iron, 1);

        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0), List.of(
                new MaterialBroker.Request(ironSource, iron, 1),
                new MaterialBroker.Request(goldSource, gold, 1)));

        assertNull(token);
        assertEquals(1, broker.available(ironSource, iron));
    }

    private static MaterialBroker brokerWithDiamonds(int count) {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        broker.publish(new MaterialSource.InitialPool(diamond), diamond, count);
        return broker;
    }
}
