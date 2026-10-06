package com.huanghuang.rsintegration.resonance.disk;

import com.huanghuang.rsintegration.resonance.api.ResonanceStorageView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.cache.IStorageCache;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskListener;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskManager;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.DiskDriveNetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.ItemDriveWrapperStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.storage.disk.ItemStorageDisk;
import com.refinedmods.refinedstorage.apiimpl.util.Comparer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResonanceDiskMutationTest extends BootstrapTest {
    private MockedStatic<API> apiMock;
    private ItemStorageDisk delegate;
    private ResonanceDiskWrapper disk;
    private IStorageDiskListener listener;
    private IStorageDiskManager manager;
    private IStorageCache<ItemStack> networkCache;

    @BeforeEach
    void setup() {
        ServerLevel level = mock(ServerLevel.class);
        IRSAPI api = mock(IRSAPI.class);
        manager = mock(IStorageDiskManager.class);
        when(api.getComparer()).thenReturn(new Comparer());
        when(api.getStorageDiskManager(level)).thenReturn(manager);
        apiMock = mockStatic(API.class);
        apiMock.when(API::instance).thenReturn(api);

        delegate = spy(new ItemStorageDisk(level, 2304, UUID.randomUUID()));
        disk = new ResonanceDiskWrapper(delegate);
        listener = mock(IStorageDiskListener.class);
        INetwork network = mock(INetwork.class);
        networkCache = mock(IStorageCache.class);
        when(network.getItemStorageCache()).thenReturn(networkCache);
        DiskDriveNetworkNode node = mock(DiskDriveNetworkNode.class);
        when(node.getNetwork()).thenReturn(network);
        when(node.getAccessType()).thenReturn(AccessType.INSERT_EXTRACT);
        disk.setSettings(listener, node);
    }

    @AfterEach
    void closeApiMock() {
        if (apiMock != null) apiMock.close();
    }

    @Test
    void insertAndExtractRefreshQueriesAndKeepPersistenceWithoutResettingNetwork() {
        assertFalse(disk.hasItem(stack -> true));
        assertTrue(disk.manualInsert(4, new ItemStack(Items.DIAMOND), 3, Action.PERFORM).isEmpty());
        assertEquals(1, disk.contentRevision());
        assertEquals(3, disk.countItems(stack -> stack.is(Items.DIAMOND)));
        assertEquals(1, disk.extractExactView(4, new ItemStack(Items.DIAMOND), 1, false).getCount());
        assertEquals(2, disk.contentRevision());
        assertEquals(2, disk.countItems(stack -> stack.is(Items.DIAMOND)));
        assertTrue(disk.getStacks().isEmpty());
        verify(listener, times(2)).onChanged();
        verify(manager, times(2)).markForSaving();
        verifyNoInteractions(networkCache);
    }

    @Test
    void sameCountNbtMutationInvalidatesQueriesOnceAndKeepsVariantsSeparate() {
        ItemStack first = charm("first");
        ItemStack second = charm("second");
        disk.manualInsert(1, first, 1, Action.PERFORM);
        disk.manualInsert(2, second, 1, Action.PERFORM);
        assertTrue(disk.hasItem(stack -> stack.getDamageValue() == 0));
        long revision = disk.contentRevision();

        ItemStack changed = first.copy();
        changed.setDamageValue(12);
        assertEquals(ResonanceStorageView.SlotMutationResult.SUCCESS,
                disk.reconcileSlotView(1, first, changed));

        assertEquals(revision + 1, disk.contentRevision());
        assertEquals(1, disk.countItems(stack -> stack.getDamageValue() == 12));
        assertEquals(1, disk.countItems(stack ->
                "second".equals(stack.getTag().getString("Potion")) && stack.getDamageValue() == 0));
        assertEquals(2, disk.getStored());
        verify(manager, atLeastOnce()).markForSaving();
        verifyNoInteractions(networkCache);
    }

    @Test
    void simulationsAndRejectedMutationsLeaveCachedStateValid() {
        ItemStack original = charm("original");
        disk.manualInsert(5, original, 1, Action.PERFORM);
        assertTrue(disk.hasItem(stack -> true));
        long revision = disk.contentRevision();
        clearInvocations(delegate, listener, manager);

        assertEquals(1, disk.extractExactView(5, original, 1, true).getCount());
        assertTrue(disk.insertView(6, new ItemStack(Items.DIAMOND), 2, true).isEmpty());
        assertEquals(ResonanceStorageView.SlotMutationResult.REJECTED,
                disk.reconcileSlotView(5, charm("wrong"), charm("replacement")));
        assertEquals(ResonanceStorageView.SlotMutationResult.SUCCESS,
                disk.reconcileSlotView(5, original, original.copy()));

        assertEquals(revision, disk.contentRevision());
        assertEquals(1, disk.countItems(stack -> true));
        // 模拟精确提取会读取槽位；后续查询继续使用原缓存。
        verify(delegate, times(2)).getStacks();
        verifyNoInteractions(listener, manager, networkCache);
    }

    @Test
    void remappingAndSplittingKeepFreshLogicalSlots() {
        ItemStack legacy = charm("legacy");
        disk.manualInsert(0, legacy, 2, Action.PERFORM);
        assertTrue(disk.hasItem(stack -> true));
        long revision = disk.contentRevision();

        assertEquals(1, disk.splitLogicallyNonStackableStacks());
        assertEquals(revision + 1, disk.contentRevision());
        assertEquals(2, disk.countItems(stack -> stack.getCount() == 1));
        assertEquals(2, disk.storedStacks().stream().map(ResonanceStorageView.StoredStack::slot).distinct().count());
        ItemStack exact = delegate.getStacks().iterator().next().copy();
        assertTrue(disk.moveSlot(exact, 7));
        assertEquals(revision + 2, disk.contentRevision());
        assertTrue(disk.storedStacks().stream().anyMatch(stored -> stored.slot() == 7));
        verifyNoInteractions(networkCache);
    }

    @Test
    void diskDriveStillUpdatesItsStateWhenResonanceDiskFills() {
        DiskDriveNetworkNode node = mock(DiskDriveNetworkNode.class);
        ItemStorageDisk smallDelegate = new ItemStorageDisk(null, 1, UUID.randomUUID());
        ResonanceDiskWrapper small = new ResonanceDiskWrapper(smallDelegate);
        new ItemDriveWrapperStorageDisk(node, small);

        small.manualInsert(0, new ItemStack(Items.DIAMOND), 1, Action.PERFORM);
        small.manualExtract(0, new ItemStack(Items.DIAMOND), 1, 0, Action.PERFORM);

        assertEquals(2, mockingDetails(node).getInvocations().stream()
                .filter(invocation -> "requestBlockUpdate".equals(invocation.getMethod().getName()))
                .count());
        verify(node, never()).getNetwork();
    }

    private static ItemStack charm(String potion) {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.getOrCreateTag().putString("Potion", potion);
        return stack;
    }
}
