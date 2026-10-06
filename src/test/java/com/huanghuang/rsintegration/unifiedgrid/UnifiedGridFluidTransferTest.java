package com.huanghuang.rsintegration.unifiedgrid;

import com.huanghuang.rsintegration.disk.core.FrozenKey;
import com.huanghuang.rsintegration.disk.rs.IndexedStackList;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 在容器能力接口边界使用可控实现，验证网络和鼠标库存守恒。 */
class UnifiedGridFluidTransferTest extends BootstrapTest {
    @Test void waterBucketEmptiesIntoNetworkAndLeavesOneEmptyBucket() {
        Store f = new Store(0, 2000, 0);
        ItemStack bucket = new ItemStack(Items.WATER_BUCKET);
        var result = UnifiedGridFluidTransfer.empty(f.network, bucket, Tank::new);
        assertTrue(result.cursor().is(Items.BUCKET));
        assertEquals(1000, f.amount);
        assertEquals(1000, result.transferred());
        assertTrue(bucket.is(Items.WATER_BUCKET));
    }

    @Test void wholeBucketDoesNotDrainWhenNetworkCannotAcceptFullBucket() {
        Store f = new Store(0, 999, 0);
        var result = UnifiedGridFluidTransfer.empty(f.network, new ItemStack(Items.WATER_BUCKET), Tank::new);
        assertTrue(result.cursor().is(Items.WATER_BUCKET));
        assertEquals(0, f.amount);
        assertEquals(0, result.transferred());
    }

    @Test void heldEmptyBucketFillsWithoutExtractingAnotherBucketFromNetwork() {
        Store f = new Store(3000, 4000, 0);
        var result = UnifiedGridFluidTransfer.fill(f.network, new ItemStack(Items.BUCKET), water(3000), Tank::new);
        assertTrue(result.cursor().is(Items.WATER_BUCKET));
        assertEquals(2000, f.amount);
        verify(f.network, never()).extractItem(any(), anyInt(), any(Action.class));
    }

    @Test void emptyCursorNeedsNetworkBucketAndConsumesOnlyOneBucket() {
        Store f = new Store(2000, 4000, 0);
        var failed = UnifiedGridFluidTransfer.fill(f.network, ItemStack.EMPTY, water(2000), Tank::new);
        assertTrue(failed.cursor().isEmpty());
        assertEquals(2000, f.amount);
        f.buckets = 1;
        var success = UnifiedGridFluidTransfer.fill(f.network, ItemStack.EMPTY, water(2000), Tank::new);
        assertTrue(success.cursor().is(Items.WATER_BUCKET));
        assertEquals(0, f.buckets);
        assertEquals(1000, f.amount);
    }

