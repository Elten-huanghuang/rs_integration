package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Delivered;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Destination;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Handler;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Request;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Result;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Scene;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

class ItemRetrievalFallbacksTest extends BootstrapTest {
    @Test
    void passContinuesAndDeliveryStopsAtActualCount() {
        Request request = request();
        assertEquals(2, ItemRetrievalFallbacks.dispatch(request, List.of(
                handler("pass", received -> {
                    received.target().shrink(1);
                    return Result.PASS;
                }),
                handler("deliver", received -> {
                    assertEquals(1, received.target().getCount());
                    return new Delivered(2);
                }),
                handler("never", received -> fail("已交付后不应调用后续处理器")))));
    }

    @Test
    void invalidDeliveryCannotFallThroughToAnotherSource() {
        assertEquals(0, ItemRetrievalFallbacks.dispatch(request(), List.of(
                handler("invalid", received -> new Delivered(5)),
                handler("never", received -> fail("结果异常后不应尝试第二个来源")))));
    }

    @Test
    void partialNativeDeliveryAndPermissionFailureAreNotShortages() {
        var mode = StorageOperationMode.PERFORM;
        assertTrue(StorageRestockSupport.confirmedMissing(StorageOperationResult.extracted(mode, 4, List.of())));
        assertFalse(StorageRestockSupport.confirmedMissing(StorageOperationResult.extracted(
                mode, 4, List.of(new ItemStack(Items.IRON_INGOT)))));
        assertFalse(StorageRestockSupport.confirmedMissing(StorageOperationResult.failedExtraction(
                mode, 4, StorageOperationStatus.DENIED, List.of(), List.of())));
    }

    private static Request request() {
        return new Request(mock(ServerPlayer.class), new ItemStack(Items.IRON_INGOT), 4,
                Scene.JEI, Destination.PLAYER_INVENTORY, "refinedstorage", "test");
    }

    private static Map.Entry<ResourceLocation, Handler> handler(String path, Handler handler) {
        return Map.entry(ResourceLocation.tryParse("test:" + path), handler);
    }
}
