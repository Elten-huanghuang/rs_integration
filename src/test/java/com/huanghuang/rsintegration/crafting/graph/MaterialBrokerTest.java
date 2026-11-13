package com.huanghuang.rsintegration.crafting.graph;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.crafting.progress.CraftMaterialView;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialBrokerTest extends BootstrapTest {

    @Test void monitorShortagesConsumeEachAvailableLotOnlyOnceAndDoNotReserveAnything() {
        var broker = new MaterialBroker();
        var copper = MaterialKey.of(new ItemStack(Items.COPPER_INGOT));
        var source = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(7), 0));
        broker.publish(source, copper, 4);
        var requests = List.of(new MaterialBroker.Request(source, copper, 3), new MaterialBroker.Request(source, copper, 3));
        long revision = broker.availabilityRevision();
        assertEquals(List.of(new MaterialBroker.Request(source, copper, 2)), broker.monitorShortages(requests, 17));
        assertEquals(4, broker.available(source, copper));
        assertEquals(revision, broker.availabilityRevision());
        broker.publish(source, copper, 2);
        assertTrue(broker.monitorShortages(requests, 17).isEmpty());
        assertNotNull(broker.reserve(new NodeId(10), requests));
    }

    @Test void monitorSeparatesPlannedInitialPoolFromHeldProducerAssetsWithoutMutatingThem() {
        var broker = new MaterialBroker();
        var diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        var initial = new MaterialSource.InitialPool(diamond);
        var producer = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
        broker.publish(initial, diamond, 9_000_000_000L);
        broker.publish(producer, diamond, 50);
        var reserved = broker.reserve(new NodeId(1), List.of(new MaterialBroker.Request(producer, diamond, 20)));
        assertMonitor(broker, 50, 9_000_000_000L);
        assertEquals(30, broker.available(producer, diamond));
        broker.commit(reserved);
        assertMonitor(broker, 30, 9_000_000_000L);
        broker.refund(reserved);
        assertMonitor(broker, 50, 9_000_000_000L);
        assertEquals(50, broker.available(producer, diamond));
    }

    private static void assertMonitor(MaterialBroker broker, long held, long toExtract) {
        long revision = broker.availabilityRevision();
        var view = new CraftMaterialView.Builder();
        broker.visitMonitorMaterials((key, available, planned) -> view.add(key, available, 0, 0, 0, planned, 0, false));
        assertEquals(1, view.build().rows().size());
        assertEquals(held, view.build().rows().get(0).available());
        assertEquals(toExtract, view.build().rows().get(0).toExtract());
        assertEquals(revision, broker.availabilityRevision());
    }

    @Test
    void increasedSourceNotificationsAreDeduplicatedAndDoNotChangeMaterialState() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
        MaterialSource equalSource = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
        MaterialSource otherPort = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 1));
        broker.publish(source, diamond, 2);
        broker.publish(equalSource, diamond, 1);
        broker.publish(otherPort, diamond, 7);
        List<MaterialBroker.Request> requests = List.of(new MaterialBroker.Request(source, diamond, 5));
        long revision = broker.availabilityRevision();
        List<MaterialSource> sources = drainSources(broker);

        assertEquals(2, sources.size());
        assertEquals(Set.of(source, otherPort), Set.copyOf(sources));
        assertEquals(revision, broker.availabilityRevision());
        assertEquals(10, broker.totalAvailable());
        assertEquals(List.of(new MaterialBroker.Request(source, diamond, 2)), broker.shortages(requests));
        assertTrue(drainSources(broker).isEmpty());
        broker.publish(source, diamond, 2);
        assertEquals(List.of(source), drainSources(broker));
        assertTrue(broker.shortages(requests).isEmpty());
    }

    @Test
    void failedNotificationConsumerLeavesSourcesAvailableForRetry() {
        MaterialBroker broker = brokerWithDiamonds(2);
        assertThrows(IllegalStateException.class, () -> broker.drainIncreasedSources(source -> {
            throw new IllegalStateException("retry");
        }));
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        assertEquals(List.of(new MaterialSource.InitialPool(diamond)), drainSources(broker));
        assertEquals(2, broker.totalAvailable());
    }

    @Test
    void shortageDetailsStayAccurateAcrossChecksPublicationReservationAndRefund() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        List<MaterialBroker.Request> requests = List.of(new MaterialBroker.Request(source, diamond, 5));

        for (int i = 0; i < 3; i++) {
            assertFalse(broker.canReserve(requests));
            assertNull(broker.reserve(new NodeId(0), requests));
            assertEquals(requests, broker.shortages(requests));
        }
        broker.publish(source, diamond, 3);
        assertFalse(broker.canReserve(requests));
        assertEquals(List.of(new MaterialBroker.Request(source, diamond, 2)), broker.shortages(requests));
        assertEquals(3, broker.available(source, diamond));

        broker.publish(source, diamond, 2);
        assertTrue(broker.canReserve(requests));
        assertTrue(broker.shortages(requests).isEmpty());
        assertEquals(5, broker.available(source, diamond));
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0), requests);
        assertNotNull(token);
        assertEquals(requests, broker.shortages(requests));
        assertEquals(5, broker.outstanding(source, diamond));
        broker.commit(token);
        broker.refund(token);
        assertTrue(broker.canReserve(requests));
        assertTrue(broker.shortages(requests).isEmpty());
        assertEquals(5, requests.get(0).quantity());
        assertEquals(5, broker.available(source, diamond));
    }

    @Test
    void indexedQueriesKeepFullSourceAndMaterialIdentityAmongUnrelatedLots() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
        MaterialSource otherPort = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 1));
        MaterialSource initial = new MaterialSource.InitialPool(diamond);
        for (int i = 1; i <= 512; i++) {
            broker.publish(new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(i), 0)), diamond, 8);
        }
        broker.publish(source, diamond, 2);
        broker.publish(otherPort, diamond, 7);
        broker.publish(initial, diamond, 11);
        ItemStack tagged = new ItemStack(Items.DIAMOND, 3);
        tagged.getOrCreateTag().putString("variant", "tagged");
        MaterialKey taggedKey = MaterialKey.of(tagged);
        broker.publishActual(source, taggedKey, tagged);
        MaterialSource equalSource = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
        MaterialKey equalKey = MaterialKey.of(tagged.copy());

        assertEquals(2, broker.available(source, diamond));
        assertEquals(3, broker.available(equalSource, equalKey));
        assertEquals(3, broker.outstanding(equalSource, equalKey));
        List<MaterialBroker.Request> requests =
                List.of(new MaterialBroker.Request(equalSource, equalKey, 4));
        assertFalse(broker.canReserve(requests));
        assertNull(broker.reserve(new NodeId(600), requests));
        assertEquals(List.of(new MaterialBroker.Request(source, taggedKey, 1)), broker.shortages(requests));

        List<ItemStack> drained = broker.drainAvailable(equalSource, equalKey, 5);
        assertEquals(1, drained.size());
        assertEquals(3, drained.get(0).getCount());
        assertEquals("tagged", drained.get(0).getTag().getString("variant"));
        assertEquals(0, broker.outstanding(source, taggedKey));
        assertEquals(2, broker.available(source, diamond));
        assertEquals(7, broker.available(otherPort, diamond));
        assertEquals(11, broker.available(initial, diamond));
        assertEquals(512L * 8 + 2 + 7 + 11, broker.totalAvailable());
    }

    @Test
    void indexedLotsKeepRuntimeNbtAndPublicationOrderWhenReturnedAndDrained() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey declared = MaterialKey.of(new ItemStack(Items.POTION));
        MaterialSource source = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(0), 0));
        MaterialSource other = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(1), 0));
        ItemStack first = new ItemStack(Items.POTION, 2);
        first.getOrCreateTag().putInt("Purity", 1);
        ItemStack second = new ItemStack(Items.POTION, 3);
        second.getOrCreateTag().putInt("Purity", 2);
        broker.publishActual(source, declared, first);
        broker.publish(other, declared, 9);
        broker.publishActual(source, declared, second);
        List<MaterialBroker.Request> requests = List.of(
                new MaterialBroker.Request(source, declared, 3),
                new MaterialBroker.Request(source, declared, 1));
        assertFalse(broker.canReserve(List.of(new MaterialBroker.Request(source, declared, 6))));
        assertTrue(broker.canReserve(requests));
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(2), requests);
        assertNotNull(token);
        List<ItemStack> checkout = broker.checkout(token).producerStacks();
        assertEquals(List.of(2, 2), checkout.stream().map(ItemStack::getCount).toList());
        assertEquals(List.of(1, 2), checkout.stream().map(stack -> stack.getTag().getInt("Purity")).toList());
        broker.release(token);
        assertEquals(5, broker.available(source, declared));

        token = broker.reserve(new NodeId(2), requests);
        assertNotNull(token);
        broker.commit(token);
        broker.refundCommittedProducerFragments(token, List.of(second.copyWithCount(1)));
        broker.settle(token);
        assertEquals(2, broker.outstanding(source, declared));
        List<ItemStack> drained = broker.drainAvailable(source, declared, 5);
        assertEquals(1, drained.size());
        assertEquals(2, drained.get(0).getCount());
        assertEquals(2, drained.get(0).getTag().getInt("Purity"));
        assertEquals(9, broker.totalAvailable());
        assertEquals(9, broker.drainAvailableProducerAssets().get(0).getCount());
        assertEquals(0, broker.totalAvailable());
    }

    @Test
    void consumingAndEmptyTransitionsDoNotAdvanceAvailabilityRevision() {
        MaterialBroker broker = brokerWithDiamonds(4);
        drainSources(broker);
        long revision = broker.availabilityRevision();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, 2)));
        broker.commit(token);
        broker.settle(token);
        broker.takeAvailable(source, diamond, 1);
        assertEquals(revision, broker.availabilityRevision());

        MaterialBroker.ReservationToken empty = broker.reserve(new NodeId(1), List.of());
        broker.release(empty);
        MaterialBroker.ReservationToken committedEmpty = broker.reserve(new NodeId(2), List.of());
        broker.commit(committedEmpty);
        broker.refund(committedEmpty);
        assertEquals(revision, broker.availabilityRevision());
        assertTrue(drainSources(broker).isEmpty());
    }

    @Test
    void availabilityRevisionTracksEveryReturnPathAndIgnoresUnrelatedRecovery() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.ProducerOutput(new OutputPortId(new NodeId(7), 0));
        long revision = broker.availabilityRevision();
        broker.publish(source, diamond, 6);
        assertTrue(broker.availabilityRevision() > revision);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(8),
                List.of(new MaterialBroker.Request(source, diamond, 6)));
        revision = broker.availabilityRevision();
        broker.releaseUnusedReservedFragments(token, List.of(),
                List.of(new ItemStack(Items.DIAMOND)));
        assertTrue(broker.availabilityRevision() > revision);
        broker.commit(token);
        revision = broker.availabilityRevision();
        broker.refundCommittedProducerFragments(token, List.of(new ItemStack(Items.DIAMOND)));
        assertTrue(broker.availabilityRevision() > revision);
        revision = broker.availabilityRevision();
        broker.refundRecoveredProducerFragments(token, List.of(new ItemStack(Items.IRON_INGOT)));
        assertEquals(revision, broker.availabilityRevision());
        broker.refundRecoveredProducerFragments(token, List.of(new ItemStack(Items.DIAMOND)));
        assertTrue(broker.availabilityRevision() > revision);
        revision = broker.availabilityRevision();
        broker.refund(token);
        assertTrue(broker.availabilityRevision() > revision);
        assertEquals(6, broker.available(source, diamond));
        token = broker.reserve(new NodeId(9),
                List.of(new MaterialBroker.Request(source, diamond, 6)));
        revision = broker.availabilityRevision();
        broker.release(token);
        assertTrue(broker.availabilityRevision() > revision);
    }

    @Test
    void partialReturnStillAdvancesRevisionWhenExcessFragmentsAreRejected() {
        MaterialBroker broker = brokerWithDiamonds(1);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, 1)));
        long revision = broker.availabilityRevision();
        assertThrows(IllegalArgumentException.class, () -> broker.releaseUnusedReservedFragments(
                token, List.of(new ItemStack(Items.DIAMOND, 2)), List.of()));
        assertEquals(1, broker.available(source, diamond));
        assertTrue(broker.availabilityRevision() > revision);
    }

    @Test
    void accessFromAnotherThreadFailsBeforeStateCanChange() throws InterruptedException {
        MaterialBroker broker = brokerWithDiamonds(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread otherThread = new Thread(() -> {
            try {
                broker.drainAvailableProducerAssets();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "material-broker-contract-test");

        otherThread.start();
        otherThread.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        assertEquals(1, broker.totalAvailable());
    }

    @Test
    void aggregateQuantitiesSaturateInsteadOfWrapping() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        broker.publish(source, diamond, Integer.MAX_VALUE);
        broker.publish(source, diamond, Integer.MAX_VALUE);

        assertEquals((long) Integer.MAX_VALUE * 2L, broker.available(source, diamond));
        assertEquals((long) Integer.MAX_VALUE * 2L, broker.totalAvailable());

        MaterialBroker.ReservationToken first = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, Integer.MAX_VALUE)));
        MaterialBroker.ReservationToken second = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, Integer.MAX_VALUE)));

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(0, broker.available(source, diamond));
        assertEquals((long) Integer.MAX_VALUE * 2L, broker.heldBy(new NodeId(0)));
    }

    @Test
    void shortagesOnlyExposeUnmetUnitsAndOutstandingIncludesEveryLiveClaim() {
        MaterialBroker broker = brokerWithDiamonds(3);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);
        MaterialBroker.Request request =
                new MaterialBroker.Request(source, diamond, 5);

        assertNull(broker.reserve(new NodeId(0), List.of(request)));
        assertEquals(List.of(new MaterialBroker.Request(source, diamond, 2)),
                broker.shortages(List.of(request)));

        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0),
                List.of(new MaterialBroker.Request(source, diamond, 1)));
        broker.commit(token);

        assertEquals(3, broker.outstanding(source, diamond));
        assertEquals(List.of(new MaterialBroker.Request(source, diamond, 3)),
                broker.shortages(List.of(request)));
        broker.settle(token);
        assertEquals(2, broker.outstanding(source, diamond));
        assertEquals(List.of(new MaterialBroker.Request(source, diamond, 3)),
                broker.shortages(List.of(request)));
    }

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
    void unusedReservedFragmentsReturnToTheirOriginalSource() {
        MaterialBroker broker = new MaterialBroker();
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 2);
        MaterialKey diamond = MaterialKey.of(diamonds);
        MaterialSource initial = new MaterialSource.InitialPool(diamond);
        MaterialSource producer = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(7), 0));
        broker.publishActual(initial, diamond, diamonds.copy());
        broker.publishActual(producer, diamond, diamonds.copy());
        NodeId owner = new NodeId(8);
        MaterialBroker.ReservationToken token = broker.reserve(owner, List.of(
                new MaterialBroker.Request(initial, diamond, 2),
                new MaterialBroker.Request(producer, diamond, 2)));

        broker.releaseUnusedReservedFragments(token,
                List.of(diamonds.copy()), List.of());

        assertEquals(2, broker.available(initial, diamond));
        assertEquals(0, broker.available(producer, diamond));
        assertEquals(2, broker.heldBy(owner));
        broker.commit(token);
        broker.settle(token);
        assertEquals(0, broker.heldBy(owner));
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

        List<MaterialBroker.Request> requests = List.of(
                new MaterialBroker.Request(ironSource, iron, 1),
                new MaterialBroker.Request(goldSource, gold, 1));
        assertFalse(broker.canReserve(requests));
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0), requests);

        assertNull(token);
        assertEquals(1, broker.available(ironSource, iron));
    }

    @Test
    void repeatedRequestsForTheSameLotCannotOverPlanIt() {
        MaterialBroker broker = brokerWithDiamonds(3);
        MaterialKey diamond = MaterialKey.of(new ItemStack(Items.DIAMOND));
        MaterialSource source = new MaterialSource.InitialPool(diamond);

        List<MaterialBroker.Request> requests = List.of(
                new MaterialBroker.Request(source, diamond, 2),
                new MaterialBroker.Request(source, diamond, 2));
        assertFalse(broker.canReserve(requests));
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(0), requests);

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
    void checkedOutProducerClaimStaysCommittedUntilNodeCompletion() {
        MaterialBroker broker = new MaterialBroker();
        ItemStack actual = new ItemStack(Items.DIAMOND, 2);
        MaterialKey diamond = MaterialKey.of(actual);
        MaterialSource producer = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(7), 0));
        broker.publishActual(producer, diamond, actual);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(8),
                List.of(new MaterialBroker.Request(producer, diamond, 2)));

        broker.commit(token);
        MaterialBroker.Checkout checkout = broker.checkout(token);

        assertEquals(2, checkout.producerStacks().get(0).getCount());
        assertEquals(MaterialBroker.ReservationState.COMMITTED, broker.state(token));
        assertEquals(0, broker.drainAvailableProducerAssets().size());

        broker.settle(token);
        assertEquals(MaterialBroker.ReservationState.SETTLED, broker.state(token));
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
    void taglessProducerDeclarationCarriesRuntimeNbtToConsumer() {
        MaterialBroker broker = new MaterialBroker();
        MaterialKey declared = MaterialKey.of(new ItemStack(Items.POTION));
        ItemStack actual = new ItemStack(Items.POTION, 4);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Purity", 3);
        actual.setTag(tag);
        MaterialSource producer = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(9), 0));
        broker.publishActual(producer, declared, actual);

        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(10),
                List.of(new MaterialBroker.Request(producer, declared, 4)));
        assertNotNull(token);
        ItemStack checkedOut = broker.producerFragments(token).get(0);

        assertEquals(4, checkedOut.getCount());
        assertEquals(3, checkedOut.getTag().getInt("Purity"));
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
    void recoveredProducerRefundIgnoresUnrelatedInputsAndSettlesMissingQuantity() {
        MaterialBroker broker = new MaterialBroker();
        ItemStack actual = new ItemStack(Items.DIAMOND, 3);
        MaterialKey material = MaterialKey.of(actual);
        MaterialSource source = new MaterialSource.ProducerOutput(
                new OutputPortId(new NodeId(7), 0));
        broker.publishActual(source, material, actual);
        MaterialBroker.ReservationToken token = broker.reserve(new NodeId(8),
                List.of(new MaterialBroker.Request(source, material, 3)));
        broker.commit(token);

        broker.refundRecoveredProducerFragments(token, List.of(
                new ItemStack(Items.IRON_INGOT, 64), new ItemStack(Items.DIAMOND, 1)));
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

    private static List<MaterialSource> drainSources(MaterialBroker broker) {
        List<MaterialSource> sources = new ArrayList<>();
        broker.drainIncreasedSources(sources::add);
        return sources;
    }
}