    @ParameterizedTest
    @CsvSource({"999,0", "999,1", "1000,0", "1000,1", "1000,3", "1001,1", "2000,0", "2000,1"})
    void fillingFromLiveCachePreservesFluidIdentityWhenLastBucketClearsEntry(int amount, int cursorBuckets) {
        Store f = new Store(amount, 4000, cursorBuckets == 0 ? 1 : 0);
        IndexedStackList<FluidStack> cache = new IndexedStackList<>(FrozenKey.Kind.FLUID);
        FluidStack resource = water(amount);
        resource.getOrCreateTag().putString("variant", "last_bucket");
        cache.add(resource);
        FluidStack selected = cache.getStacks().iterator().next().getStack();
        when(f.network.extractFluid(any(), anyInt(), any(Action.class))).thenAnswer(invocation -> {
            int requested = invocation.getArgument(1);
            FluidStack extracted = new FluidStack(resource, Math.min(f.amount, requested));
            if (invocation.getArgument(2) == Action.PERFORM && !extracted.isEmpty()) {
                f.amount -= extracted.getAmount();
                // 和真实 RS 缓存一样，实际提取会同步修改之前交给终端的对象。
                cache.remove(extracted, extracted.getAmount());
            }
            return extracted;
        });
        ItemStack cursor = cursorBuckets == 0 ? ItemStack.EMPTY : new ItemStack(Items.BUCKET, cursorBuckets);
        var result = UnifiedGridFluidTransfer.fill(f.network, cursor, selected, Tank::new);
        if (amount < 1000) {
            assertEquals(0, result.transferred());
            assertSame(cursor, result.cursor());
            assertEquals(amount, f.amount);
            assertEquals(cursorBuckets == 0 ? 1 : 0, f.buckets);
            verify(f.network, never()).extractFluid(any(), anyInt(), eq(Action.PERFORM));
            verify(f.network, never()).extractItem(any(), anyInt(), eq(Action.PERFORM));
            return;
        }
        ItemStack filled = cursorBuckets > 1 ? result.overflow() : result.cursor();
        assertTrue(filled.is(Items.WATER_BUCKET));
        assertEquals(1, filled.getCount());
        assertEquals(1000, result.transferred());
        assertEquals(amount - 1000, f.amount);
        assertEquals(amount - 1000, selected.getAmount());
        assertEquals(amount > 1000 ? 1 : 0, cache.size());
        assertEquals(0, f.buckets);
        selected.setAmount(0);
        assertTrue(result.resource().isFluidEqual(resource));
        assertEquals(resource.getTag(), result.resource().getTag());
        assertTrue(result.recovery().isEmpty());
        if (cursorBuckets > 1) {
            assertTrue(result.cursor().is(Items.BUCKET));
            assertEquals(cursorBuckets - 1, result.cursor().getCount());
        } else {
            assertTrue(result.overflow().isEmpty());
        }
        verify(f.network, never()).insertFluid(any(), anyInt(), any(Action.class));
        verify(f.network, never()).insertItem(any(), anyInt(), any(Action.class));
    }

    @Test void partialExtractionAfterSimulationRefundsFluidAndBorrowedBucket() {
        Store f = new Store(1000, 4000, 1);
        f.performExtractLimit = 500;
        var result = UnifiedGridFluidTransfer.fill(f.network, ItemStack.EMPTY, water(1000), Tank::new);
        assertTrue(result.cursor().isEmpty());
        assertTrue(result.recovery().isEmpty());
        assertEquals(1000, f.amount);
        assertEquals(1, f.buckets);
    }

    @Test void failedRefundKeepsBorrowedBucketAndUnreturnedFluidRecoverable() {
        Store f = new Store(1000, 4000, 1);
        f.performExtractLimit = 500;
        f.rejectInserts = true;
        f.rejectBucketReturn = true;
        var result = UnifiedGridFluidTransfer.fill(f.network, ItemStack.EMPTY, water(1000), Tank::new);
        assertTrue(result.cursor().is(Items.BUCKET));
        assertEquals(500, result.recovery().getAmount());
        assertEquals(1000, f.amount + result.recovery().getAmount());
    }

    @Test void generalContainerTransfersItsCapacityAndStackedBucketsConvertOnlyOne() {
        Store f = new Store(3000, 5000, 0);
        ItemStack tank = new ItemStack(Items.DIAMOND);
        var filled = UnifiedGridFluidTransfer.fill(f.network, tank, water(3000), Tank::new);
        assertEquals(3000, filled.cursor().getOrCreateTag().getInt("mB"));
        assertEquals(0, f.amount);
        f.capacity = 600;
        var emptied = UnifiedGridFluidTransfer.empty(f.network, filled.cursor(), Tank::new);
        assertEquals(600, f.amount);
        assertEquals(2400, emptied.cursor().getOrCreateTag().getInt("mB"));
        f.capacity = 5000;
        var buckets = UnifiedGridFluidTransfer.fill(f.network, new ItemStack(Items.BUCKET, 3), water(600), Tank::new);
        assertEquals(3, buckets.cursor().getCount(), "不足一桶保持堆叠");
        f.amount = 1000;
        buckets = UnifiedGridFluidTransfer.fill(f.network, new ItemStack(Items.BUCKET, 3), water(1000), Tank::new);
        assertTrue(buckets.cursor().is(Items.BUCKET));
        assertEquals(2, buckets.cursor().getCount());
        assertTrue(buckets.overflow().is(Items.WATER_BUCKET));
    }

