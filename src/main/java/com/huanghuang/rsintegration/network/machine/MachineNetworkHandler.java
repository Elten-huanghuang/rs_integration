package com.huanghuang.rsintegration.network.machine;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.machine.MachineInteractType;
import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.machine.MachineStatusKey;
import com.huanghuang.rsintegration.machine.MachineStatusReader;
import com.huanghuang.rsintegration.machine.bd.BeyondDimensionsMachineOperations;
import com.huanghuang.rsintegration.network.binding.BindingInfo;
import com.huanghuang.rsintegration.network.binding.BindingSyncCodec;
import com.huanghuang.rsintegration.network.binding.BindingSyncRequestRateLimiter;
import com.huanghuang.rsintegration.network.binding.MachineBindingInfoFactory;
import com.huanghuang.rsintegration.network.binding.MachineBindingService;
import com.huanghuang.rsintegration.network.binding.MachineFavoritesSavedData;
import com.huanghuang.rsintegration.network.binding.MachineBindingSyncBridge;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 机器标签、Hub、收藏、绑定快照和实时状态的网络入口。 */
public final class MachineNetworkHandler {
    public static final SimpleChannel CHANNEL = NetworkHandler.CHANNEL;

    private static final Map<UUID, Map<MachineStatusKey, MachineStatus>>
            lastPushedStatuses = new ConcurrentHashMap<>();
    private static final Set<UUID> dirtyMachinePlayers =
            ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> bindingFingerprints =
            new ConcurrentHashMap<>();
    private static final AtomicLong BINDING_GENERATION = new AtomicLong(1L);

    private static int machineScanCounter;
    private static long machineStatusSequence;
    private static boolean eventsRegistered;

    private MachineNetworkHandler() {}

