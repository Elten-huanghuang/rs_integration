package com.huanghuang.rsintegration.disk;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.RSStorageConfig;
import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.persistence.DiskFileStore;
import com.huanghuang.rsintegration.disk.rs.UnifiedBoundDisk;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskItem;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskManager;
import com.huanghuang.rsintegration.disk.rs.UnifiedDiskRoot;
import com.huanghuang.rsintegration.disk.rs.UnifiedMountCoordinator;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.node.INetworkNodeProxy;
import com.refinedmods.refinedstorage.api.storage.AccessType;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDiskListener;
import com.refinedmods.refinedstorage.api.util.Action;
import com.refinedmods.refinedstorage.api.util.IComparer;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.DiskDriveNetworkNode;
import com.refinedmods.refinedstorage.apiimpl.network.node.diskdrive.ItemDriveWrapperStorageDisk;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.IItemHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UnifiedDiskAdapterTest extends BootstrapTest {
    @TempDir Path world;
    private MinecraftServer server;
    private ServerLevel level;
    private UnifiedDiskManager manager;
    private UnifiedDiskRoot root;
    private ServerChunkCache chunks;
    private LevelChunk chunk;
    private Map<BlockPos, BlockEntity> blocks;
    private DiskDriveNetworkNode node;
    private AtomicReference<ItemStack> current;
    private IStorageDisk<?>[] items, fluids;

    private void setup() throws Exception {
        server = mock(MinecraftServer.class); when(server.isSameThread()).thenReturn(true);
        when(server.getWorldPath(LevelResource.ROOT)).thenReturn(world);
        level = mock(ServerLevel.class); when(level.getServer()).thenReturn(server);
        chunks = mock(ServerChunkCache.class); when(level.getChunkSource()).thenReturn(chunks);
        chunk = mock(LevelChunk.class); when(chunks.getChunkNow(0, 0)).thenReturn(chunk);
        blocks = new HashMap<>(); when(chunk.getBlockEntities()).thenReturn(blocks);
        // 这些入口会重新请求区块或恢复方块实体，挂载检查绝不能调用。
        when(level.getBlockEntity(any())).thenThrow(new AssertionError("不能同步加载磁盘仓所在区块"));
        manager = UnifiedDiskManager.get(level); root = manager.create(UUID.randomUUID());
        node = mock(DiskDriveNetworkNode.class);
        when(node.getLevel()).thenReturn(level); when(node.getPos()).thenReturn(BlockPos.ZERO);
        IItemHandler inventory = mock(IItemHandler.class); when(node.getDisks()).thenReturn(inventory);
        // 单测注册表已经冻结，模拟新注册的物品类型，NBT/数量仍使用真实 ItemStack。
        ItemStack physical = spy(new ItemStack(Items.STONE));
        doReturn(mock(UnifiedDiskItem.class, CALLS_REAL_METHODS)).when(physical).getItem();
        doAnswer(invocation -> new ItemStack(Items.STONE, invocation.getArgument(0))).when(physical).copyWithCount(anyInt());
        ((UnifiedDiskItem) physical.getItem()).setIdentity(physical, root.id(), root.worldId());
        current = new AtomicReference<>(physical);
        when(inventory.getStackInSlot(0)).thenAnswer(invocation -> current.get());
        BlockEntity block = mock(BlockEntity.class, withSettings().extraInterfaces(INetworkNodeProxy.class));
        when(((INetworkNodeProxy<?>) block).getNode()).thenReturn(node);
        blocks.put(BlockPos.ZERO, block);
        items = new IStorageDisk<?>[8]; fluids = new IStorageDisk<?>[8];
    }

    private UnifiedMountCoordinator.Lease lease() {
        return manager.mounts().acquire(root, node, 0, items, fluids, current.get(), () -> {});
    }
    @AfterEach void shutdown() { if (server != null) UnifiedDiskManager.stop(server); RSStorageConfig.SPEC.setConfig(null); }

    @Test void oldDiskLoadsWithConfiguredCapacityAndSummarySurvivesRestart() throws Exception {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("unifiedDisk.maxItemEntries", 65536);
        config.set("unifiedDisk.maxFluidEntries", 65536);
        RSStorageConfig.SPEC.setConfig(config);
        setup();
        var original = manager.entry(root.id()).core;
        original.insertItem(new ItemStack(Items.DIAMOND), 447, true);
        original.insertFluid(new FluidStack(Fluids.WATER, 1), 5790, true);
        UUID diskId = root.id();
        UnifiedDiskManager.stop(server);
        var files = new DiskFileStore(world.resolve("data/rs_integration/unified_disks"));
        assertEquals(65536, files.manifest(diskId).limits().items());
        config.set("unifiedDisk.maxItemEntries", 262144);
        config.set("unifiedDisk.maxFluidEntries", 262144);
        RSStorageConfig.SPEC.setConfig(config);
        manager = UnifiedDiskManager.get(level);
        var summary = manager.summary(diskId);
        assertEquals(447, summary.items()); assertEquals(5790, summary.fluids());
        assertEquals(262144, summary.itemCapacity()); assertEquals(262144, summary.fluidCapacity());
        assertEquals(262144, files.manifest(diskId).limits().items());
        UnifiedDiskManager.stop(server);
        config.set("unifiedDisk.maxItemEntries", 65536);
        config.set("unifiedDisk.maxFluidEntries", 65536);
        RSStorageConfig.SPEC.setConfig(config);
        manager = UnifiedDiskManager.get(level);
        assertEquals(summary, manager.summary(diskId));
    }

    @Test void loadingChunkReturnsImmediatelyAndSameWrapperRecoversAfterAttachment() throws Exception {
        setup();
        var core = manager.entry(root.id()).core;
        core.insertItem(new ItemStack(Items.DIAMOND), 17, true);
        when(chunks.getChunkNow(0, 0)).thenReturn(null);
        var lease = lease();
        var item = new UnifiedBoundDisk<ItemStack>(lease, core, FrozenKey.Kind.ITEM);
        // 原卡死调用链：恢复节点槽位时，RS wrapper 构造器立即读取 getStored()。
        var wrapper = new ItemDriveWrapperStorageDisk(node, item);
        assertEquals(0, wrapper.getStored());
        assertFalse(lease.valid());
        assertEquals(3, item.insert(new ItemStack(Items.DIAMOND), 3, Action.PERFORM).getCount());
        assertTrue(item.extract(new ItemStack(Items.DIAMOND), 1, 0, Action.PERFORM).isEmpty());
        assertEquals(17, core.items.total());
        when(chunks.getChunkNow(0, 0)).thenReturn(chunk);
        assertTrue(lease.valid());
        assertEquals(17, wrapper.getStored());
        assertTrue(item.insert(new ItemStack(Items.DIAMOND), 3, Action.PERFORM).isEmpty());
        assertEquals(20, wrapper.getStored());
        verify(level, never()).getBlockEntity(any());
    }

    @Test void pendingBlockEntityIsNotRestoredAndRemovedBlockRevokesBothViews() throws Exception {
        setup();
        BlockEntity block = blocks.remove(BlockPos.ZERO);
        var lease = lease();
        var core = manager.entry(root.id()).core;
        items[0] = new UnifiedBoundDisk<ItemStack>(lease, core, FrozenKey.Kind.ITEM);
        fluids[0] = new UnifiedBoundDisk<FluidStack>(lease, core, FrozenKey.Kind.FLUID);
        assertFalse(lease.valid());
        blocks.put(BlockPos.ZERO, block);
        assertTrue(lease.valid());
        blocks.clear();
        manager.mounts().sweep();
        assertFalse(lease.valid());
        assertNull(items[0]); assertNull(fluids[0]);
        assertFalse(manager.mounts().mounted(root.id()));
        verify(level, never()).getBlockEntity(any());
    }

    @Test void unloadedLeaseHandsOverToWaitingDiskWithoutRequestingChunks() throws Exception {
        setup();
        var first = lease(); assertTrue(first.valid());
        DiskDriveNetworkNode other = mock(DiskDriveNetworkNode.class);
        BlockPos otherPos = new BlockPos(16, 0, 0);
        when(other.getLevel()).thenReturn(level); when(other.getPos()).thenReturn(otherPos);
        IItemHandler otherInventory = node.getDisks();
        when(other.getDisks()).thenReturn(otherInventory);
        LevelChunk otherChunk = mock(LevelChunk.class);
        BlockEntity otherBlock = mock(BlockEntity.class, withSettings().extraInterfaces(INetworkNodeProxy.class));
        when(((INetworkNodeProxy<?>) otherBlock).getNode()).thenReturn(other);
        when(otherChunk.getBlockEntities()).thenReturn(Map.of(otherPos, otherBlock));
        when(chunks.getChunkNow(1, 0)).thenReturn(otherChunk);
        IStorageDisk<?>[] otherItems = new IStorageDisk<?>[8], otherFluids = new IStorageDisk<?>[8];
        AtomicReference<UnifiedMountCoordinator.Lease> replacement = new AtomicReference<>();
        Runnable retry = () -> replacement.set(manager.mounts().acquire(root, other, 0,
                otherItems, otherFluids, current.get(), () -> {}));
        assertNull(manager.mounts().acquire(root, other, 0, otherItems, otherFluids, current.get(), retry));
        when(chunks.getChunkNow(0, 0)).thenReturn(null);
        manager.mounts().sweep();
        assertFalse(first.valid());
        assertNotNull(replacement.get()); assertTrue(replacement.get().valid());
        verify(level, never()).getBlockEntity(any());
    }

    @Test void itemAndFluidViewsPreservePermissionsSimulationAndIndependentListeners() throws Exception {
        setup(); var lease = lease(); assertTrue(lease.valid());
        var core = manager.entry(root.id()).core;
        UnifiedBoundDisk<ItemStack> item = new UnifiedBoundDisk<>(lease, core, FrozenKey.Kind.ITEM);
        UnifiedBoundDisk<FluidStack> fluid = new UnifiedBoundDisk<>(lease, core, FrozenKey.Kind.FLUID);
        IStorageDiskListener itemListener = mock(IStorageDiskListener.class), fluidListener = mock(IStorageDiskListener.class);
        AtomicReference<AccessType> access = new AtomicReference<>(AccessType.INSERT_EXTRACT);
        item.setSettings(itemListener, access::get); fluid.setSettings(fluidListener, access::get);
        ItemStack stone = new ItemStack(Items.STONE);
        assertTrue(item.insert(stone, Integer.MAX_VALUE, Action.SIMULATE).isEmpty());
        assertEquals(0, item.getStored()); verifyNoInteractions(itemListener, fluidListener);
        assertTrue(item.insert(stone, Integer.MAX_VALUE, Action.PERFORM).isEmpty());
        FluidStack water = new FluidStack(Fluids.WATER, 1);
        assertTrue(fluid.insert(water, Integer.MAX_VALUE, Action.PERFORM).isEmpty());
        assertEquals(Integer.MAX_VALUE, item.getStored()); assertEquals(Integer.MAX_VALUE, fluid.getStored());
        verify(itemListener, times(1)).onChanged(); verify(fluidListener, times(1)).onChanged();
        access.set(AccessType.INSERT);
        assertTrue(item.extract(stone, 1, IComparer.COMPARE_NBT, Action.PERFORM).isEmpty());
        assertEquals(0, item.getCacheDelta(0, 7, ItemStack.EMPTY));
        access.set(AccessType.EXTRACT);
        assertEquals(7, item.insert(stone, 7, Action.PERFORM).getCount());
        assertEquals(1, fluid.extract(water, 1, IComparer.COMPARE_NBT, Action.PERFORM).getAmount());
        ItemStack returned = item.getStacks().iterator().next(); returned.setCount(0);
        assertEquals(Integer.MAX_VALUE, item.getStored());
        access.set(AccessType.INSERT_EXTRACT);
        assertTrue(item.insert(current.get(), 1, Action.PERFORM).getCount() > 0);
    }

    @Test void fuzzyExtractionReturnsOneActualVariantAndQuantityFlagIsHonored() throws Exception {
        setup(); var lease = lease(); var core = manager.entry(root.id()).core;
        UnifiedBoundDisk<ItemStack> item = new UnifiedBoundDisk<>(lease, core, FrozenKey.Kind.ITEM);
        ItemStack one = UnifiedDiskCoreTest.variant(1).itemStack(1), two = UnifiedDiskCoreTest.variant(2).itemStack(1);
        item.insert(one, 7, Action.PERFORM); item.insert(two, 8, Action.PERFORM);
        ItemStack result = item.extract(new ItemStack(Items.STONE), 20, 0, Action.SIMULATE);
        assertEquals(7, result.getCount()); assertEquals(1, result.getTag().getInt("variant"));
        assertEquals(15, item.getStored());
        result = item.extract(new ItemStack(Items.STONE, 8), 20, IComparer.COMPARE_QUANTITY, Action.PERFORM);
        assertEquals(8, result.getCount()); assertEquals(2, result.getTag().getInt("variant"));
        assertEquals(7, item.getStored());
    }

    @Test void exactBucketVolumeFluidExtractionSucceeds() throws Exception {
        setup(); var lease = lease(); var core = manager.entry(root.id()).core;
        UnifiedBoundDisk<FluidStack> fluid = new UnifiedBoundDisk<>(lease, core, FrozenKey.Kind.FLUID);
        FluidStack water = new FluidStack(Fluids.WATER, 1000);

        assertTrue(fluid.insert(water, 1000, Action.PERFORM).isEmpty());
        FluidStack selected = fluid.getStacks().iterator().next();
        FluidStack extracted = fluid.extract(selected, 1000, IComparer.COMPARE_NBT, Action.PERFORM);

        assertEquals(Fluids.WATER, extracted.getFluid());
        assertEquals(1000, extracted.getAmount());
        assertEquals(0, fluid.getStored());
    }

    @Test void duplicateUuidAndStaleWrappersCannotOperate() throws Exception {
        setup(); var first = lease();
        DiskDriveNetworkNode other = mock(DiskDriveNetworkNode.class);
        var second = manager.mounts().acquire(root, other, 0, new IStorageDisk<?>[8], new IStorageDisk<?>[8], current.get(), () -> {});
        assertNull(second);
        var item = new UnifiedBoundDisk<ItemStack>(first, manager.entry(root.id()).core, FrozenKey.Kind.ITEM);
        item.insert(new ItemStack(Items.DIAMOND), 5, Action.PERFORM);
        current.set(ItemStack.EMPTY);
        assertTrue(item.extract(new ItemStack(Items.DIAMOND), 5, 0, Action.PERFORM).isEmpty());
        assertEquals(5, manager.entry(root.id()).core.items.total());
        manager.mounts().releaseSlot(node, 0); assertFalse(first.valid());
    }

    @Test void disabledStartupPreservesFilesAndReopeningRestoresSameContents() throws Exception {
        setup(); var entry = manager.entry(root.id());
        FrozenKey key = FrozenKey.item(new ItemStack(Items.DIAMOND)); entry.core.insert(key, 17, true);
        UUID diskId = root.id(), worldId = root.worldId();
        manager.flush(); UnifiedDiskManager.stop(server);
        CommentedConfig config = CommentedConfig.inMemory(); RSStorageConfig.SPEC.correct(config);
        config.set("unifiedDisk.enabled", false); RSStorageConfig.SPEC.setConfig(config);
        manager = UnifiedDiskManager.get(level); assertFalse(manager.enabled());
        assertThrows(Exception.class, () -> manager.create(null));
        assertEquals(17, manager.entry(diskId).core.items.amount(key));
        UnifiedDiskManager.stop(server); RSStorageConfig.SPEC.setConfig(null);
        manager = UnifiedDiskManager.get(level); assertTrue(manager.enabled());
        assertEquals(worldId, manager.worldId()); assertEquals(17, manager.entry(diskId).core.items.amount(key));
    }

    @Test void recoveryAfterRestartFindsOriginalItemAndFluidCheckpointWithoutCreatingNewDisk() throws Exception {
        setup(); var original = manager.entry(root.id()).core;
        FrozenKey key = UnifiedDiskCoreTest.variant(71);
        original.insert(key, 123456, true);
        original.insertFluid(new FluidStack(Fluids.WATER, 1), 987654, true);
        UUID diskId = root.id(), worldId = root.worldId();
        manager.flush(); UnifiedDiskManager.stop(server);
        manager = UnifiedDiskManager.get(level);
        assertEquals(List.of(diskId), manager.savedDiskIds());
        ItemStack replacement = spy(new ItemStack(Items.STONE));
        UnifiedDiskItem item = mock(UnifiedDiskItem.class, CALLS_REAL_METHODS);
        doReturn(item).when(replacement).getItem();
        manager.restoreIdentity(diskId, replacement);
        assertEquals(diskId, item.getId(replacement)); assertEquals(worldId, item.worldId(replacement));
        assertEquals(123456, manager.entry(diskId).core.items.amount(key));
        assertEquals(987654, manager.entry(diskId).core.fluids.total());
        assertEquals(List.of(diskId), manager.savedDiskIds());
        ItemStack missing = spy(new ItemStack(Items.STONE));
        doReturn(item).when(missing).getItem();
        assertThrows(Exception.class, () -> manager.restoreIdentity(UUID.randomUUID(), missing));
        assertFalse(missing.hasTag());
        assertEquals(List.of(diskId), manager.savedDiskIds());
    }

    @Test void rootProxyIsSmallReadOnlyAndUnknownFormatPreservesOriginal() throws Exception {
        setup();
        assertTrue(root.getStacks().isEmpty()); assertEquals(10, root.insert(new ItemStack(Items.STONE), 10, Action.PERFORM).getCount());
        assertTrue(root.extract(new ItemStack(Items.STONE), 10, 0, Action.PERFORM).isEmpty());
        CompoundTag future = root.writeToNbt(); future.putInt("Format", 200); future.putString("extra", "preserve");
        UnifiedDiskRoot unsupported = new UnifiedDiskRoot(manager, future);
        assertFalse(unsupported.compatible()); assertEquals(future, unsupported.writeToNbt());
        future.putString("extra", "changed"); assertEquals("preserve", unsupported.writeToNbt().getString("extra"));
    }

    @Test void tooltipSummaryUsesLiveCoreAndCachedSummaryAfterEviction() throws Exception {
        setup(); var entry = manager.entry(root.id());
        entry.core.insertItem(new ItemStack(Items.DIAMOND), 17, true);
        assertEquals(17, manager.summary(root.id()).items());
        manager.flush(); when(server.getTickCount()).thenReturn(1200); manager.tick();
        assertNull(entry.core);
        assertEquals(17, manager.summary(root.id()).items()); assertNull(entry.core);
        manager.entry(root.id()).core.insertItem(new ItemStack(Items.DIAMOND), 3, true);
        assertEquals(20, manager.summary(root.id()).items());
        UUID player = UUID.randomUUID();
        assertTrue(manager.allowTooltipRequest(player)); assertFalse(manager.allowTooltipRequest(player));
        when(server.getTickCount()).thenReturn(1210); assertTrue(manager.allowTooltipRequest(player));
        manager.forgetTooltipPlayer(player); assertTrue(manager.allowTooltipRequest(player));
    }
}
