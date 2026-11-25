package com.huanghuang.rsintegration.feature.autoeat;

import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.Context;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.Source;
import com.huanghuang.rsintegration.api.RSIAutoEatFoodSources.TakeResult;
import com.huanghuang.rsintegration.crafting.NetworkOutputReception;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageItemKey;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class AutoEatStorageTest extends BootstrapTest {
    @Test
    void unavailableSourceRefundsFeeWithoutSatisfyingWaitingCraft() {
        ServerPlayer player = mock(ServerPlayer.class);
        StorageSession session = mock(StorageSession.class);
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        ItemStack food = new ItemStack(Items.MUSHROOM_STEW);
        ItemStack fee = new ItemStack(Items.PAPER);
        Context context = new Context(player, Set.of(food.getItem()));
        Source supplier = mock(Source.class);
        when(supplier.candidates(context)).thenReturn(List.of(food));
        when(supplier.takeOne(eq(context), any())).thenReturn(new TakeResult.Unavailable());
        assertTrue(RSIAutoEatFoodSources.register(ResourceLocation.fromNamespaceAndPath("test", "refund"), supplier));

        StorageBackendId backend = new StorageBackendId("test");
        Object network = new Object();
        var resource = TaskOutputReception.resource(fee);
        var mode = StorageOperationMode.PERFORM;
        var stored = new AtomicLong();
        when(endpoint.session()).thenReturn(session);
        when(session.isAvailable()).thenReturn(true);
        when(session.hasPermission(player, StoragePermission.VIEW)).thenReturn(true);
        when(session.hasPermission(player, StoragePermission.EXTRACT)).thenReturn(true);
        when(session.itemKey(any())).thenAnswer(call -> StorageItemKey.fromItemStack(backend, call.getArgument(0)));
        when(endpoint.extractExact(eq(player), any(), eq(1L), eq(false))).thenAnswer(call ->
                StorageOperationResult.extracted(mode, 1,
                        call.getArgument(1, ItemStack.class).is(Items.PAPER) ? List.of(fee) : List.of()));
        when(endpoint.insert(eq(player), any(), eq(false))).thenAnswer(call -> {
            ItemStack refund = call.getArgument(1);
            return NetworkOutputReception.route(network, resource, refund.getCount(), false, remaining -> {
                stored.addAndGet(remaining);
                return StorageOperationResult.inserted(mode, refund, ItemStack.EMPTY);
            });
        });

        var storage = new AutoEatStorage(endpoint, new StorageSnapshot(backend, List.of()));
        try (var reception = TaskOutputReception.open()) {
            reception.bindNetwork(network, () -> true);
            reception.prepare(List.of(new TaskOutputReception.Demand("fee", resource, 1, 1))).activate();
            var batch = new AutoEatFoodBatch(storage, context, fee);
            assertTrue(batch.take(batch.candidates().iterator().next(), stack -> true).isEmpty());
            assertEquals(1, stored.get());
            assertEquals(0, reception.received(resource));
        }
    }
}
