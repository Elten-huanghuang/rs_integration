package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.mixin.malum.AltarCraftingHelperMixin;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemStackHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MalumAltarPedestalsTest extends BootstrapTest {
    private final Map<BlockPos, BlockEntity> blocks = new HashMap<>();
    private final Map<Object, ItemStackHandler> inventories = new HashMap<>();
    private final List<MalumAltarPedestals.Lease> leases = new ArrayList<>();
    private final ServerLevel level = world(blocks);

    @AfterEach
    void releaseAllLeases() {
        leases.forEach(MalumAltarPedestals.Lease::close);
    }

    @Test
    void fourTouchingAltarsKeepTheirOwnInputsThroughoutSixteenBufferedCycles() {
        List<BlockEntity> altars = List.of(block(0, 0), block(0, 1), block(1, 0), block(1, 1));
        List<BlockEntity> ring = new ArrayList<>();
        for (int x = -1; x <= 2; x++) {
            for (int z = -1; z <= 2; z++) {
                if (x == -1 || x == 2 || z == -1 || z == 2) ring.add(block(x, z));
            }
        }
        Set<Object> claimed = new HashSet<>();
        List<List<?>> inputs = new ArrayList<>();
        for (BlockEntity altar : altars) {
            MalumAltarPedestals.Lease lease = reserve(altar, ring, 3);
            assertNotNull(lease);
            List<?> owned = lease.captured();
            inputs.add(owned);
            for (Object pedestal : owned) {
                assertTrue(claimed.add(pedestal), "同一个物品台不能分给两台祭坛");
                inventories.get(pedestal).setStackInSlot(0, new ItemStack(Items.REDSTONE, 64));
            }
        }
        assertEquals(12, claimed.size());
        int consumed = 0;
        for (int cycle = 0; cycle < 16; cycle++) {
            for (int index = 0; index < altars.size(); index++) {
                BlockPos pos = altars.get(index).getBlockPos();
                List<?> captured = MalumAltarPedestals.captureReserved(level, pos);
                assertEquals(inputs.get(index), captured);
                assertEquals(captured, MalumAltarPedestals.filterCaptured(level, pos, ring));
                for (Object pedestal : captured) {
                    consumed += inventories.get(pedestal).extractItem(0, 4, false).getCount();
                }
            }
        }
        assertEquals(12 * 64, consumed);
        BlockEntity neighbor = block(3, 0);
        assertTrue(MalumAltarPedestals.filterCaptured(level, neighbor.getBlockPos(), ring).isEmpty());
        assertNull(reserve(neighbor, ring, 1), "底座变空也要保留到批次完成");
        for (MalumAltarPedestals.Lease lease : List.copyOf(leases)) lease.close();
        assertNotNull(reserve(neighbor, ring, 3));
    }

    @Test
    void insufficientSpaceNeverLeavesAPartialReservation() {
        BlockEntity first = block(0, 0);
        BlockEntity second = block(1, 0);
        List<BlockEntity> ring = List.of(block(-1, 0), block(-1, 1));

        assertNull(reserve(first, ring, 3));
        assertNull(MalumAltarPedestals.captureReserved(level, first.getBlockPos()));
        assertNotNull(reserve(second, ring, 2));
    }

    @Test
    void occupiedAndDuplicatePedestalsCannotInflateAvailableCapacity() {
        BlockEntity altar = block(0, 0);
        BlockEntity occupied = block(-1, 0);
        BlockEntity empty = block(-1, 1);
        inventories.get(occupied).setStackInSlot(0, new ItemStack(Items.DIAMOND));

        assertNull(reserve(altar, List.of(occupied, empty, empty), 2));
        MalumAltarPedestals.Lease lease = reserve(altar, List.of(occupied, empty), 1);
        assertNotNull(lease);
        assertEquals(List.of(empty), lease.captured());
        assertEquals(Items.DIAMOND, inventories.get(occupied).getStackInSlot(0).getItem());
    }

    @Test
    void brokenOrReplacedPedestalIsNotAdoptedByTheRunningTask() {
        BlockEntity altar = block(0, 0);
        BlockEntity original = block(-1, 0);
        assertNotNull(reserve(altar, List.of(original), 1));
        BlockEntity replacement = block(-1, 0);

        assertTrue(MalumAltarPedestals.captureReserved(level, altar.getBlockPos()).isEmpty());
        assertFalse(MalumAltarPedestals.available(level, replacement));
    }

    @Test
    void nativeCaptureHookReturnsOnlyReservedInputsWithoutScanningTheRingAgain() throws Exception {
        BlockEntity altar = block(0, 0);
        BlockEntity pedestal = block(-1, 0);
        assertNotNull(reserve(altar, List.of(pedestal), 1));
        Method hook = AltarCraftingHelperMixin.class.getDeclaredMethod("rsi$captureReserved",
                Level.class, BlockPos.class, CallbackInfoReturnable.class);
        hook.setAccessible(true);
        clearInvocations(level);

        for (int attempt = 0; attempt < 100; attempt++) {
            CallbackInfoReturnable<List<?>> callback = new CallbackInfoReturnable<>("capturePedestals", true);
            hook.invoke(null, level, altar.getBlockPos(), callback);
            assertTrue(callback.isCancelled(), "有预留清单时应跳过原生范围扫描");
            assertEquals(List.of(pedestal), callback.getReturnValue());
        }

        // captureReserved 校验祭坛一次，读取清单时再校验一次；除此以外只读本任务的底座。
        verify(level, times(200)).isLoaded(altar.getBlockPos());
        verify(level, times(200)).getBlockEntity(altar.getBlockPos());
        verify(level, times(100)).isLoaded(pedestal.getBlockPos());
        verify(level, times(100)).getBlockEntity(pedestal.getBlockPos());
        verifyNoMoreInteractions(level);
    }

    @Test
    void nativeCaptureHookLeavesUnmanagedAltarsOnTheOriginalPath() throws Exception {
        Method hook = AltarCraftingHelperMixin.class.getDeclaredMethod("rsi$captureReserved",
                Level.class, BlockPos.class, CallbackInfoReturnable.class);
        hook.setAccessible(true);
        CallbackInfoReturnable<List<?>> callback = new CallbackInfoReturnable<>("capturePedestals", true);

        hook.invoke(null, level, new BlockPos(0, 64, 0), callback);

        assertFalse(callback.isCancelled());
    }

    @Test
    void staleOwnerAndRepeatedCleanupCannotReleaseTheSuccessorsInputs() {
        BlockEntity altar = block(0, 0);
        BlockEntity pedestal = block(-1, 0);
        MalumAltarPedestals.Lease old = reserve(altar, List.of(pedestal), 1);
        assertNotNull(old);
        BlockEntity replacement = block(0, 0);
        MalumAltarPedestals.Lease successor = reserve(replacement, List.of(pedestal), 1);
        assertNotNull(successor);

        old.close();
        old.close();

        assertEquals(List.of(pedestal), MalumAltarPedestals.captureReserved(level, replacement.getBlockPos()));
        assertFalse(MalumAltarPedestals.available(level, pedestal));
    }

    @Test
    void loadedChecksNeverReadAnUnloadedPedestal() {
        BlockEntity altar = block(0, 0);
        BlockEntity pedestal = block(-1, 0);
        assertNotNull(reserve(altar, List.of(pedestal), 1));
        when(level.isLoaded(pedestal.getBlockPos())).thenReturn(false);
        clearInvocations(level);

        assertTrue(MalumAltarPedestals.captureReserved(level, altar.getBlockPos()).isEmpty());
        verify(level, never()).getBlockEntity(pedestal.getBlockPos());
    }

    @Test
    void worldsWithIdenticalPositionsDoNotShareReservations() {
        BlockEntity altar = block(0, 0);
        BlockEntity pedestal = block(-1, 0);
        assertNotNull(reserve(altar, List.of(pedestal), 1));
        ServerLevel other = world(blocks);
        MalumAltarPedestals.Lease independent = MalumAltarPedestals.tryReserve(
                other, altar, List.of(pedestal), 1, ignored -> true);
        assertNotNull(independent);
        leases.add(independent);

        assertEquals(List.of(pedestal), MalumAltarPedestals.captureReserved(other, altar.getBlockPos()));
    }

    @Test
    void recipeWithoutExtraInputsStillUsesAnEmptyReservedCapture() {
        BlockEntity altar = block(0, 0);
        assertNotNull(reserve(altar, List.of(), 0));

        assertEquals(List.of(), MalumAltarPedestals.captureReserved(level, altar.getBlockPos()));
        assertNull(reserve(altar, List.of(), 0));
    }

    @Test
    void delegateCompletionAndPreparationCancellationReleaseOwnership() throws Exception {
        BlockEntity altar = block(0, 0);
        BlockEntity pedestal = block(-1, 0);
        MalumBatchDelegate delegate = new MalumBatchDelegate();
        set(delegate, "pedestalLease", reserve(altar, List.of(pedestal), 1));
        delegate.releasePreparationResources();
        assertNull(MalumAltarPedestals.captureReserved(level, altar.getBlockPos()));

        set(delegate, "pedestalLease", reserve(altar, List.of(pedestal), 1));
        Method reset = MalumBatchDelegate.class.getDeclaredMethod("resetState");
        reset.setAccessible(true);
        reset.invoke(delegate);
        assertNull(MalumAltarPedestals.captureReserved(level, altar.getBlockPos()));
        delegate.releasePreparationResources();
    }

    @Test
    void failedPreparedStartReleasesItsReservation() throws Exception {
        BlockEntity altar = block(0, 0);
        BlockEntity pedestal = block(-1, 0);
        MalumBatchDelegate delegate = new MalumBatchDelegate();
        set(delegate, "pedestalLease", reserve(altar, List.of(pedestal), 1));
        set(delegate, "altar", new BusyAltar());

        assertFalse(delegate.startPreparedOperation(null, List.of(), null));

        assertNull(MalumAltarPedestals.captureReserved(level, altar.getBlockPos()));
        assertTrue(MalumAltarPedestals.available(level, pedestal));
    }

    public static final class BusyAltar {
        public boolean isCrafting = true;
    }

    private MalumAltarPedestals.Lease reserve(BlockEntity altar, List<?> ring, int count) {
        MalumAltarPedestals.Lease lease = MalumAltarPedestals.tryReserve(
                level, altar, ring, count, pedestal -> inventories.get(pedestal).getStackInSlot(0).isEmpty());
        if (lease != null) leases.add(lease);
        return lease;
    }

    private BlockEntity block(int x, int z) {
        BlockEntity block = mock(BlockEntity.class);
        BlockPos pos = new BlockPos(x, 64, z);
        when(block.getBlockPos()).thenReturn(pos);
        blocks.put(pos, block);
        inventories.put(block, new ItemStackHandler(1));
        return block;
    }

    private static ServerLevel world(Map<BlockPos, BlockEntity> blocks) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.isLoaded(ArgumentMatchers.any(BlockPos.class))).thenReturn(true);
        when(level.getBlockEntity(ArgumentMatchers.any(BlockPos.class)))
                .thenAnswer(invocation -> blocks.get(invocation.getArgument(0)));
        return level;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = MalumBatchDelegate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
