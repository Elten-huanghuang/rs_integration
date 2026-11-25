package com.huanghuang.rsintegration.mixin.constructionwand;

import com.huanghuang.rsintegration.crafting.CraftItemRecovery;
import com.huanghuang.rsintegration.crafting.DeepPlanningFixture;
import com.huanghuang.rsintegration.crafting.NetworkOutputReception;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.storage.StorageDiagnosticCode;
import com.huanghuang.rsintegration.storage.StorageOperationMode;
import com.huanghuang.rsintegration.storage.StorageOperationResult;
import com.huanghuang.rsintegration.storage.StorageOperationStatus;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SupplierInventoryNetworkMixinTest extends BootstrapTest {
    private final UUID owner = UUID.randomUUID();
    private final ItemStack refund = new ItemStack(Items.STONE, 7);
    private final ServerPlayer player = mock(ServerPlayer.class);
    private final CraftStorageEndpoint storage = mock(CraftStorageEndpoint.class);
    private final CraftItemRecovery recovery = new CraftItemRecovery();

    @BeforeAll
    static void initializeMod() {
        DeepPlanningFixture.initializeMod();
    }

    @Test
    void onlyKnownRejectedItemsAreRetainedAndTaskDoesNotReceiveRefund() throws Exception {
        var key = TaskOutputReception.resource(refund);
        when(storage.insert(eq(player), any(ItemStack.class), eq(false))).thenAnswer(call -> {
            ItemStack stack = call.getArgument(1);
            assertEquals(7, stack.getCount());
            return NetworkOutputReception.route(storage, key, stack.getCount(), false,
                    left -> StorageOperationResult.inserted(StorageOperationMode.PERFORM, stack,
                            stack.copyWithCount(Math.max(0, (int) left - 4))));
        });
        try (var task = TaskOutputReception.open()) {
            task.bindNetwork(storage, () -> true);
            task.prepare(List.of(new TaskOutputReception.Demand("out", key, 7, 7))).activate();
            takeWithRefund();
            assertEquals(0, task.received(key));
        }

        var pending = recovery.save(new CompoundTag()).getList("pending", Tag.TAG_COMPOUND);
        assertEquals(1, pending.size());
        CompoundTag entry = pending.getCompound(0);
        assertEquals(owner, entry.getUUID("owner"));
        assertTrue(ItemStack.of(entry.getCompound("stack")).is(Items.STONE));
        assertEquals(3, entry.getInt("amount"));
        verify(player).sendSystemMessage(Component.translatable("rsi.item_recovery.pending"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownOrThrowingWritebackDoesNotRetryOrRefundTheFullStack(boolean throwsAfterWrite) throws Exception {
        when(storage.insert(eq(player), any(ItemStack.class), eq(false))).thenAnswer(call -> {
            if (throwsAfterWrite) throw new IllegalStateException("写入结果未知");
            return StorageOperationResult.indeterminateInsert(refund, StorageDiagnosticCode.BACKEND_EXCEPTION);
        });
        takeWithRefund();
        assertFalse(recovery.hasPending(owner));
    }

    private void takeWithRefund() throws Exception {
        var server = mock(MinecraftServer.class);
        var serverField = ServerPlayer.class.getField("server");
        serverField.setAccessible(true);
        serverField.set(player, server);
        when(player.getUUID()).thenReturn(owner);

        var mixin = new SupplierInventoryNetworkMixin() {};
        mixin.player = player;
        var requested = new ItemStack(Items.DIRT, 5);
        when(storage.extractExact(eq(player), any(ItemStack.class), eq(5L), eq(false)))
                .thenReturn(StorageOperationResult.failedExtraction(StorageOperationMode.PERFORM,
                        5, StorageOperationStatus.INVALID_RESPONSE, List.of(requested.copyWithCount(2)),
                        List.of(refund), StorageDiagnosticCode.INVALID_NATIVE_RESPONSE));
        var take = SupplierInventoryNetworkMixin.class.getDeclaredMethod(
                "rsi$takeFromNetwork", ItemStack.class, CallbackInfoReturnable.class);
        take.setAccessible(true);
        var callback = new CallbackInfoReturnable<>("takeItemStack", true, 5);
        try (var restock = mockStatic(StorageRestockSupport.class);
             var recoveryData = mockStatic(CraftItemRecovery.class)) {
            restock.when(() -> StorageRestockSupport.resolve(player)).thenReturn(Optional.of(storage));
            recoveryData.when(() -> CraftItemRecovery.get(server)).thenReturn(recovery);
            take.invoke(mixin, requested, callback);
        }

        assertEquals(3, callback.getReturnValueI());
        verify(storage).insert(eq(player), any(ItemStack.class), eq(false));
    }
}
