package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Delivered;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Destination;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Handler;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Request;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Result;
import com.huanghuang.rsintegration.api.RSIItemRetrievalFallback.Scene;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class ItemRetrievalFallbacks {
    private static final Map<ResourceLocation, Handler> HANDLERS = new TreeMap<>();

    private ItemRetrievalFallbacks() {}

    public static synchronized boolean register(ResourceLocation id, Handler handler) {
        return HANDLERS.putIfAbsent(Objects.requireNonNull(id), Objects.requireNonNull(handler)) == null;
    }

    private static synchronized List<Map.Entry<ResourceLocation, Handler>> handlers() {
        return HANDLERS.entrySet().stream().map(entry -> Map.entry(entry.getKey(), entry.getValue())).toList();
    }

    public static int onMissing(ServerPlayer player, ItemStack target, int count, Scene scene,
                                Destination destination, StorageReference storage) {
        if (count <= 0 || target.isEmpty()) return 0;
        var registered = handlers();
        if (registered.isEmpty() || player.getServer() == null || !player.getServer().isSameThread()) return 0;
        return dispatch(new Request(player, target, count, scene, destination,
                storage.backendId().value(), storage.networkId()), registered);
    }

    static int dispatch(Request request, List<Map.Entry<ResourceLocation, Handler>> handlers) {
        for (var entry : handlers) {
            try {
                Result result = entry.getValue().tryDeliver(request);
                if (result == Result.PASS) continue;
                if (result instanceof Delivered delivered && delivered.actualCount() <= request.requestedCount()) {
                    return delivered.actualCount();
                }
                RSIntegrationMod.LOGGER.warn("[RSI-Restock] Invalid fallback result from {}", entry.getKey());
            } catch (RuntimeException exception) {
                RSIntegrationMod.LOGGER.warn("[RSI-Restock] Fallback {} failed", entry.getKey(), exception);
            }
            // 处理器可能已经产生副作用，不能继续尝试下一来源。
            break;
        }
        return 0;
    }
}
