package com.huanghuang.rsintegration.storage;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.api.RSIItemRetrieval.Intent;
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
import java.util.Set;
import java.util.TreeMap;

public final class ItemRetrievalFallbacks {
    private record Registered(Handler handler, Set<Destination> destinations, boolean supportsTopUp) {
        boolean accepts(Request request) {
            if (request.intent() == Intent.TOP_UP && !supportsTopUp) return false;
            if (destinations != null) return destinations.contains(request.destination());
            return request.scene() == Scene.JEI ? request.destination() == Destination.PLAYER_INVENTORY
                    : request.destination() == Destination.SELECTED_HOTBAR_SLOT;
        }
    }

    private static final Map<ResourceLocation, Registered> HANDLERS = new TreeMap<>();

    private ItemRetrievalFallbacks() {}

    public static synchronized boolean register(ResourceLocation id, Handler handler) {
        return HANDLERS.putIfAbsent(Objects.requireNonNull(id),
                new Registered(Objects.requireNonNull(handler), null, false)) == null;
    }

    public static synchronized boolean register(ResourceLocation id, Set<Destination> destinations,
                                                boolean supportsTopUp, Handler handler) {
        return HANDLERS.putIfAbsent(Objects.requireNonNull(id),
                new Registered(Objects.requireNonNull(handler), Set.copyOf(destinations), supportsTopUp)) == null;
    }

    private static synchronized List<Map.Entry<ResourceLocation, Handler>> handlers(Request request) {
        return HANDLERS.entrySet().stream().filter(entry -> entry.getValue().accepts(request))
                .map(entry -> Map.entry(entry.getKey(), entry.getValue().handler())).toList();
    }

    public static int onMissing(ServerPlayer player, ItemStack target, int count, Scene scene,
                                Destination destination, StorageReference storage) {
        if (target.isEmpty() || count <= 0) return 0;
        return Math.max(0, tryOnMissing(new Request(player, target, count, scene, destination,
                storage.backendId().value(), storage.networkId())));
    }

    /** 处理器已运行但无法确认交付结果时返回 -1。 */
    static int tryOnMissing(Request request) {
        ServerPlayer player = request.player();
        var registered = handlers(request);
        if (registered.isEmpty() || player.getServer() == null || !player.getServer().isSameThread()) return 0;
        return dispatchOutcome(request, registered);
    }

    static int dispatch(Request request, List<Map.Entry<ResourceLocation, Handler>> handlers) {
        return Math.max(0, dispatchOutcome(request, handlers));
    }

    static int dispatchOutcome(Request request, List<Map.Entry<ResourceLocation, Handler>> handlers) {
        for (var entry : handlers) {
            try {
                Result result = entry.getValue().tryDeliver(request);
                if (result == Result.PASS) continue;
                if (result instanceof Delivered delivered && delivered.actualCount() <= request.requestedCount()) {
                    return delivered.actualCount();
                }
                RSIntegrationMod.LOGGER.warn("[RSI-Restock] Invalid fallback result from {}", entry.getKey());
            } catch (RuntimeException | LinkageError exception) {
                RSIntegrationMod.LOGGER.warn("[RSI-Restock] Fallback {} failed", entry.getKey(), exception);
            }
            // 处理器可能已经产生副作用，不能继续尝试下一来源。
            return -1;
        }
        return 0;
    }
}