    @Test void changingInsertCapacityRetainsUnacceptedFluidInsteadOfLosingIt() {
        Store f = new Store(0, 2000, 0);
        f.performInsertLimit = 500;
        var result = UnifiedGridFluidTransfer.empty(f.network, new ItemStack(Items.WATER_BUCKET), Tank::new);
        assertTrue(result.cursor().is(Items.BUCKET));
        assertEquals(500, f.amount);
        assertEquals(500, result.recovery().getAmount());
        assertEquals(1000, f.amount + result.recovery().getAmount());
    }

    private static FluidStack water(int amount) { return new FluidStack(Fluids.WATER, amount); }

    private static final class Store {
        private final INetwork network = mock(INetwork.class);
        private int amount, capacity, buckets;
        private int performExtractLimit = Integer.MAX_VALUE, performInsertLimit = Integer.MAX_VALUE;
        private boolean rejectInserts, rejectBucketReturn;
        private Store(int amount, int capacity, int buckets) {
            this.amount = amount; this.capacity = capacity; this.buckets = buckets;
            when(network.extractFluid(any(), anyInt(), any(Action.class))).thenAnswer(invocation -> {
                int requested = invocation.getArgument(1);
                Action action = invocation.getArgument(2);
                int extracted = Math.min(this.amount, requested);
                if (action == Action.PERFORM) {
                    extracted = Math.min(extracted, performExtractLimit);
                    this.amount -= extracted;
                }
                return water(extracted);
            });
            when(network.insertFluid(any(), anyInt(), any(Action.class))).thenAnswer(invocation -> {
                int requested = invocation.getArgument(1);
                Action action = invocation.getArgument(2);
                int accepted = rejectInserts ? 0 : Math.min(requested, this.capacity - this.amount);
                if (action == Action.PERFORM) {
                    accepted = Math.min(accepted, performInsertLimit);
                    this.amount += accepted;
                }
                return water(requested - accepted);
            });
            when(network.extractItem(any(), anyInt(), any(Action.class))).thenAnswer(invocation -> {
                if (this.buckets == 0) return ItemStack.EMPTY;
                if (invocation.getArgument(2) == Action.PERFORM) this.buckets--;
                return new ItemStack(Items.BUCKET);
            });
            when(network.insertItem(any(), anyInt(), eq(Action.PERFORM))).thenAnswer(invocation -> {
                ItemStack bucket = invocation.getArgument(0);
                if (rejectBucketReturn) return bucket.copy();
                this.buckets += bucket.getCount();
                return ItemStack.EMPTY;
            });
        }
    }

    private static final class Tank implements IFluidHandlerItem {
        private final ItemStack source;
        private final boolean bucket;
        private final int capacity;
        private int amount;
        private Tank(ItemStack source) {
            this.source = source;
            bucket = source.is(Items.BUCKET) || source.is(Items.WATER_BUCKET);
            capacity = bucket ? 1000 : 4000;
            amount = source.is(Items.WATER_BUCKET) ? 1000 : source.getOrCreateTag().getInt("mB");
        }
        @Override public int getTanks() { return 1; }
        @Override public FluidStack getFluidInTank(int tank) { return water(amount); }
        @Override public int getTankCapacity(int tank) { return capacity; }
        @Override public boolean isFluidValid(int tank, FluidStack stack) { return stack.getFluid() == Fluids.WATER; }
        @Override public int fill(FluidStack resource, FluidAction action) {
            if (!isFluidValid(0, resource)) return 0;
            int filled = Math.min(resource.getAmount(), capacity - amount);
            if (bucket && filled != 1000) return 0;
            if (action.execute()) amount += filled;
            return filled;
        }
        @Override public FluidStack drain(int requested, FluidAction action) {
            int drained = Math.min(requested, amount);
            if (bucket && drained != 1000) return FluidStack.EMPTY;
            if (action.execute()) amount -= drained;
            return water(drained);
        }
        @Override public FluidStack drain(FluidStack resource, FluidAction action) {
            return isFluidValid(0, resource) ? drain(resource.getAmount(), action) : FluidStack.EMPTY;
        }
        @Override public ItemStack getContainer() {
            if (bucket) return new ItemStack(amount == 0 ? Items.BUCKET : Items.WATER_BUCKET);
            ItemStack result = source.copy();
            result.getOrCreateTag().putInt("mB", amount);
            return result;
        }
    }
}
