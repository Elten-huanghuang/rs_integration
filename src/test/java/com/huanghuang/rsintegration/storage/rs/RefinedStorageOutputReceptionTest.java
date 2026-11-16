package com.huanghuang.rsintegration.storage.rs;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.LegacyRsCraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.mixin.refinedstorage.CraftingTaskOutputWritebackMixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingManager;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.network.Network;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RefinedStorageOutputReceptionTest extends BootstrapTest {
    private final ItemStack iron = new ItemStack(Items.IRON_INGOT);

    private TaskOutputReception.Session waiting(INetwork network, long count) {
        var session = TaskOutputReception.open();
        session.bindNetwork(network, () -> true);
        session.prepare(List.of(new TaskOutputReception.Demand("out",
                TaskOutputReception.resource(iron), count, count))).activate();
        return session;
    }

    @Test
    void nativeAndLegacySessionsUseTheSameLiveNetworkAndRevokeInvalidEligibility() {
        INetwork network = mock(INetwork.class, RETURNS_DEEP_STUBS);
        when(network.canRun()).thenReturn(true);
        when(network.getLevel()).thenReturn(mock(ServerLevel.class));
        StorageSession nativeSession = new RefinedStorageSession(new NativeRefinedStorageDriver(network),
                new StorageReference(RefinedStorageIds.BACKEND, "test"), (p, ref, stack) -> {});
        StorageSession legacySession = new LegacyRsCraftStorageEndpoint(network).session();
        var key = TaskOutputReception.resource(iron);
        for (StorageSession storage : List.of(nativeSession, legacySession)) {
            when(network.canRun()).thenReturn(true);
            try (var reception = TaskOutputReception.open()) {
                storage.bindOutputReception(reception);
                reception.prepare(List.of(new TaskOutputReception.Demand("out", key, 4, 4))).activate();
                assertEquals(0, TaskOutputReception.receive(mock(INetwork.class), key, 1, false));
                assertEquals(1, TaskOutputReception.receive(network, key, 1, false));
                when(network.canRun()).thenReturn(false);
                TaskOutputReception.pruneNetwork(network);
                assertEquals(0, TaskOutputReception.receive(network, key, 3, false));
                when(network.canRun()).thenReturn(true);
                assertEquals(0, TaskOutputReception.receive(network, key, 3, false));
                assertEquals(1, reception.drain().get(0).amount());
            }
        }
    }

    @Test
    void nativeCraftWritebackScopeBypassesWaitingTask() throws Exception {
        INetwork network = mock(INetwork.class);
        AtomicInteger stored = new AtomicInteger();
        when(network.insertItem(any(), anyInt(), any())).thenAnswer(call ->
                RefinedStorageOutputReception.insertItem(network, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), (stack, size) -> {
                            stored.addAndGet(size);
                            return ItemStack.EMPTY;
                        }));
        CraftingTaskOutputWritebackMixin scope = mock(CraftingTaskOutputWritebackMixin.class, CALLS_REAL_METHODS);
        Method write = CraftingTaskOutputWritebackMixin.class.getDeclaredMethod("rsi$writeItem",
                INetwork.class, ItemStack.class, int.class, Action.class, Operation.class);
        write.setAccessible(true);
        Operation<ItemStack> original = args -> ((INetwork) args[0]).insertItem(
                (ItemStack) args[1], (Integer) args[2], (Action) args[3]);
        try (var session = waiting(network, 5)) {
            assertTrue(((ItemStack) write.invoke(scope, network, iron, 3, Action.PERFORM, original)).isEmpty());
            assertEquals(3, stored.get());
            assertEquals(0, session.received(TaskOutputReception.resource(iron)));
            assertTrue(network.insertItem(iron, 3, Action.PERFORM).isEmpty());
            assertEquals(3, session.received(TaskOutputReception.resource(iron)));
            assertEquals(3, stored.get());
        }
    }

    @Test
    void trackedClaimsBeforeNativeCraftAndOrdinaryInsertionGetsOnlyRemainder() {
        INetwork network = mock(INetwork.class);
        ICraftingManager crafting = mock(ICraftingManager.class);
        when(network.getCraftingManager()).thenReturn(crafting);
        when(crafting.track(any(ItemStack.class), anyInt())).thenAnswer(call -> {
            assertEquals(7, call.getArgument(1, Integer.class));
            assertEquals(7, call.getArgument(0, ItemStack.class).getCount());
            return 4; // 原生合成实际持有三件。
        });
        AtomicInteger disk = new AtomicInteger();
        when(network.insertItem(any(), anyInt(), any())).thenAnswer(call ->
                RefinedStorageOutputReception.insertItem(network, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), (stack, size) -> {
                            assertEquals(4, size);
                            disk.addAndGet(2);
                            return stack.copyWithCount(2);
                        }));
        try (var session = waiting(network, 5)) {
            ItemStack remainder = RefinedStorageOutputReception.insertItemTracked(network, iron, 12);
            assertEquals(2, remainder.getCount());
            assertEquals(5, session.drain().get(0).amount());
            assertEquals(2, disk.get());
            verify(crafting, times(1)).track(any(ItemStack.class), eq(7));
        }
    }

    @Test
    void fullClaimNeverReachesNativeTrackingOrDisk() {
        INetwork network = mock(INetwork.class);
        try (var session = waiting(network, 4)) {
            assertTrue(RefinedStorageOutputReception.insertItemTracked(network, iron, 4).isEmpty());
            verifyNoInteractions(network);
            assertEquals(4, session.received(TaskOutputReception.resource(iron)));
        }
    }

    @Test
    void unrelatedItemBypassesNbtCopyAndKeepsOriginalStackAmountAndAction() {
        INetwork network = mock(INetwork.class);
        CompoundTag tag = spy(new CompoundTag());
        tag.putString("grade", "pure");
        ItemStack gold = new ItemStack(Items.GOLD_INGOT);
        gold.setTag(tag);
        clearInvocations(tag);
        try (var session = waiting(network, 4)) {
            for (Action action : List.of(Action.SIMULATE, Action.PERFORM)) {
                assertSame(gold, RefinedStorageOutputReception.insertItem(
                        network, gold, 12, action, (stack, amount) -> {
                            assertSame(gold, stack);
                            assertEquals(12, amount);
                            return stack;
                        }));
            }
            verify(tag, never()).copy();
            assertEquals(0, session.received(TaskOutputReception.resource(iron)));
            assertEquals(4, session.current().remaining("out"));
        }
    }

    @Test
    void unrelatedFluidBypassesNbtCopyAndSameItemTypeStillRequiresExactNbt() {
        INetwork network = mock(INetwork.class);
        CompoundTag tag = spy(new CompoundTag());
        tag.putString("grade", "pure");
        FluidStack lava = new FluidStack(Fluids.LAVA, 1);
        lava.setTag(tag);
        clearInvocations(tag);
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        try (var session = waiting(network, 4); var fluidSession = TaskOutputReception.open()) {
            fluidSession.bindNetwork(network, () -> true);
            fluidSession.prepare(List.of(new TaskOutputReception.Demand(
                    "water", TaskOutputReception.resource(water), 10, 10))).activate();
            for (Action action : List.of(Action.SIMULATE, Action.PERFORM)) {
                assertSame(lava, RefinedStorageOutputReception.insertFluid(
                        network, lava, 12, action, (stack, amount) -> {
                            assertSame(lava, stack);
                            assertEquals(12, amount);
                            return stack;
                        }));
            }
            verify(tag, never()).copy();
            ItemStack taggedIron = iron.copy();
            taggedIron.getOrCreateTag().putString("grade", "pure");
            assertSame(taggedIron, RefinedStorageOutputReception.insertItem(
                    network, taggedIron, 4, Action.PERFORM, (stack, amount) -> stack));
            assertEquals(0, session.received(TaskOutputReception.resource(iron)));
            assertTrue(RefinedStorageOutputReception.insertItem(
                    network, iron, 4, Action.PERFORM, (stack, amount) -> stack).isEmpty());
            assertEquals(4, session.received(TaskOutputReception.resource(iron)));
            assertEquals(0, fluidSession.received(TaskOutputReception.resource(water)));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void realRsStorageLoopReceivesReducedAmountAndSimulationDoesNotConsume() throws Exception {
        Network network = mock(Network.class, CALLS_REAL_METHODS);
        IStorageCache<ItemStack> cache = mock(IStorageCache.class);
        IStorage<ItemStack> disk = mock(IStorage.class);
        when(cache.getStorages()).thenReturn(List.of(disk));
        when(disk.insert(any(), anyInt(), any())).thenAnswer(call -> {
            assertEquals(5, call.getArgument(1, Integer.class));
            return call.getArgument(0, ItemStack.class).copyWithCount(2);
        });
        Field field = Network.class.getDeclaredField("itemStorage");
        field.setAccessible(true);
        field.set(network, cache);
        try (var session = waiting(network, 4)) {
            for (Action action : List.of(Action.SIMULATE, Action.SIMULATE, Action.PERFORM)) {
                ItemStack result = RefinedStorageOutputReception.insertItem(network, iron, 9, action,
                        (stack, size) -> network.insertItem(stack, size, action));
                assertEquals(2, result.getCount());
                assertEquals(action == Action.SIMULATE ? 0 : 4, session.received(TaskOutputReception.resource(iron)));
            }
            assertEquals(1, iron.getCount());
        }
    }

    @Test
    void trackedFluidsAndNbtUseSameSharedHolding() {
        INetwork network = mock(INetwork.class);
        ICraftingManager crafting = mock(ICraftingManager.class);
        when(network.getCraftingManager()).thenReturn(crafting);
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        water.getOrCreateTag().putString("grade", "pure");
        when(crafting.track(any(FluidStack.class), anyInt())).thenAnswer(call -> {
            assertEquals(400, call.getArgument(1, Integer.class));
            assertTrue(water.isFluidEqual(call.getArgument(0, FluidStack.class)));
            return 200;
        });
        when(network.insertFluid(any(), anyInt(), any())).thenAnswer(call ->
                RefinedStorageOutputReception.insertFluid(network, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), (stack, size) -> {
                            assertEquals(200, size);
                            stack.setAmount(50);
                            return stack;
                        }));
        try (var session = TaskOutputReception.open()) {
            session.bindNetwork(network, () -> true);
            var batch = session.prepare(List.of(new TaskOutputReception.Demand("water",
                    TaskOutputReception.resource(water), 600, 600)));
            batch.activate();
            batch.collect(TaskOutputReception.resource(water), 100);
            FluidStack result = RefinedStorageOutputReception.insertFluidTracked(network, water, 900);
            assertEquals(50, result.getAmount());
            assertTrue(water.isFluidEqual(result));
            assertEquals(600, session.drain().get(0).amount());
            assertEquals(1, water.getAmount());
        }
    }

    @Test
    void legacyEndpointInternalWritebackBypassesOtherWaitingTasks() {
        INetwork network = mock(INetwork.class);
        when(network.insertItem(any(), anyInt(), any())).thenAnswer(call ->
                RefinedStorageOutputReception.insertItem(network, call.getArgument(0), call.getArgument(1),
                        call.getArgument(2), (stack, size) -> {
                            assertEquals(3, size);
                            return ItemStack.EMPTY;
                        }));
        CraftStorageEndpoint endpoint = new LegacyRsCraftStorageEndpoint(network);
        try (var session = waiting(network, 5)) {
            assertEquals(3L, CraftStorageEndpoint.insertInternal(endpoint, iron.copyWithCount(3), false)
                    .transferredAmount().orElseThrow());
            assertEquals(0, session.received(TaskOutputReception.resource(iron)));
            assertTrue(network.insertItem(iron, 3, Action.PERFORM).isEmpty());
            assertEquals(3, session.received(TaskOutputReception.resource(iron)));
        }
    }
}
