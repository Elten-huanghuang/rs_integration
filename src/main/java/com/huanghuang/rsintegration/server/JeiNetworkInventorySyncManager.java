package com.huanghuang.rsintegration.server;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.storage.StorageItemChangeListener;
import com.huanghuang.rsintegration.storage.StorageItemSubscription;
import com.huanghuang.rsintegration.storage.StoragePermission;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageRestockSupport;
import com.huanghuang.rsintegration.storage.StorageSession;
import com.huanghuang.rsintegration.storage.StorageSnapshot;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JEI inventory synchronization: one full snapshot, then native RS/BD item events.
 * A 20-tick probe validates only the active reference, permission and subscription;
 * complete inventory polling remains a fallback for backends without subscriptions.
 */
public final class JeiNetworkInventorySyncManager {
    private static final int VALIDATION_INTERVAL = 20;
    private static final int RESYNC_COOLDOWN_TICKS = 20;
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private JeiNetworkInventorySyncManager() {}

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player)) return;
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        if (!RSIntegrationConfig.ENABLE_JEI_NETWORK_OVERLAY.get()) {
            disconnect(player, state);
            return;
        }
        state.validationTicks++;
        if (!state.initialized || state.invalidated
                || state.validationTicks >= VALIDATION_INTERVAL) {
            state.validationTicks = 0;
            validate(player, state);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (Map.Entry<UUID, State> entry : List.copyOf(STATES.entrySet())) {
            State state = entry.getValue();
            if (!state.initialized || state.pending.isEmpty()) continue;
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            List<JeiNetworkInventoryPacket.Entry> changes = new ArrayList<>();
            for (Map.Entry<String, JeiNetworkInventoryPacket.Entry> change
                    : List.copyOf(state.pending.entrySet())) {
                if (state.pending.remove(change.getKey(), change.getValue())) {
                    changes.add(change.getValue());
                }
            }
            if (!changes.isEmpty()) send(player, state, false, changes);
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        State state = STATES.remove(event.getEntity().getUUID());
        if (state != null) state.closeSubscription();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        STATES.values().forEach(State::closeSubscription);
        STATES.clear();
    }

    public static void requestResync(ServerPlayer player, long clientEpoch, long clientSequence) {
        State state = STATES.computeIfAbsent(player.getUUID(), ignored -> new State());
        long tick = player.server.getTickCount();
        if (tick - state.lastResyncTick < RESYNC_COOLDOWN_TICKS) return;
        state.lastResyncTick = tick;
        if (!state.initialized) {
            validate(player, state);
            return;
        }
        state.pending.clear();
        sendFull(player, state);
    }

    private static void validate(ServerPlayer player, State state) {
        CraftStorageEndpoint endpoint;
        try {
            endpoint = StorageRestockSupport.resolve(player).orElse(null);
        } catch (RuntimeException | LinkageError failure) {
            endpoint = null;
        }
        if (endpoint == null || !endpoint.session().hasPermission(player, StoragePermission.VIEW)) {
            disconnect(player, state);
            return;
        }

        StorageSession resolved = endpoint.session();
        StorageReference reference = resolved.reference();
        if (!state.initialized || state.invalidated || !reference.equals(state.reference)) {
            bind(player, state, resolved);
            return;
        }
        if (state.eventDriven) {
            if (state.subscription == null || !state.subscription.isValid()) {
                bind(player, state, resolved);
            }
        } else {
            pollFallback(player, state, resolved);
        }
    }

    private static void bind(ServerPlayer player, State state, StorageSession session) {
        state.closeSubscription();
        state.generation++;
        long generation = state.generation;
        state.epoch++;
        state.sequence = 0L;
        state.initialized = false;
        state.invalidated = false;
        state.eventDriven = false;
        state.reference = session.reference();
        state.session = session;
        state.current.clear();
        state.pending.clear();

        try {
            state.subscription = session.subscribeItemChanges(new StorageItemChangeListener() {
                @Override
                public void onChanged(ItemStack stack, long amount) {
                    if (state.generation != generation || stack == null || stack.isEmpty()) return;
                    JeiNetworkInventoryPacket.Entry change =
                            new JeiNetworkInventoryPacket.Entry(stack, Math.max(0L, amount));
                    String key = key(stack);
                    if (amount <= 0) state.current.remove(key);
                    else state.current.put(key, change);
                    state.pending.put(key, change);
                }

                @Override
                public void onInvalidated() {
                    if (state.generation == generation) state.invalidated = true;
                }
            }).orElse(null);
            state.eventDriven = state.subscription != null;
        } catch (RuntimeException | LinkageError failure) {
            state.subscription = null;
            state.eventDriven = false;
        }

        var snapshotResult = session.snapshotItems(player);
        if (!snapshotResult.successful()) {
            disconnect(player, state);
            return;
        }
        StorageSnapshot snapshot = snapshotResult.snapshot().orElseThrow();
        for (var item : snapshot.items()) {
            if (item.stack().isEmpty() || item.amount() <= 0) continue;
            var entry = new JeiNetworkInventoryPacket.Entry(item.stack(), item.amount());
            state.current.put(key(item.stack()), entry);
        }
        // No backend event can interleave with this synchronous server-thread snapshot.
        state.pending.clear();
        state.initialized = true;
        state.clientCleared = false;
        sendFull(player, state);
    }

    private static void pollFallback(ServerPlayer player, State state, StorageSession session) {
        var snapshotResult = session.snapshotItems(player);
        if (!snapshotResult.successful()) return;
        Map<String, JeiNetworkInventoryPacket.Entry> latest = new LinkedHashMap<>();
        for (var item : snapshotResult.snapshot().orElseThrow().items()) {
            if (item.stack().isEmpty() || item.amount() <= 0) continue;
            latest.put(key(item.stack()), new JeiNetworkInventoryPacket.Entry(item.stack(), item.amount()));
        }
        List<JeiNetworkInventoryPacket.Entry> changes = new ArrayList<>();
        Map<String, JeiNetworkInventoryPacket.Entry> union = new HashMap<>(state.current);
        union.putAll(latest);
        for (Map.Entry<String, JeiNetworkInventoryPacket.Entry> entry : union.entrySet()) {
            long before = state.current.containsKey(entry.getKey())
                    ? state.current.get(entry.getKey()).amount() : 0L;
            long after = latest.containsKey(entry.getKey()) ? latest.get(entry.getKey()).amount() : 0L;
            if (before != after) changes.add(new JeiNetworkInventoryPacket.Entry(entry.getValue().stack(), after));
        }
        state.current.clear();
        state.current.putAll(latest);
        if (!changes.isEmpty()) send(player, state, false, changes);
    }

    private static void disconnect(ServerPlayer player, State state) {
        state.closeSubscription();
        state.generation++;
        state.initialized = false;
        state.invalidated = false;
        state.eventDriven = false;
        state.reference = null;
        state.session = null;
        state.current.clear();
        state.pending.clear();
        if (state.clientCleared) return;
        state.epoch++;
        state.sequence = 0L;
        state.clientCleared = true;
        sendChunks(player, true, state.epoch, ++state.sequence, null, List.of());
    }

    private static void sendFull(ServerPlayer player, State state) {
        List<JeiNetworkInventoryPacket.Entry> snapshot = new ArrayList<>(state.current.values());
        send(player, state, true, snapshot);
    }

    private static void send(ServerPlayer player, State state, boolean full,
                             List<JeiNetworkInventoryPacket.Entry> entries) {
        sendChunks(player, full, state.epoch, ++state.sequence,
                full ? state.reference : null, entries);
    }

    private static void sendChunks(ServerPlayer player, boolean full, long epoch, long sequence,
                                   @Nullable StorageReference reference,
                                   List<JeiNetworkInventoryPacket.Entry> source) {
        int chunkCount = Math.max(1, (source.size() + JeiNetworkInventoryPacket.MAX_ENTRIES - 1)
                / JeiNetworkInventoryPacket.MAX_ENTRIES);
        if (chunkCount > JeiNetworkInventoryPacket.MAX_CHUNKS) {
            throw new IllegalStateException("JEI inventory snapshot exceeds packet chunk limit");
        }
        for (int chunk = 0; chunk < chunkCount; chunk++) {
            int from = chunk * JeiNetworkInventoryPacket.MAX_ENTRIES;
            int to = Math.min(source.size(), from + JeiNetworkInventoryPacket.MAX_ENTRIES);
            List<JeiNetworkInventoryPacket.Entry> entries = from >= to
                    ? List.of() : List.copyOf(source.subList(from, to));
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                    new JeiNetworkInventoryPacket(full, epoch, sequence, chunk, chunkCount,
                            reference, entries));
        }
    }

    private static String key(ItemStack stack) {
        return JeiNetworkInventoryPacket.key(stack);
    }

    private static final class State {
        int validationTicks;
        boolean initialized;
        boolean invalidated;
        boolean eventDriven;
        boolean clientCleared = true;
        long generation;
        long epoch;
        long sequence;
        long lastResyncTick = Long.MIN_VALUE / 2;
        @Nullable StorageReference reference;
        @Nullable StorageSession session;
        @Nullable StorageItemSubscription subscription;
        final Map<String, JeiNetworkInventoryPacket.Entry> current = new ConcurrentHashMap<>();
        final Map<String, JeiNetworkInventoryPacket.Entry> pending = new ConcurrentHashMap<>();

        void closeSubscription() {
            StorageItemSubscription old = subscription;
            subscription = null;
            if (old != null) old.close();
        }
    }
}
