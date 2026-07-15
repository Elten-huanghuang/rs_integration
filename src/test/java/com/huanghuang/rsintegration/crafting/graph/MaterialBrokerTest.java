package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
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
    void deliveredSurplusExcludesUnitsCommittedByDownstream() {
        MaterialBroker broker = brokerWithDiamonds(3);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, 2)));
        broker.commit(token);

        assertEquals(1, broker.takeAvailable(source, diamond, 3));
        assertEquals(0, broker.available(source, diamond));
        broker.settle(token);
        assertEquals(0, broker.takeAvailable(source, diamond, 1));
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

    @Test
    void repeatedRequestsForTheSameLotCannotOverPlanIt() {
        MaterialBroker broker = brokerWithDiamonds(3);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);

        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0), List.of(
                new MaterialBroker.Request(source, diamond, 2),
                new MaterialBroker.Request(source, diamond, 2)));

        assertNull(token);
        assertEquals(3, broker.available(source, diamond));
    }

    @Test
    void oneRequestCanConsumeMultipleLotsWithoutLosingUnits() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        broker.publish(source, diamond, 2);
        broker.publish(source, diamond, 3);

        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, 4)));

        assertNotNull(token);
        assertEquals(1, broker.available(source, diamond));
        assertEquals(4, broker.heldBy(new NodeId(0)));
        broker.commit(token);
        assertEquals(4, broker.heldBy(new NodeId(0)));
        broker.settle(token);
        assertEquals(0, broker.heldBy(new NodeId(0)));
        assertEquals(1, broker.takeAvailable(source, diamond, 5));
        assertEquals(0, broker.available(source, diamond));
    }

    @Test
    void identicalMaterialCannotCrossInitialAndProducerSources() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource initial = new MaterialSource.InitialPool(diamond);
        MaterialSource producer = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(7), 0));
        broker.publish(initial, diamond, 1);
        broker.publish(producer, diamond, 2);

        assertNull(broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(initial, diamond, 2))));
        assertEquals(1, broker.available(initial, diamond));
        assertEquals(2, broker.available(producer, diamond));

        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0), List.of(
                new MaterialBroker.Request(initial, diamond, 1),
                new MaterialBroker.Request(producer, diamond, 2)));
        assertNotNull(token);
        assertEquals(0, broker.available(initial, diamond));
        assertEquals(0, broker.available(producer, diamond));
    }

    @Test
    void committedProducerOutputIsNeverDeliveredAsSurplus() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource producer = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(7), 0));
        broker.publish(producer, diamond, 3);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(producer, diamond, 2)));
        broker.commit(token);

        assertEquals(1, broker.takeAvailable(producer, diamond, 3));
        assertEquals(0, broker.takeAvailable(producer, diamond, 1));
        broker.settle(token);
        assertEquals(0, broker.takeAvailable(producer, diamond, 1));
    }

    @Test
    void actualProducerFragmentsAreCheckedOutAndUnavailableForSurplus() {
        MaterialBroker broker = new MaterialBroker();
        ItemStack actual = new ItemStack(Items.DIAMOND, 3);
        CompoundTag tag = new CompoundTag();
        tag.putString("runtime", "preserved");
        actual.setTag(tag);
        MaterialKey material = MaterialKey.of(actual);
        MaterialSource source = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(7), 0));
        broker.publishActual(source, material, actual);

        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(8),
                List.of(new MaterialBroker.Request(source, material, 2)));

        assertNotNull(token);
        List<ItemStack> checkout = broker.producerFragments(token);
        assertEquals(1, checkout.size());
        assertEquals(2, checkout.get(0).getCount());
        assertEquals("preserved", checkout.get(0).getTag().getString("runtime"));
        List<ItemStack> surplus = broker.drainAvailableProducerAssets();
        assertEquals(1, surplus.size());
        assertEquals(1, surplus.get(0).getCount());
        assertEquals("preserved", surplus.get(0).getTag().getString("runtime"));

        broker.commit(token);
        broker.settle(token);
        assertEquals(0, broker.drainAvailableProducerAssets().size());
    }

    @Test
    void checkoutSeparatesExactInitialAndProducerFragments() {
        MaterialBroker broker = new MaterialBroker();
        ItemStack initialStack = new ItemStack(Items.DIAMOND, 2);
        CompoundTag initialTag = new CompoundTag();
        initialTag.putString("owner", "initial");
        initialStack.setTag(initialTag);
        MaterialKey initialMaterial = MaterialKey.of(initialStack);
        MaterialSource initial = new MaterialSource.InitialPool(initialMaterial);
        broker.publishActual(initial, initialMaterial, initialStack);

        ItemStack producedStack = new ItemStack(Items.DIAMOND, 3);
        CompoundTag producedTag = new CompoundTag();
        producedTag.putString("owner", "producer");
        producedStack.setTag(producedTag);
        MaterialKey producedMaterial = MaterialKey.of(producedStack);
        MaterialSource producer = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(7), 0));
        broker.publishActual(producer, producedMaterial, producedStack);

        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(8), List.of(
                new MaterialBroker.Request(initial, initialMaterial, 1),
                new MaterialBroker.Request(producer, producedMaterial, 2)));
        MaterialBroker.Checkout checkout = broker.checkout(token);

        assertEquals(1, checkout.initialStacks().size());
        assertEquals(1, checkout.initialStacks().get(0).getCount());
        assertEquals("initial", checkout.initialStacks().get(0).getTag().getString("owner"));
        assertEquals(1, checkout.producerStacks().size());
        assertEquals(2, checkout.producerStacks().get(0).getCount());
        assertEquals("producer", checkout.producerStacks().get(0).getTag().getString("owner"));
    }

    @Test
    void releasedActualFragmentReturnsToAvailablePoolExactlyOnce() {
        MaterialBroker broker = new MaterialBroker();
        ItemStack actual = new ItemStack(Items.DIAMOND, 2);
        MaterialKey material = MaterialKey.of(actual);
        MaterialSource source = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(7), 0));
        broker.publishActual(source, material, actual);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(8),
                List.of(new MaterialBroker.Request(source, material, 2)));

        broker.release(token);

        List<ItemStack> returned = broker.drainAvailableProducerAssets();
        assertEquals(1, returned.size());
        assertEquals(2, returned.get(0).getCount());
        assertEquals(0, broker.drainAvailableProducerAssets().size());
    }

    @Test
    void partialCommittedProducerRefundLeavesOnlyDispatchedClaimSettled() {
        MaterialBroker broker = new MaterialBroker();
        ItemStack actual = new ItemStack(Items.DIAMOND, 3);
        MaterialKey material = MaterialKey.of(actual);
        MaterialSource source = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(7), 0));
        broker.publishActual(source, material, actual);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(8),
                List.of(new MaterialBroker.Request(source, material, 3)));
        broker.commit(token);

        broker.refundCommittedProducerFragments(token,
                List.of(new ItemStack(Items.DIAMOND, 1)));
        broker.settle(token);

        List<ItemStack> available = broker.drainAvailableProducerAssets();
        assertEquals(1, available.size());
        assertEquals(1, available.get(0).getCount());
        assertEquals(0, broker.heldBy(new NodeId(8)));
    }

    @Test
    void illegalTransitionsFailWithoutChangingQuantity() {
        MaterialBroker broker = brokerWithDiamonds(2);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        MaterialBroker.ReservationToken released = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, 1)));
        broker.release(released);

        assertThrows(IllegalStateException.class, () -> broker.release(released));
        assertThrows(IllegalStateException.class, () -> broker.commit(released));
        assertThrows(IllegalStateException.class, () -> broker.settle(released));
        assertThrows(IllegalStateException.class, () -> broker.refund(released));
        assertEquals(2, broker.available(source, diamond));

        MaterialBroker.ReservationToken committed = broker.reserve(new NodeId(1),
                List.of(new MaterialBroker.Request(source, diamond, 1)));
        broker.commit(committed);
        assertThrows(IllegalStateException.class, () -> broker.commit(committed));
        assertThrows(IllegalStateException.class, () -> broker.release(committed));
        assertEquals(1, broker.available(source, diamond));
        assertEquals(1, broker.heldBy(new NodeId(1)));
        broker.refund(committed);
        assertEquals(2, broker.available(source, diamond));
        assertThrows(IllegalStateException.class, () -> broker.refund(committed));
        assertThrows(IllegalStateException.class, () -> broker.settle(committed));
        assertEquals(2, broker.available(source, diamond));
    }

    @Test
    void nbtDistinguishesOtherwiseIdenticalMaterials() {
        MaterialBroker broker = new MaterialBroker();
        ItemStack redStack = new ItemStack(Items.DIAMOND);
        CompoundTag redTag = new CompoundTag();
        redTag.putString("variant", "red");
        redStack.setTag(redTag);
        ItemStack blueStack = new ItemStack(Items.DIAMOND);
        CompoundTag blueTag = new CompoundTag();
        blueTag.putString("variant", "blue");
        blueStack.setTag(blueTag);
        MaterialKey red = MaterialKey.of(redStack);
        MaterialKey blue = MaterialKey.of(blueStack);
        MaterialSource redSource = new MaterialSource.InitialPool(red);
        MaterialSource blueSource = new MaterialSource.InitialPool(blue);
        broker.publish(redSource, red, 1);

        assertNull(broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(blueSource, blue, 1))));
        assertEquals(1, broker.available(redSource, red));
        assertEquals(0, broker.available(blueSource, blue));
    }

    @Test
    void rejectsInvalidQuantitiesNullsAndUnknownTokens() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);

        assertThrows(IllegalArgumentException.class, () -> broker.publish(source, diamond, 0));
        assertThrows(IllegalArgumentException.class, () -> broker.publish(source, diamond, -1));
        assertThrows(IllegalArgumentException.class, () -> new MaterialBroker.Request(source, diamond, 0));
        assertThrows(IllegalArgumentException.class, () -> new MaterialBroker.Request(source, diamond, -1));
        assertThrows(IllegalArgumentException.class, () -> broker.takeAvailable(source, diamond, 0));
        assertThrows(IllegalArgumentException.class, () -> broker.takeAvailable(source, diamond, -1));
        assertThrows(NullPointerException.class, () -> broker.reserve(new NodeId(0), null));
        assertThrows(NullPointerException.class, () -> broker.reserve(null, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> broker.commit(new MaterialBroker.ReservationToken(99)));
        assertThrows(IllegalArgumentException.class,
                () -> broker.release(new MaterialBroker.ReservationToken(99)));
        assertThrows(IllegalArgumentException.class,
                () -> broker.settle(new MaterialBroker.ReservationToken(99)));
        assertThrows(IllegalArgumentException.class,
                () -> broker.refund(new MaterialBroker.ReservationToken(99)));
        assertNull(broker.state(new MaterialBroker.ReservationToken(99)));
        assertEquals(0, broker.totalAvailable());
    }

    @Test
    void emptyReservationStillObeysTheStateMachine() {
        MaterialBroker broker = new MaterialBroker();
        NodeId owner = new NodeId(0);
        MaterialBroker.ReservationToken token = broker.reserve(owner, List.of());

        assertNotNull(token);
        assertEquals(MaterialBroker.ReservationState.RESERVED, broker.state(token));
        assertEquals(0, broker.heldBy(owner));
        broker.commit(token);
        broker.settle(token);
        assertEquals(MaterialBroker.ReservationState.SETTLED, broker.state(token));
        assertEquals(0, broker.totalAvailable());
    }

    private static MaterialBroker brokerWithDiamonds(int count) {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        broker.publish(new MaterialSource.InitialPool(diamond), diamond, count);
        return broker;
    }
}
