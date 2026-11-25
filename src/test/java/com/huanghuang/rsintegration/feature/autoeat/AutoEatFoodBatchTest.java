package com.huanghuang.rsintegration.feature.autoeat;

import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.Context;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.Source;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.TakeResult;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageRestockSupport.Extraction;
import com.huanghuang.rsintegration.storage.StoredItem;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoEatFoodBatchTest extends BootstrapTest {
    private static final StorageBackendId BACKEND = new StorageBackendId("test");
    private final ServerPlayer player = mock(ServerPlayer.class);
    private final AutoEatStorage storage = mock(AutoEatStorage.class);
    private final ItemStack food = new ItemStack(Items.MUSHROOM_STEW);
    private final ItemStack fee = new ItemStack(Items.PAPER, 2);
    private final Context context = new Context(player, Set.of(food.getItem()));

    @BeforeEach
    void setup() {
        when(storage.accessible(player)).thenReturn(true);
        when(storage.extract(eq(player), any(), eq(1))).thenReturn(new Extraction(ItemStack.EMPTY, true));
        when(storage.extract(player, fee, 2)).thenReturn(new Extraction(fee, false));
    }

    private Source source(String id, ItemStack... templates) {
        var source = mock(Source.class);
        var key = ResourceLocation.fromNamespaceAndPath("batch_test", id);
        assertTrue(RSIAutoEatFoodSources.register(key, source));
        assertFalse(RSIAutoEatFoodSources.register(key, source));
        when(source.candidates(context)).thenReturn(List.of(templates));
        return source;
    }

    @Test
    void nativeFirstThenOneFeePerExternalServingWithStableSourceOrder() {
        Source supplier = source("priority_b", food);
        Source unavailable = source("priority_a", food, food);
        when(unavailable.takeOne(eq(context), any())).thenReturn(new TakeResult.Unavailable());
        when(supplier.takeOne(eq(context), any())).thenAnswer(call -> new TakeResult.Taken(food.copy()));
        var stored = new StoredItem(StorageItemKey.fromItemStack(BACKEND, food), 1);
        when(storage.items()).thenReturn(List.of(stored));
        when(storage.extract(eq(player), any(), eq(1))).thenReturn(
                new Extraction(food, false), new Extraction(ItemStack.EMPTY, true));
        var batch = new AutoEatFoodBatch(storage, context, fee);
        assertEquals(1, batch.candidates().size());
        var candidate = batch.candidates().iterator().next();
        var order = inOrder(storage, unavailable, supplier);
        assertSame(food, batch.take(candidate, stack -> true));
        order.verify(storage).extract(player, fee, 2);
        verify(supplier, never()).takeOne(any(), any());
        assertFalse(batch.take(candidate, stack -> true).isEmpty());
        order.verify(storage).extract(player, fee, 2);
        order.verify(unavailable).takeOne(eq(context), any());
        order.verify(supplier).takeOne(eq(context), any());
        assertFalse(batch.take(candidate, stack -> true).isEmpty());
        order.verify(storage).extract(player, fee, 2);
        order.verify(supplier).takeOne(eq(context), any());
        verify(unavailable).candidates(context);
        verify(unavailable).takeOne(eq(context), any());
        assertNull(batch.failure());
    }

    @Test
    void snapshotsKeepExactVariantsAndWantedItems() {
        ItemStack variant = food.copy();
        variant.getOrCreateTag().putInt("flavour", 2);
        source("variants", food, variant, new ItemStack(Items.APPLE));
        var batch = new AutoEatFoodBatch(storage, context, fee);
        variant.getTag().putInt("flavour", 9);
        var candidates = List.copyOf(batch.candidates());
        assertEquals(2, candidates.size());
        assertFalse(candidates.get(0).template.hasTag());
        assertEquals(2, candidates.get(1).template.getTag().getInt("flavour"));
        Set<Item> mutable = new HashSet<>(context.wantedItems());
        var copied = new Context(player, mutable);
        mutable.clear();
        assertEquals(context.wantedItems(), copied.wantedItems());
        assertThrows(UnsupportedOperationException.class, copied.wantedItems()::clear);
    }

    @Test
    void unavailableAndRejectedFoodRefundOnlyHeldStacks() {
        Source supplier = source("rejected", food);
        var delivered = food.copy();
        when(supplier.takeOne(eq(context), any())).thenReturn(
                new TakeResult.Taken(delivered), new TakeResult.Unavailable());
        var batch = new AutoEatFoodBatch(storage, context, fee);
        var candidate = batch.candidates().iterator().next();
        assertTrue(batch.take(candidate, stack -> false).isEmpty());
        verify(storage).returnStack(player, delivered);
        assertTrue(batch.take(candidate, stack -> true).isEmpty());
        assertTrue(batch.take(candidate, stack -> true).isEmpty());
        verify(supplier, times(2)).takeOne(eq(context), any());
        verify(storage, times(2)).returnStack(player, fee);
        verify(storage, times(2)).extract(player, fee, 2);
        assertNull(batch.failure());
    }

    @Test
    void wrongDeliveryStopsRequestWithoutCallingNextSupplier() {
        Source faulty = source("invalid_a", food);
        Source next = source("invalid_b", food);
        when(faulty.takeOne(eq(context), any())).thenReturn(new TakeResult.Taken(new ItemStack(Items.APPLE)));
        var batch = new AutoEatFoodBatch(storage, context, fee);
        var candidate = batch.candidates().iterator().next();
        assertTrue(batch.take(candidate, stack -> true).isEmpty());
        assertNotNull(batch.failure());
        assertTrue(batch.take(candidate, stack -> true).isEmpty());
        verify(storage).returnStack(player, fee);
        verify(faulty).takeOne(eq(context), any());
        verify(next, never()).takeOne(any(), any());
    }

    @Test
    void capabilityVariantsStaySeparateAndWrongCapabilityIsNotDelivered() {
        ItemStack first = foodWithCapability(1);
        ItemStack second = foodWithCapability(2);
        Source supplier = source("capabilities", first, second);
        when(supplier.takeOne(eq(context), any())).thenAnswer(call -> {
            ItemStack requested = call.getArgument(1);
            assertEquals(1, requested.save(new CompoundTag()).getCompound("ForgeCaps").getInt("test"));
            return new TakeResult.Taken(second);
        });

        var batch = new AutoEatFoodBatch(storage, context, fee);
        var candidates = List.copyOf(batch.candidates());
        assertEquals(2, candidates.size());
        assertTrue(batch.take(candidates.get(0), stack -> fail("错误能力的食物不得进入食用检查")).isEmpty());
        assertEquals("rsi.autoeat.error.food_source", batch.failure());
        verify(storage).returnStack(player, fee);
    }

    @Test
    void broaderNativeMatchDoesNotCountAsConfirmedStockout() {
        ItemStack nativeFood = foodWithCapability(2);
        Source supplier = source("broader_native_match", foodWithCapability(1));
        when(storage.extract(eq(player), any(), eq(1))).thenReturn(new Extraction(nativeFood, false));

        var batch = new AutoEatFoodBatch(storage, context, fee);
        assertTrue(batch.take(batch.candidates().iterator().next(), stack -> true).isEmpty());
        assertNull(batch.failure());
        verify(storage).returnStack(player, nativeFood);
        verify(storage, never()).extract(player, fee, 2);
        verify(supplier, never()).takeOne(any(), any());
    }

    private ItemStack foodWithCapability(int variant) {
        ItemStack stack = spy(food.copy());
        // 不启动 Forge 附加事件，只模拟物品存档中独立于普通 NBT 的能力字段。
        doAnswer(call -> {
            CompoundTag saved = (CompoundTag) call.callRealMethod();
            CompoundTag capabilities = new CompoundTag();
            capabilities.putInt("test", variant);
            saved.put("ForgeCaps", capabilities);
            return saved;
        }).when(stack).save(any(CompoundTag.class));
        doAnswer(call -> foodWithCapability(variant)).when(stack).copy();
        return stack;
    }

    @Test
    void indeterminateOrThrowingSupplierRefundsFeeAndStops() {
        Source supplier = source("uncertain", food);
        when(supplier.takeOne(eq(context), any())).thenReturn(new TakeResult.Indeterminate())
                .thenThrow(new IllegalStateException("uncertain charge"));
        for (int request = 0; request < 2; request++) {
            var batch = new AutoEatFoodBatch(storage, context, fee);
            var candidate = batch.candidates().iterator().next();
            assertTrue(batch.take(candidate, stack -> true).isEmpty());
            assertNotNull(batch.failure());
            assertTrue(batch.take(candidate, stack -> true).isEmpty());
        }
        verify(storage, times(2)).returnStack(player, fee);
        verify(supplier, times(2)).candidates(context);
        verify(supplier, times(2)).takeOne(eq(context), any());
    }

    @Test
    void shortFeeRefundsActualPaymentBeforeSourceCommit() {
        var partial = fee.copyWithCount(1);
        when(storage.extract(player, fee, 2)).thenReturn(new Extraction(partial, false));
        Source supplier = source("short_fee", food);
        var batch = new AutoEatFoodBatch(storage, context, fee);
        assertTrue(batch.take(batch.candidates().iterator().next(), stack -> true).isEmpty());
        verify(storage).returnStack(player, partial);
        verify(supplier, never()).takeOne(any(), any());
        assertNotNull(batch.failure());
    }

    @Test
    void uncertainNativeTransferPreventsFallbackEvenWithConfirmedFood() {
        when(storage.extract(eq(player), any(), eq(1))).thenReturn(new Extraction(food, false, true));
        Source supplier = source("native_failure", food);
        var batch = new AutoEatFoodBatch(storage, context, fee);
        assertTrue(batch.take(batch.candidates().iterator().next(), stack -> true).isEmpty());
        verify(storage).returnStack(player, food);
        verify(storage, never()).extract(player, fee, 2);
        verify(supplier, never()).takeOne(any(), any());
        assertNotNull(batch.failure());
    }

    @Test
    void permissionLossAfterFeePreventsSourceCommit() {
        when(storage.accessible(player)).thenReturn(true, false);
        Source supplier = source("permission", food);
        var batch = new AutoEatFoodBatch(storage, context, fee);
        assertTrue(batch.take(batch.candidates().iterator().next(), stack -> true).isEmpty());
        verify(storage).returnStack(player, fee);
        verify(supplier, never()).takeOne(any(), any());
        assertNotNull(batch.failure());
    }
}
