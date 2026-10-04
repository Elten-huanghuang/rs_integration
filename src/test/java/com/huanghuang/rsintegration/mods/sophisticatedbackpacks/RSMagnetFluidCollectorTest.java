package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import com.huanghuang.rsintegration.ModItems;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidTestFixtures;
import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.Direction;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.eventbus.api.EventListenerHelper;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.ListenerList;
import net.minecraftforge.eventbus.LockHelper;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.registries.RegistryObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterType;
import net.p3pp3rf1y.sophisticatedcore.settings.memory.MemorySettingsCategory;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RSMagnetFluidCollectorTest extends BootstrapTest {
    private Field tokenValue;
    private Object originalToken;
    private StorageSession session;
    private ServerPlayer player;
    private ItemStack upgrade;
    private Runnable save;

    @BeforeEach
    void prepare() throws Exception {
        tokenValue = RegistryObject.class.getDeclaredField("value");
        tokenValue.setAccessible(true);
        originalToken = tokenValue.get(ModItems.ALCHEMIST_INK_FLUID);
        tokenValue.set(ModItems.ALCHEMIST_INK_FLUID, InkFluidTestFixtures.tokenItem());
        session = mock(StorageSession.class);
        player = mock(ServerPlayer.class);
        upgrade = new ItemStack(Items.COMPASS);
        save = mock(Runnable.class);
    }

    @AfterEach
    void restoreToken() throws Exception {
        tokenValue.set(ModItems.ALCHEMIST_INK_FLUID, originalToken);
    }

    private void acceptAll() {
        when(session.insert(eq(player), any(), anyBoolean())).thenAnswer(call ->
                StorageOperationResult.inserted(call.getArgument(2) ? StorageOperationMode.SIMULATE
                        : StorageOperationMode.PERFORM, call.getArgument(1), ItemStack.EMPTY));
    }

    @Test
    void waterAndLavaSourcesUseNativeBucketPickupAndStoreExactlyOneBucket() {
        acceptAll();
        for (var block : new LiquidBlock[]{(LiquidBlock) Blocks.WATER, (LiquidBlock) Blocks.LAVA}) {
            Level level = mock(Level.class);
            var state = block.defaultBlockState();
            state.initCache();
            BlockPos pos = new BlockPos(1, 64, 2);
            when(level.getBlockState(pos)).thenReturn(state);
            when(level.getFluidState(pos)).thenReturn(state.getFluidState());
            when(level.setBlock(eq(pos), any(), anyInt())).thenReturn(true);
            IFluidHandler source = RSMagnetFluidCollector.sourceHandler(level, pos, state);
            assertNotNull(source, state + " fluid=" + state.getFluidState());
            assertTrue(RSMagnetFluidCollector.transfer(source, session, player, upgrade, save));
            verify(level).setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
            assertTrue(RSMagnetFluidCollector.pending(upgrade).isEmpty());
        }
        ArgumentCaptor<ItemStack> inserted = ArgumentCaptor.forClass(ItemStack.class);
        verify(session, times(2)).insert(eq(player), inserted.capture(), eq(false));
        assertEquals(1000, InkFluidSupport.fluid(inserted.getAllValues().get(0)).getAmount());
        assertEquals(Fluids.WATER, InkFluidSupport.fluid(inserted.getAllValues().get(0)).getFluid());
        assertEquals(Fluids.LAVA, InkFluidSupport.fluid(inserted.getAllValues().get(1)).getFluid());
    }

    @Test
    void flowingWaterWaterloggedBlocksAndMachinesAreSkipped() {
        Level level = mock(Level.class);
        assertNull(RSMagnetFluidCollector.sourceHandler(level, BlockPos.ZERO,
                Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3)));
        assertNull(RSMagnetFluidCollector.sourceHandler(level, BlockPos.ZERO,
                Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)));
        assertNull(RSMagnetFluidCollector.sourceHandler(level, BlockPos.ZERO, Blocks.CHEST.defaultBlockState()));
        verifyNoInteractions(level);
    }

    @Test
    void insufficientCapacityDoesNotRemoveTheSource() {
        FluidTank source = new FluidTank(1000);
        source.setFluid(new FluidStack(Fluids.WATER, 1000));
        when(session.insert(eq(player), any(), eq(true))).thenAnswer(call ->
                StorageOperationResult.inserted(StorageOperationMode.SIMULATE,
                        call.getArgument(1), ((ItemStack) call.getArgument(1)).copyWithCount(1)));
        assertFalse(RSMagnetFluidCollector.transfer(source, session, player, upgrade, save));
        assertEquals(1000, source.getFluidAmount());
        verify(session, never()).insert(any(), any(), eq(false));
        verifyNoInteractions(save);
    }

    @Test
    void noRsFluidCapacityDoesNotDrainOrRouteToItemStorage() {
        INetwork network = mock(INetwork.class);
        when(network.canRun()).thenReturn(true);
        when(network.insertFluid(any(), eq(1000), eq(Action.SIMULATE)))
                .thenAnswer(call -> ((FluidStack) call.getArgument(0)).copy());
        when(session.insert(eq(player), any(), eq(true))).thenAnswer(call -> {
            ItemStack token = call.getArgument(1);
            return StorageOperationResult.inserted(StorageOperationMode.SIMULATE, token,
                    InkFluidSupport.insert(network, token, true));
        });
        FluidTank source = new FluidTank(1000);
        source.setFluid(new FluidStack(Fluids.WATER, 1000));
        assertFalse(RSMagnetFluidCollector.transfer(source, session, player, upgrade, save));
        assertEquals(1000, source.getFluidAmount());
        verify(network, never()).insertFluid(any(), anyInt(), eq(Action.PERFORM));
        verify(network, never()).insertItem(any(), anyInt(), any());
    }

    @Test
    void partialCommitPersistsRemainderAcrossReloadAndRetriesOnlyThatAmount() {
        FluidTank source = new FluidTank(1000);
        FluidStack water = new FluidStack(Fluids.WATER, 1000);
        water.getOrCreateTag().putString("variant", "special");
        source.setFluid(water);
        acceptAll();
        when(session.insert(eq(player), any(), eq(false))).thenAnswer(call ->
                StorageOperationResult.inserted(StorageOperationMode.PERFORM,
                        call.getArgument(1), ((ItemStack) call.getArgument(1)).copyWithCount(400)));
        assertTrue(RSMagnetFluidCollector.transfer(source, session, player, upgrade, save));
        assertEquals(0, source.getFluidAmount());
        ItemStack reloaded = ItemStack.of(upgrade.save(new CompoundTag()));
        assertEquals(400, RSMagnetFluidCollector.pending(reloaded).getAmount());
        assertEquals("special", RSMagnetFluidCollector.pending(reloaded).getTag().getString("variant"));
        clearInvocations(session);
        acceptAll();
        assertTrue(RSMagnetFluidCollector.flushPending(session, player, reloaded, save));
        ArgumentCaptor<ItemStack> remaining = ArgumentCaptor.forClass(ItemStack.class);
        verify(session).insert(eq(player), remaining.capture(), eq(false));
        assertEquals(400, remaining.getValue().getCount());
        assertTrue(RSMagnetFluidCollector.pending(reloaded).isEmpty());
    }

    @Test
    void indeterminateCommitStopsRetriesWithoutDuplicatingFluid() {
        FluidTank source = new FluidTank(1000);
        source.setFluid(new FluidStack(Fluids.WATER, 1000));
        acceptAll();
        when(session.insert(eq(player), any(), eq(false))).thenAnswer(call ->
                StorageOperationResult.indeterminateInsert(call.getArgument(1), StorageDiagnosticCode.BACKEND_EXCEPTION));
        assertTrue(RSMagnetFluidCollector.transfer(source, session, player, upgrade, save));
        assertEquals(1000, RSMagnetFluidCollector.pending(upgrade).getAmount());
        assertFalse(RSMagnetFluidCollector.flushPending(session, player, upgrade.copy(), save));
        verify(session, times(1)).insert(eq(player), any(), eq(false));
    }

    @Test
    void deniedPermissionSkipsWorldScanning() {
        Level level = mock(Level.class);
        new RSMagnetFluidCollector().collect(level, BlockPos.ZERO, 10, player, session, upgrade, save, fluid -> true);
        verify(session).hasPermission(player, StoragePermission.INSERT);
        verifyNoInteractions(level);
    }

    @Test
    void scanIsBoundedAndContinuesWithoutLoadingChunks() {
        Level level = mock(Level.class);
        when(session.hasPermission(player, StoragePermission.INSERT)).thenReturn(true);
        var collector = new RSMagnetFluidCollector();
        collector.collect(level, BlockPos.ZERO, 10, player, session, upgrade, save, fluid -> true);
        collector.collect(level, BlockPos.ZERO, 10, player, session, upgrade, save, fluid -> true);
        ArgumentCaptor<BlockPos> positions = ArgumentCaptor.forClass(BlockPos.class);
        verify(level, times(2 * RSMagnetFluidCollector.SCAN_LIMIT)).hasChunkAt(positions.capture());
        assertEquals(2 * RSMagnetFluidCollector.SCAN_LIMIT, positions.getAllValues().stream().distinct().count());
        assertTrue(positions.getAllValues().stream().allMatch(pos -> Math.abs(pos.getX()) <= 10
                && Math.abs(pos.getY()) <= 10 && Math.abs(pos.getZ()) <= 10));
        verify(level, never()).getBlockState(any());
    }

    private ContentsFilterLogic filter() {
        return new ContentsFilterLogic(upgrade, stack -> save.run(), 16,
                () -> mock(InventoryHandler.class), mock(MemorySettingsCategory.class));
    }

    @Test
    void jeiFluidBlacklistDistinguishesWaterFromLavaAndSurvivesReload() {
        var filter = filter();
        filter.setDepositFilterType(ContentsFilterType.BLOCK);
        filter.getFilterHandler().setStackInSlot(0,
                InkFluidSupport.token(new FluidStack(Fluids.WATER, 250)).copyWithCount(1));
        upgrade = ItemStack.of(upgrade.save(new CompoundTag()));
        var reloaded = filter();
        assertFalse(RSMagnetFluidFilter.matches(reloaded, new FluidStack(Fluids.WATER, 1000)));
        assertTrue(RSMagnetFluidFilter.matches(reloaded, new FluidStack(Fluids.LAVA, 1000)));
        FluidTank source = new FluidTank(1000);
        source.setFluid(new FluidStack(Fluids.WATER, 1000));
        assertFalse(RSMagnetFluidCollector.transfer(source, session, player, upgrade, save,
                fluid -> RSMagnetFluidFilter.matches(reloaded, fluid)));
        assertEquals(1000, source.getFluidAmount());
        verifyNoInteractions(session);
    }

    @Test
    void fluidWhitelistAndOptionalNbtMatchingApplyWithoutComparingAmount() {
        var filter = filter();
        filter.setDepositFilterType(ContentsFilterType.ALLOW);
        FluidStack listed = new FluidStack(Fluids.WATER, 1);
        listed.getOrCreateTag().putString("variant", "special");
        filter.getFilterHandler().setStackInSlot(0, InkFluidSupport.token(listed));
        assertTrue(RSMagnetFluidFilter.matches(filter, new FluidStack(Fluids.WATER, 1000)));
        assertFalse(RSMagnetFluidFilter.matches(filter, new FluidStack(Fluids.LAVA, 1000)));
        filter.setMatchNbt(true);
        assertFalse(RSMagnetFluidFilter.matches(filter, new FluidStack(Fluids.WATER, 1000)));
        listed.setAmount(1000);
        assertTrue(RSMagnetFluidFilter.matches(filter, listed));
    }

    private Level sourceWorld() {
        Level level = mock(Level.class);
        var state = Blocks.WATER.defaultBlockState();
        state.initCache();
        when(level.hasChunkAt(any())).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(state);
        when(level.getFluidState(any())).thenReturn(state.getFluidState());
        when(level.mayInteract(eq(player), any())).thenReturn(true);
        when(player.mayUseItemAt(any(), eq(Direction.UP), any())).thenReturn(true);
        when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
        when(session.hasPermission(player, StoragePermission.INSERT)).thenReturn(true);
        return level;
    }

    @Test
    void collectionStopsAfterFourSourcesAndHonorsProtectionCancellation() throws Exception {
        Level level = sourceWorld();
        acceptAll();
        when(level.setBlock(any(), any(), anyInt())).thenReturn(true);
        var collector = new RSMagnetFluidCollector();
        collector.collect(level, new BlockPos(0, 64, 0), 2, player, session, upgrade, save, fluid -> true);
        verify(level, times(RSMagnetFluidCollector.SOURCE_LIMIT)).setBlock(any(), any(), anyInt());
        clearInvocations(level, session);
        withCollectionListeners(event -> event.setCanceled(true), event -> fail("采液不应发送挖掘事件"), () -> {
            collector.collect(level, new BlockPos(0, 64, 0), 2, player, session, upgrade, save, fluid -> true);
            verify(level, never()).setBlock(any(), any(), anyInt());
            verify(session, never()).insert(any(), any(), eq(false));
            assertTrue(RSMagnetFluidCollector.pending(upgrade).isEmpty());
        });
    }

    @Test
    void collectingRiverSourcesDoesNotTriggerUltimineOrDamageHeldTool() throws Exception {
        Level level = sourceWorld();
        acceptAll();
        when(level.setBlock(any(), any(), anyInt())).thenReturn(true);
        ItemStack shovel = new ItemStack(Items.IRON_SHOVEL);
        shovel.setDamageValue(7);
        when(player.getMainHandItem()).thenReturn(shovel);
        AtomicInteger bucketChecks = new AtomicInteger();
        AtomicInteger miningEvents = new AtomicInteger();
        withCollectionListeners(event -> {
            bucketChecks.incrementAndGet();
            assertSame(player, event.getEntity());
            assertSame(level, event.getLevel());
            assertTrue(event.getEmptyBucket().is(Items.BUCKET));
            BlockHitResult hit = assertInstanceOf(BlockHitResult.class, event.getTarget());
            assertTrue(level.getFluidState(hit.getBlockPos()).isSource());
        }, event -> {
            // 模拟连锁模组收到玩家挖掘事件后消耗手持工具耐久。
            miningEvents.incrementAndGet();
            shovel.setDamageValue(shovel.getDamageValue() + 1);
        }, () -> {
            var collector = new RSMagnetFluidCollector();
            for (int tick = 0; tick < 3; tick++) {
                collector.collect(level, new BlockPos(0, 64, 0), 2, player, session, upgrade, save, fluid -> true);
            }
        });
        assertEquals(3 * RSMagnetFluidCollector.SOURCE_LIMIT, bucketChecks.get());
        assertEquals(0, miningEvents.get());
        assertEquals(7, shovel.getDamageValue());
        verify(level, times(3 * RSMagnetFluidCollector.SOURCE_LIMIT)).setBlock(any(), any(), anyInt());
        verify(session, times(3 * RSMagnetFluidCollector.SOURCE_LIMIT)).insert(eq(player), any(), eq(false));
    }

    @Test
    void deniedOrExternallyHandledBucketEventDoesNotDrainOrStoreAgain() throws Exception {
        Level level = sourceWorld();
        acceptAll();
        for (Event.Result result : new Event.Result[]{Event.Result.DENY, Event.Result.ALLOW}) {
            withCollectionListeners(event -> event.setResult(result), event -> fail("采液不应发送挖掘事件"),
                    () -> new RSMagnetFluidCollector().collect(level, BlockPos.ZERO, 0,
                            player, session, upgrade, save, fluid -> true));
        }
        verify(level, never()).setBlock(any(), any(), anyInt());
        verify(session, never()).insert(any(), any(), eq(false));
        verifyNoInteractions(save);
    }

    @Test
    void filteredOrFullStorageDoesNotEmitBucketInteractionEvents() throws Exception {
        Level level = sourceWorld();
        withCollectionListeners(event -> fail("不能采集的液体不应发送装桶事件"),
                event -> fail("采液不应发送挖掘事件"), () -> {
                    new RSMagnetFluidCollector().collect(level, BlockPos.ZERO, 0,
                            player, session, upgrade, save, fluid -> false);
                    when(session.insert(eq(player), any(), eq(true))).thenAnswer(call ->
                            StorageOperationResult.inserted(StorageOperationMode.SIMULATE,
                                    call.getArgument(1), ((ItemStack) call.getArgument(1)).copy()));
                    new RSMagnetFluidCollector().collect(level, BlockPos.ZERO, 0,
                            player, session, upgrade, save, fluid -> true);
                });
        verify(level, never()).setBlock(any(), any(), anyInt());
        verify(session, never()).insert(any(), any(), eq(false));
    }

    @SuppressWarnings("unchecked")
    private void withCollectionListeners(Consumer<FillBucketEvent> bucketListener,
            Consumer<BlockEvent.BreakEvent> miningListener, Runnable action) throws Exception {
        // 普通单测未经过 Forge 的事件类变换，需要显式提供运行时监听器列表。
        Field listenerCache = EventListenerHelper.class.getDeclaredField("listeners");
        listenerCache.setAccessible(true);
        var cache = (LockHelper<Class<?>, ListenerList>) listenerCache.get(null);
        cache.computeIfAbsent(BlockEvent.BreakEvent.class, ListenerList::new);
        cache.computeIfAbsent(FillBucketEvent.class, ListenerList::new);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, FillBucketEvent.class, bucketListener);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, BlockEvent.BreakEvent.class, miningListener);
        MinecraftForge.EVENT_BUS.start();
        try {
            action.run();
        } finally {
            MinecraftForge.EVENT_BUS.unregister(bucketListener);
            MinecraftForge.EVENT_BUS.unregister(miningListener);
            MinecraftForge.EVENT_BUS.shutdown();
        }
    }

    @Test
    void pendingFluidIsRetriedBeforeScanningNewSources() {
        Level level = sourceWorld();
        upgrade.getOrCreateTag().put(RSMagnetFluidCollector.PENDING,
                new FluidStack(Fluids.WATER, 400).writeToNBT(new CompoundTag()));
        when(session.insert(eq(player), any(), eq(false))).thenAnswer(call ->
                StorageOperationResult.inserted(StorageOperationMode.PERFORM,
                        call.getArgument(1), ((ItemStack) call.getArgument(1)).copy()));
        new RSMagnetFluidCollector().collect(level, BlockPos.ZERO, 2, player, session, upgrade, save, fluid -> true);
        verify(level, never()).hasChunkAt(any());
        assertEquals(400, RSMagnetFluidCollector.pending(upgrade).getAmount());
    }
}