    public static void registerPackets(SimpleChannel channel) {
        channel.registerMessage(
                NetworkPacketIds.OPEN_BOUND_MACHINE_GUI,
                OpenBoundMachineGuiPacket.class,
                OpenBoundMachineGuiPacket::encode,
                OpenBoundMachineGuiPacket::decode,
                OpenBoundMachineGuiPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(
                NetworkPacketIds.MACHINE_STATUS_DELTA,
                MachineStatusDeltaPacket.class,
                MachineStatusDeltaPacket::encode,
                MachineStatusDeltaPacket::decode,
                MachineStatusDeltaPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(
                NetworkPacketIds.MACHINE_COLLECT,
                MachineCollectPacket.class,
                MachineCollectPacket::encode,
                MachineCollectPacket::decode,
                MachineCollectPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(
                NetworkPacketIds.MACHINE_INSERT,
                MachineInsertPacket.class,
                MachineInsertPacket::encode,
                MachineInsertPacket::decode,
                MachineInsertPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(
                NetworkPacketIds.RS_BINDING_SYNC,
                RSBindingSyncPacket.class,
                RSBindingSyncPacket::encode,
                RSBindingSyncPacket::decode,
                RSBindingSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(
                NetworkPacketIds.UNBIND_MACHINE,
                UnbindMachinePacket.class,
                UnbindMachinePacket::encode,
                UnbindMachinePacket::decode,
                UnbindMachinePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(
                NetworkPacketIds.MACHINE_FAVORITE_TOGGLE,
                MachineFavoriteTogglePacket.class,
                MachineFavoriteTogglePacket::encode,
                MachineFavoriteTogglePacket::decode,
                MachineFavoriteTogglePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(
                NetworkPacketIds.MACHINE_FAVORITES_SYNC,
                MachineFavoritesSyncPacket.class,
                MachineFavoritesSyncPacket::encode,
                MachineFavoritesSyncPacket::decode,
                MachineFavoritesSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(
                NetworkPacketIds.MACHINE_AUTOMATION_BLOCK_TOGGLE,
                MachineAutomationBlockTogglePacket.class,
                MachineAutomationBlockTogglePacket::encode,
                MachineAutomationBlockTogglePacket::decode,
                MachineAutomationBlockTogglePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(
                NetworkPacketIds.MACHINE_AUTOMATION_BLOCK_BATCH,
                MachineAutomationBlockBatchPacket.class,
                MachineAutomationBlockBatchPacket::encode,
                MachineAutomationBlockBatchPacket::decode,
                MachineAutomationBlockBatchPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(
                NetworkPacketIds.RS_BINDING_SYNC_REQUEST,
                RSBindingSyncRequestPacket.class,
                RSBindingSyncRequestPacket::encode,
                RSBindingSyncRequestPacket::decode,
                RSBindingSyncRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
    }

    public static void registerEvents() {
        if (eventsRegistered) return;
        eventsRegistered = true;
        MachineBindingSyncBridge.install(
                MachineNetworkHandler::sendBindingSyncForBackend);
        MinecraftForge.EVENT_BUS.register(MachineNetworkHandler.class);
        MinecraftForge.EVENT_BUS.addListener(
                MachineNetworkHandler::onServerTickEnd);
    }

    public static void markMachineStatusDirty(UUID playerId) {
        if (playerId != null) dirtyMachinePlayers.add(playerId);
    }

    public static void sendBindingSyncRequest() {
        CHANNEL.sendToServer(new RSBindingSyncRequestPacket());
    }

    public static void sendBindingSync(ServerPlayer player) {
        sendCurrentState(player, true);
    }

    private static void sendBindingSyncForBackend(ServerPlayer player) {
        if (ModList.get().isLoaded("refinedstorage")) {
            sendBindingSync(player);
            return;
        }
        BeyondDimensionsMachineOperations.sendBindingSync(player);
    }

    public static void sendCurrentState(
            ServerPlayer player, boolean forceBindings) {
        if (player == null) return;
        markMachineStatusDirty(player.getUUID());
        sendMachineFavoritesSync(player);
        sendBindingSnapshot(player, forceBindings);
    }

    public static void sendMachineFavoritesSync(ServerPlayer player) {
        MachineFavoritesSavedData data =
                MachineFavoritesSavedData.get(player.server);
        CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> player),
                new MachineFavoritesSyncPacket(
                        data.getFavorites(player.getUUID()),
                        data.getBlocked(player.getUUID())));
    }

    public static List<BindingInfo> collectPlayerBindings(
            ServerPlayer player) {
        List<MachineBindingService.PlayerEndpoint> endpoints =
                MachineBindingService.collectPlayerEndpoints(player);
        return toBindingInfos(endpoints);
    }

    @SubscribeEvent
    public static void onPlayerLogout(
            PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID playerId = player.getUUID();
        lastPushedStatuses.remove(playerId);
        bindingFingerprints.remove(playerId);
        dirtyMachinePlayers.remove(playerId);
        BindingSyncRequestRateLimiter.onPlayerLogout(playerId);
    }

    public static void clearServerState() {
        dirtyMachinePlayers.clear();
        lastPushedStatuses.clear();
        bindingFingerprints.clear();
        machineScanCounter = 0;
        machineStatusSequence = 0;
    }

    private static void onServerTickEnd(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        machineScanCounter++;
        if (machineScanCounter % 40 != 0) return;
        for (UUID playerId : List.copyOf(dirtyMachinePlayers)) {
            ServerPlayer player =
                    findPlayer(event.getServer(), playerId);
            if (player != null) pushMachineStatusDeltasFor(player);
            dirtyMachinePlayers.remove(playerId);
        }
    }

    private static void pushMachineStatusDeltasFor(
            ServerPlayer player) {
        List<MachineBindingService.PlayerEndpoint> endpoints =
                MachineBindingService.collectPlayerEndpoints(player);
        Map<MachineStatusKey, MachineStatus> playerLast =
                lastPushedStatuses.computeIfAbsent(
                        player.getUUID(),
                        ignored -> new ConcurrentHashMap<>());
        List<MachineStatusDeltaPacket.Entry> changed = new ArrayList<>();
        Set<MachineStatusKey> activeKeys = new HashSet<>();

        for (MachineBindingService.PlayerEndpoint endpoint : endpoints) {
            var entry = endpoint.entry();
            if (entry.dim() == null) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Delta] Skipping binding with invalid dim: dim={} blockKey={}",
                        entry.dim(), entry.blockKey());
                continue;
            }
            if (MachineInteractType.fromBlockKey(entry.blockKey())
                    != MachineInteractType.QUICK) {
                continue;
            }
            ResourceKey<Level> dimension =
                    ResourceKey.create(Registries.DIMENSION, entry.dim());
            var machineLevel = player.server.getLevel(dimension);
            if (machineLevel == null) continue;

            MachineStatusKey key =
                    new MachineStatusKey(entry.dim(), entry.pos());
            activeKeys.add(key);
            MachineStatus current =
                    MachineStatusReader.read(machineLevel, entry.pos());
            MachineStatus last = playerLast.get(key);
            if (last == null || !current.equals(last)) {
                changed.add(new MachineStatusDeltaPacket.Entry(
                        entry.dim(), entry.pos(), current));
                playerLast.put(key, current);
            }
        }

        for (MachineStatusKey staleKey :
                new ArrayList<>(playerLast.keySet())) {
            if (activeKeys.contains(staleKey)) continue;
            changed.add(MachineStatusDeltaPacket.Entry.removed(
                    staleKey.dimension(), staleKey.pos()));
            playerLast.remove(staleKey);
        }

        if (!changed.isEmpty()) {
            CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new MachineStatusDeltaPacket(
                            changed, ++machineStatusSequence));
        }
    }

    private static void sendBindingSnapshot(
            ServerPlayer player, boolean force) {
        List<MachineBindingService.PlayerEndpoint> endpoints =
                MachineBindingService.collectPlayerEndpoints(player);
        long fingerprint = bindingFingerprint(endpoints);
        Long previous = bindingFingerprints.get(player.getUUID());
        if (!force && previous != null
                && previous.longValue() == fingerprint) {
            return;
        }

        List<BindingInfo> bindings = toBindingInfos(endpoints);
        long generation = BINDING_GENERATION.incrementAndGet();
        for (BindingSyncCodec.Payload chunk :
                BindingSyncCodec.chunks(bindings, generation)) {
            CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> player),
                    new RSBindingSyncPacket(
                            chunk.bindings(), chunk.generation(),
                            chunk.chunkIndex(), chunk.totalChunks()));
        }
        bindingFingerprints.put(player.getUUID(), fingerprint);
    }

    private static List<BindingInfo> toBindingInfos(
            List<MachineBindingService.PlayerEndpoint> endpoints) {
        if (endpoints.isEmpty()) return List.of();
        try {
            return MachineBindingInfoFactory.fromEndpoints(endpoints);
        } catch (Exception exception) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI] Failed to collect player bindings", exception);
            return List.of();
        }
    }

    private static long bindingFingerprint(
            List<MachineBindingService.PlayerEndpoint> endpoints) {
        long hash = 0x9E3779B97F4A7C15L;
        for (MachineBindingService.PlayerEndpoint endpoint : endpoints) {
            var entry = endpoint.entry();
            hash = hash * 31
                    + endpoint.bookId().getMostSignificantBits();
            hash = hash * 31
                    + endpoint.bookId().getLeastSignificantBits();
            hash = hash * 31
                    + endpoint.endpointId().value()
                    .getMostSignificantBits();
            hash = hash * 31
                    + endpoint.endpointId().value()
                    .getLeastSignificantBits();
            hash = hash * 31
                    + (endpoint.itemKey() == null
                    ? 0 : endpoint.itemKey().hashCode());
            hash = hash * 31 + entry.dim().hashCode();
            hash = hash * 31 + entry.pos().asLong();
            hash = hash * 31 + endpoint.revision();
        }
        return hash;
    }

    private static ServerPlayer findPlayer(
            MinecraftServer server, UUID playerId) {
        if (server == null || server.getPlayerList() == null) return null;
        return server.getPlayerList().getPlayer(playerId);
    }
}
