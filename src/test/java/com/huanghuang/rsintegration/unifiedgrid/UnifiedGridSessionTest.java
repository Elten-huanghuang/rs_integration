package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.api.network.grid.INetworkAwareGrid;
import com.refinedmods.refinedstorage.api.network.grid.handler.IItemGridHandler;
import com.refinedmods.refinedstorage.api.network.grid.handler.IFluidGridHandler;
import com.refinedmods.refinedstorage.api.network.security.ISecurityManager;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCacheListener;
import com.refinedmods.refinedstorage.api.storage.tracker.IStorageTracker;
import com.refinedmods.refinedstorage.api.util.IStackList;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.api.util.StackListResult;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UnifiedGridSessionTest extends BootstrapTest {
    @Test void dualSubscriptionsMergeStoredAndCraftableAndDoNotScanOnIdleTicks() {
        Fixture f = new Fixture();
        f.session.tick();
        UnifiedGridEntry item = f.entry(GridResourceKind.ITEM);
        assertEquals(f.storedItemId, item.storedId());
        assertEquals(f.craftableItemId, item.craftableId());
        assertEquals(17, item.amount());
        assertEquals(1000, f.entry(GridResourceKind.FLUID).amount());
        f.session.tick();
        verify(f.items.list, times(1)).getStacks();
        verify(f.craftItems.list, times(1)).getStacks();
        verify(f.fluids.list, times(1)).getStacks();
        verify(f.itemCache).addListener(any());
        verify(f.fluidCache).addListener(any());
        f.session.close();
        f.session.close();
        verify(f.itemCache, times(1)).removeListener(f.itemListener);
        verify(f.fluidCache, times(1)).removeListener(f.fluidListener);
    }

    @Test void repeatedChangesCoalesceIntoAbsoluteDeltaWithoutTemplate() {
        Fixture f = new Fixture();
        f.session.tick();
        int serial = f.entry(GridResourceKind.ITEM).serial();
        f.frames.clear();
        f.items.put(f.storedItemId, new ItemStack(Items.DIAMOND, Integer.MAX_VALUE));
        f.itemListener.onChanged(new StackListResult<>(new ItemStack(Items.DIAMOND), f.storedItemId, 2));
        f.itemListener.onChanged(new StackListResult<>(new ItemStack(Items.DIAMOND), f.storedItemId, 3));
        f.session.tick();
        UnifiedGridEntry update = f.entry(GridResourceKind.ITEM);
        assertEquals(serial, update.serial());
        assertEquals(Integer.MAX_VALUE, update.amount());
        assertFalse(update.metadata());
        assertNull(update.template());
        assertEquals(1, f.frames.size());
        verify(f.items.list, never()).getEntry(any(), anyInt());
        verify(f.craftItems.list, never()).getEntry(any(), anyInt());
    }

    @Test void storedDeletionRetainsCraftableRowAndTotalDeletionNeverReusesSerial() {
        Fixture f = new Fixture();
        f.session.tick();
        int serial = f.entry(GridResourceKind.ITEM).serial();
        f.frames.clear();
        f.items.clear();
        f.notifyItem();
        f.session.tick();
        UnifiedGridEntry craftable = f.entry(GridResourceKind.ITEM);
        assertEquals(serial, craftable.serial());
        assertNull(craftable.storedId());
        assertEquals(f.craftableItemId, craftable.craftableId());
        assertFalse(craftable.removed());
        f.frames.clear();
        f.craftItems.clear();
        f.notifyItem();
        f.session.tick();
        assertTrue(f.entry(GridResourceKind.ITEM).removed());
        f.frames.clear();
        UUID reinserted = UUID.randomUUID();
        f.items.put(reinserted, new ItemStack(Items.DIAMOND, 9));
        f.itemListener.onChanged(new StackListResult<>(new ItemStack(Items.DIAMOND), reinserted, 9));
        f.session.tick();
        assertTrue(f.entry(GridResourceKind.ITEM).serial() > serial);
    }

    @Test void requestValidatesSessionKindEpochPermissionsAndCurrentNetwork() {
        Fixture f = new Fixture();
        f.session.tick();
        UnifiedGridEntry item = f.entry(GridResourceKind.ITEM);
        UnifiedGridUpdatePacket frame = f.frame(GridResourceKind.ITEM);
        var good = new UnifiedGridActionPacket(0, frame.session(), GridResourceKind.ITEM, frame.epoch(),
                item.serial(), UnifiedGridActionPacket.Action.EXTRACT, 0);
        f.session.handle(new UnifiedGridActionPacket(0, UUID.randomUUID(), good.kind(), good.epoch(), good.serial(), good.action(), 0));
        f.session.handle(new UnifiedGridActionPacket(0, good.session(), GridResourceKind.FLUID, good.epoch(), good.serial(), good.action(), 0));
        f.session.handle(new UnifiedGridActionPacket(0, good.session(), good.kind(), good.epoch() + 1, good.serial(), good.action(), 0));
        verifyNoInteractions(f.itemHandler, f.fluidHandler);
        f.session.handle(good);
        verify(f.itemHandler).onExtract(f.player, f.storedItemId, -1, 0);
        clearInvocations(f.itemHandler);
        when(f.security.hasPermission(Permission.EXTRACT, f.player)).thenReturn(false);
        f.session.handle(good);
        verifyNoInteractions(f.itemHandler);
        when(f.security.hasPermission(Permission.EXTRACT, f.player)).thenReturn(true);
        when(f.grid.getNetwork()).thenReturn(mock(INetwork.class));
        f.session.handle(good);
        verifyNoInteractions(f.itemHandler);
    }

    @Test void categoryInvalidationRejectsOldHandlesAndDoesNotResendOtherKind() {
        Fixture f = new Fixture();
        f.session.tick();
        UnifiedGridEntry old = f.entry(GridResourceKind.ITEM);
        UnifiedGridUpdatePacket before = f.frame(GridResourceKind.ITEM);
        f.frames.clear();
        f.itemListener.onInvalidated();
        f.session.handle(new UnifiedGridActionPacket(0, before.session(), GridResourceKind.ITEM,
                before.epoch(), old.serial(), UnifiedGridActionPacket.Action.EXTRACT, 0));
        verifyNoInteractions(f.itemHandler);
        f.session.tick();
        assertTrue(f.frames.stream().allMatch(packet -> packet.kind() == GridResourceKind.ITEM));
        assertTrue(f.entry(GridResourceKind.ITEM).serial() > old.serial());
        assertTrue(f.frame(GridResourceKind.ITEM).epoch() > before.epoch());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 5})
    void itemExtractionPreservesNativeTargetSlotAndMouseFlags(int flags) {
        Fixture f = new Fixture();
        f.session.tick();
        UnifiedGridEntry item = f.entry(GridResourceKind.ITEM);
        UnifiedGridUpdatePacket frame = f.frame(GridResourceKind.ITEM);
        f.session.handle(new UnifiedGridActionPacket(0, frame.session(), GridResourceKind.ITEM, frame.epoch(),
                item.serial(), UnifiedGridActionPacket.Action.EXTRACT, flags));
        verify(f.itemHandler).onExtract(f.player, f.storedItemId, -1, flags);
        verifyNoInteractions(f.fluidHandler);
    }

    @Test void fluidActionsUseContainerTransferAndRespectInsertAndExtractPermissions() {
        try (MockedStatic<UnifiedGridFluidTransfer> transfers = mockStatic(UnifiedGridFluidTransfer.class)) {
        Fixture f = new Fixture();
        f.session.tick();
        UnifiedGridEntry fluid = f.entry(GridResourceKind.FLUID);
        UnifiedGridUpdatePacket frame = f.frame(GridResourceKind.FLUID);
        var extract = new UnifiedGridActionPacket(0, frame.session(), GridResourceKind.FLUID, frame.epoch(),
                fluid.serial(), UnifiedGridActionPacket.Action.FILL_FLUID, 0);
        var insert = new UnifiedGridActionPacket(0, frame.session(), GridResourceKind.FLUID, frame.epoch(),
                0, UnifiedGridActionPacket.Action.INSERT_FLUID, 0);
        var result = new UnifiedGridFluidTransfer.Result(ItemStack.EMPTY, ItemStack.EMPTY,
                FluidStack.EMPTY, FluidStack.EMPTY, 0);
        transfers.when(() -> UnifiedGridFluidTransfer.fill(eq(f.network), eq(ItemStack.EMPTY), any())).thenReturn(result);
        transfers.when(() -> UnifiedGridFluidTransfer.empty(f.network, ItemStack.EMPTY)).thenReturn(result);
        f.session.handle(extract);
        f.session.handle(insert);
        transfers.verify(() -> UnifiedGridFluidTransfer.fill(eq(f.network), eq(ItemStack.EMPTY), any()));
        transfers.verify(() -> UnifiedGridFluidTransfer.empty(f.network, ItemStack.EMPTY));
        verifyNoInteractions(f.itemHandler);
        transfers.clearInvocations();
        when(f.security.hasPermission(Permission.EXTRACT, f.player)).thenReturn(false);
        when(f.security.hasPermission(Permission.INSERT, f.player)).thenReturn(false);
        f.session.handle(extract);
        f.session.handle(insert);
        transfers.verifyNoInteractions();
        verifyNoInteractions(f.itemHandler, f.fluidHandler);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shiftFilledBucketGoesToInventoryOrStaysOnCursorWhenInventoryIsFull(boolean full) {
        Fixture f = new Fixture();
        f.session.tick();
        Inventory inventory = mock(Inventory.class);
        when(f.player.getInventory()).thenReturn(inventory);
        when(inventory.add(any(ItemStack.class))).thenAnswer(invocation -> {
            ItemStack result = invocation.getArgument(0);
            if (!full) result.setCount(0);
            return !full;
        });
        UnifiedGridUpdatePacket frame = f.frame(GridResourceKind.FLUID);
        var result = new UnifiedGridFluidTransfer.Result(new ItemStack(Items.WATER_BUCKET), ItemStack.EMPTY,
                FluidStack.EMPTY, new FluidStack(Fluids.WATER, 1000), 1000);
        try (MockedStatic<UnifiedGridFluidTransfer> transfers = mockStatic(UnifiedGridFluidTransfer.class)) {
            transfers.when(() -> UnifiedGridFluidTransfer.fill(eq(f.network), eq(ItemStack.EMPTY), any())).thenReturn(result);
            transfers.when(() -> UnifiedGridFluidTransfer.apply(f.player, f.network, result, true, true))
                    .thenCallRealMethod();
            f.session.handle(new UnifiedGridActionPacket(0, frame.session(), GridResourceKind.FLUID, frame.epoch(),
                    f.entry(GridResourceKind.FLUID).serial(), UnifiedGridActionPacket.Action.FILL_FLUID,
                    IItemGridHandler.EXTRACT_SHIFT));
        }
        verify(inventory).add(any(ItemStack.class));
        var cursor = ArgumentCaptor.forClass(ItemStack.class);
        verify(f.menu, times(2)).setCarried(cursor.capture());
        assertEquals(full, !cursor.getValue().isEmpty());
        if (full) assertTrue(cursor.getValue().is(Items.WATER_BUCKET));
        assertEquals(1, result.cursor().getCount(), "背包操作不修改转移结果模板");
        verify(f.network.getFluidStorageTracker()).changed(eq(f.player),
                argThat(stack -> stack.getFluid() == Fluids.WATER && stack.getAmount() == 1000));
    }

    @Test void fillingRejectsWrongKindOldEpochAndDeletedStoredIdentity() {
        Fixture f = new Fixture();
        f.session.tick();
        UnifiedGridUpdatePacket frame = f.frame(GridResourceKind.FLUID);
        int serial = f.entry(GridResourceKind.FLUID).serial();
        try (MockedStatic<UnifiedGridFluidTransfer> transfers = mockStatic(UnifiedGridFluidTransfer.class)) {
            f.session.handle(new UnifiedGridActionPacket(0, frame.session(), GridResourceKind.ITEM, frame.epoch(),
                    serial, UnifiedGridActionPacket.Action.FILL_FLUID, 0));
            f.session.handle(new UnifiedGridActionPacket(0, frame.session(), GridResourceKind.FLUID, frame.epoch() + 1,
                    serial, UnifiedGridActionPacket.Action.FILL_FLUID, 0));
            f.fluids.clear();
            f.session.handle(new UnifiedGridActionPacket(0, frame.session(), GridResourceKind.FLUID, frame.epoch(),
                    serial, UnifiedGridActionPacket.Action.FILL_FLUID, 0));
            transfers.verifyNoInteractions();
        }
    }

    @Test void largeSnapshotSplitsByEntriesAndHoldsDeltasUntilComplete() {
        Fixture f = new Fixture();
        f.items.clear();
        f.craftItems.clear();
        for (int i = 0; i < 50000; i++) {
            ItemStack variant = new ItemStack(Items.DIAMOND, 2);
            variant.getOrCreateTag().putInt("variant", i);
            f.items.put(UUID.randomUUID(), variant);
        }
        f.session.tick();
        assertFalse(f.frames.stream().anyMatch(packet -> packet.kind() == GridResourceKind.ITEM && packet.end()));
        ItemStack changed = new ItemStack(Items.DIAMOND, 77);
        changed.getOrCreateTag().putInt("variant", 0);
        UUID changedId = f.items.rows.get(GridResourceKey.of(GridResourceKind.ITEM, changed)).getId();
        f.items.put(changedId, changed);
        f.itemListener.onChanged(new StackListResult<>(changed, changedId, 75));
        int ticks = 1;
        while (f.frames.stream().noneMatch(packet -> packet.kind() == GridResourceKind.ITEM && packet.end()) && ticks++ < 30)
            f.session.tick();
        f.session.tick();
        assertTrue(ticks < 30);
        long count = f.frames.stream().filter(packet -> packet.kind() == GridResourceKind.ITEM)
                .flatMap(packet -> packet.entries().stream()).filter(UnifiedGridEntry::metadata).count();
        assertEquals(50000, count);
        assertTrue(f.frames.stream().flatMap(packet -> packet.entries().stream())
                .anyMatch(entry -> !entry.metadata() && entry.amount() == 77));
        assertTrue(f.frames.stream().allMatch(packet -> packet.payload().length <= UnifiedGridUpdatePacket.MAX_BYTES));
        assertTrue(f.frames.stream().allMatch(packet -> packet.entries().size() <= UnifiedGridUpdatePacket.MAX_ENTRIES));
    }

    private static final class Fixture {
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final GridContainerMenu menu = mock(GridContainerMenu.class);
        private final INetworkAwareGrid grid = mock(INetworkAwareGrid.class);
        private final INetwork network = mock(INetwork.class);
        private final ISecurityManager security = mock(ISecurityManager.class);
        private final IItemGridHandler itemHandler = mock(IItemGridHandler.class);
        private final IFluidGridHandler fluidHandler = mock(IFluidGridHandler.class);
        private final IStorageCache<ItemStack> itemCache = mock(IStorageCache.class);
        private final IStorageCache<FluidStack> fluidCache = mock(IStorageCache.class);
        private final FakeList<ItemStack> items = new FakeList<>(GridResourceKind.ITEM);
        private final FakeList<ItemStack> craftItems = new FakeList<>(GridResourceKind.ITEM);
        private final FakeList<FluidStack> fluids = new FakeList<>(GridResourceKind.FLUID);
        private final FakeList<FluidStack> craftFluids = new FakeList<>(GridResourceKind.FLUID);
        private final UUID storedItemId = UUID.randomUUID();
        private final UUID craftableItemId = UUID.randomUUID();
        private final List<UnifiedGridUpdatePacket> frames = new ArrayList<>();
        private IStorageCacheListener<ItemStack> itemListener;
        private IStorageCacheListener<FluidStack> fluidListener;
        private final UnifiedGridSession session;

        private Fixture() {
            player.containerMenu = menu;
            when(menu.getGrid()).thenReturn(grid);
            when(menu.getCarried()).thenReturn(ItemStack.EMPTY);
            when(menu.stillValid(player)).thenReturn(true);
            when(grid.getGridType()).thenReturn(GridType.CRAFTING);
            when(grid.getNetwork()).thenReturn(network);
            when(grid.isGridActive()).thenReturn(true);
            when(network.canRun()).thenReturn(true);
            when(network.getSecurityManager()).thenReturn(security);
            when(security.hasPermission(any(), eq(player))).thenReturn(true);
            when(network.getItemStorageCache()).thenReturn(itemCache);
            when(network.getFluidStorageCache()).thenReturn(fluidCache);
            when(network.getItemGridHandler()).thenReturn(itemHandler);
            when(network.getFluidGridHandler()).thenReturn(fluidHandler);
            when(network.getItemStorageTracker()).thenReturn(mock(IStorageTracker.class));
            when(network.getFluidStorageTracker()).thenReturn(mock(IStorageTracker.class));
            when(itemCache.getList()).thenReturn(items.list);
            when(itemCache.getCraftablesList()).thenReturn(craftItems.list);
            when(fluidCache.getList()).thenReturn(fluids.list);
            when(fluidCache.getCraftablesList()).thenReturn(craftFluids.list);
            doAnswer(invocation -> { itemListener = invocation.getArgument(0); itemListener.onAttached(); return null; })
                    .when(itemCache).addListener(any());
            doAnswer(invocation -> { fluidListener = invocation.getArgument(0); fluidListener.onAttached(); return null; })
                    .when(fluidCache).addListener(any());
            items.put(storedItemId, new ItemStack(Items.DIAMOND, 17));
            craftItems.put(craftableItemId, new ItemStack(Items.DIAMOND));
            fluids.put(UUID.randomUUID(), new FluidStack(Fluids.WATER, 1000));
            session = new UnifiedGridSession(menu, player, frames::add);
        }
        private void notifyItem() { itemListener.onChanged(new StackListResult<>(new ItemStack(Items.DIAMOND), storedItemId, -17)); }
        private UnifiedGridEntry entry(GridResourceKind kind) {
            return frames.stream().filter(packet -> packet.kind() == kind).flatMap(packet -> packet.entries().stream()).findFirst().orElseThrow();
        }
        private UnifiedGridUpdatePacket frame(GridResourceKind kind) {
            return frames.stream().filter(packet -> packet.kind() == kind).findFirst().orElseThrow();
        }
    }

    private static final class FakeList<T> {
        private final IStackList<T> list = mock(IStackList.class);
        private final Map<GridResourceKey, StackListEntry<T>> rows = new HashMap<>();
        private final Map<UUID, T> ids = new HashMap<>();
        private final GridResourceKind kind;
        private FakeList(GridResourceKind kind) {
            this.kind = kind;
            when(list.getStacks()).thenAnswer(invocation -> rows.values());
            when(list.getEntry(any(), anyInt())).thenAnswer(invocation -> rows.get(GridResourceKey.of(kind, invocation.getArgument(0))));
            when(list.get(any(UUID.class))).thenAnswer(invocation -> ids.get(invocation.getArgument(0)));
        }
        private void put(UUID id, T stack) {
            rows.put(GridResourceKey.of(kind, stack), new StackListEntry<>(id, stack));
            ids.put(id, stack);
        }
        private void clear() { rows.clear(); ids.clear(); }
    }
}
