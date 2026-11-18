package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.network.binding.MachineBindingService.PlayerEndpoint;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** 统一生成绑定快照；安装环境和网络包由调用方决定。 */
public final class MachineBindingSnapshotService {
    public static final MachineBindingSnapshotService INSTANCE = new MachineBindingSnapshotService();

    private static final ResourceLocation BD_TERMINAL =
            ResourceLocation.fromNamespaceAndPath("beyonddimensions", "net_terminal_item");

    public enum Scope {
        ALL,
        BD_TERMINALS
    }

    private final AtomicLong generation = new AtomicLong(1L);
    private final Map<UUID, Stamp> lastSent = new ConcurrentHashMap<>();

    MachineBindingSnapshotService() {}

    public void send(ServerPlayer player, Scope scope, boolean force,
                     Consumer<BindingSyncCodec.Payload> sender) {
        List<PlayerEndpoint> endpoints = MachineBindingService.collectPlayerEndpoints(player);
        if (scope == Scope.BD_TERMINALS) {
            endpoints = endpoints.stream().filter(endpoint -> BD_TERMINAL.equals(endpoint.itemKey())).toList();
        }
        UUID playerId = player.getUUID();
        Stamp stamp = new Stamp(scope, fingerprint(endpoints));
        if (!force && stamp.equals(lastSent.get(playerId))) return;

        List<BindingInfo> bindings = toBindingInfos(endpoints);
        long snapshotGeneration = generation.incrementAndGet();
        for (BindingSyncCodec.Payload chunk : BindingSyncCodec.chunks(bindings, snapshotGeneration)) {
            sender.accept(chunk);
        }
        lastSent.put(playerId, stamp);
    }

    public void forgetPlayer(UUID playerId) {
        lastSent.remove(playerId);
    }

    public void clear() {
        lastSent.clear();
    }

    private static List<BindingInfo> toBindingInfos(List<PlayerEndpoint> endpoints) {
        try {
            return MachineBindingInfoFactory.fromEndpoints(endpoints);
        } catch (Exception exception) {
            RSIntegrationMod.LOGGER.debug("[RSI] Failed to collect player bindings", exception);
            return List.of();
        }
    }

    private static long fingerprint(List<PlayerEndpoint> endpoints) {
        long hash = 0x9E3779B97F4A7C15L;
        for (PlayerEndpoint endpoint : endpoints) {
            var entry = endpoint.entry();
            hash = hash * 31 + endpoint.bookId().getMostSignificantBits();
            hash = hash * 31 + endpoint.bookId().getLeastSignificantBits();
            hash = hash * 31 + endpoint.endpointId().value().getMostSignificantBits();
            hash = hash * 31 + endpoint.endpointId().value().getLeastSignificantBits();
            hash = hash * 31 + (endpoint.itemKey() == null ? 0 : endpoint.itemKey().hashCode());
            hash = hash * 31 + entry.dim().hashCode();
            hash = hash * 31 + entry.pos().asLong();
            hash = hash * 31 + endpoint.revision();
        }
        return hash;
    }

    private record Stamp(Scope scope, long fingerprint) {}
}
