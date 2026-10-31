package com.huanghuang.rsintegration.api.storage;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.storage.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Adapter kept inside RSI; no backend implementation type is exposed by the API. */
@org.jetbrains.annotations.ApiStatus.Internal
public final class DefaultRSIStorageApi implements RSIStorageApi {
    private static final Set<String> REPORTED = new HashSet<>();
    private static <T> T guarded(String operation, java.util.function.Supplier<T> call, T fallback) {
        try { return call.get(); }
        catch (RuntimeException | LinkageError failure) {
            synchronized (REPORTED) {
                if (REPORTED.add(operation)) RSIntegrationMod.LOGGER.warn("[RSI-Storage-API] {} unavailable", operation, failure);
            }
            return fallback;
        }
    }
    private static boolean onServerThread(ServerPlayer player) {
        return player != null && player.server != null && player.server.isSameThread();
    }
    private boolean active() { return RSIStorageServices.get().orElse(null) == this; }
    private StorageLink wrap(ServerPlayer player, StorageSession session) { return new Link(this, player, session); }
    private StorageSession unwrap(ServerPlayer player, StorageLink link) {
        return active() && onServerThread(player) && link instanceof Link l && l.api == this
                && l.owner.equals(player.getUUID()) ? l.session : null;
    }
    @Override public Optional<StorageLink> defaultStorage(ServerPlayer player) {
        if (!active() || !onServerThread(player)) return Optional.empty();
        return guarded("default", () -> {
            var sessions = RSIntegrationMod.STORAGE_BACKENDS.registry().resolveDefaultSessionsForPlayer(player);
            return sessions.size() == 1 && sessions.get(0).isAvailable()
                    && sessions.get(0).hasPermission(player, StoragePermission.VIEW)
                    ? Optional.of(wrap(player, sessions.get(0))) : Optional.empty();
        }, Optional.empty());
    }
    @Override public Optional<StorageLink> resolve(ServerPlayer player, String backendId, String networkId) {
        if (!active() || !onServerThread(player) || backendId == null || networkId == null || backendId.isBlank() || networkId.isBlank()) return Optional.empty();
        return guarded("resolve", () -> RSIntegrationMod.STORAGE_BACKENDS.registry()
                .resolve(new StorageReference(new StorageBackendId(backendId), networkId), player).session()
                .filter(s -> s.isAvailable() && s.hasPermission(player, StoragePermission.VIEW)).map(session -> wrap(player, session)), Optional.empty());
    }
    @Override public boolean isCurrentDefault(ServerPlayer player, StorageLink link) {
        var session = unwrap(player, link); return onServerThread(player) && session != null && defaultStorage(player)
                .map(current -> Objects.equals(current.backendId(), link.backendId()) && Objects.equals(current.networkId(), link.networkId())).orElse(false);
    }
    @Override public Optional<StorageSnapshot> snapshot(ServerPlayer player, StorageLink link, Set<Item> wanted) {
        var session = unwrap(player, link); if (session == null || !onServerThread(player) || wanted == null) return Optional.empty();
        return guarded("snapshot", () -> session.snapshotItems(player, wanted).snapshot()
                .map(snapshot -> (StorageSnapshot) stack -> unwrap(player, link) == null || stack == null || stack.isEmpty() ? 0L
                        : guarded("snapshot count", () -> snapshot.countExact(session.itemKey(stack)), 0L)), Optional.empty());
    }
    @Override public long countExact(ServerPlayer player, StorageLink link, ItemStack stack) {
        var session = unwrap(player, link); if (session == null || !onServerThread(player) || stack == null || stack.isEmpty()) return 0;
        return guarded("count", () -> session.snapshotItems(player, Set.of(stack.getItem())).snapshot()
                .map(s -> s.countExact(session.itemKey(stack))).orElse(0L), 0L);
    }
    @Override public long countSnapshot(StorageSnapshot snapshot, ItemStack stack) {
        return snapshot == null || stack == null ? 0L : guarded("snapshot identity", () -> snapshot.countExact(stack), 0L);
    }
    @Override public boolean hasTool(ServerPlayer player, StorageLink link, Item item) {
        var session = unwrap(player, link); if (session == null || !onServerThread(player) || item == null) return false;
        return guarded("tool", () -> session.snapshotItems(player, Set.of(item)).snapshot()
                .map(s -> s.items().stream().anyMatch(stored -> stored.key().displayStack().is(item)
                        && stored.amount() > 0)).orElse(false), false);
    }
    @Override public List<ItemStack> extractExact(ServerPlayer player, StorageLink link, ItemStack item, int amount) {
        var session = unwrap(player, link); if (session == null || !onServerThread(player) || item == null || item.isEmpty() || amount < 1) return List.of();
        return guarded("extract", () -> {
            var result = session.extractExact(player, session.itemKey(item), amount, false);
            var actual = new ArrayList<ItemStack>(result.extractedStacks()); actual.addAll(result.recoveryStacks());
            if (!result.complete() || actual.stream().anyMatch(s -> !ItemStack.isSameItemSameTags(s, item))
                    || actual.stream().mapToLong(ItemStack::getCount).sum() != amount) {
                actual.forEach(s -> { var remainder = s.copy(); if (!player.getInventory().add(remainder)) player.drop(remainder, false); });
                return List.<ItemStack>of();
            }
            return List.copyOf(actual);
        }, List.of());
    }
    private static final class Link implements StorageLink {
        private final DefaultRSIStorageApi api;
        private final ServerPlayer player;
        private final UUID owner;
        private final StorageSession session;
        private Link(DefaultRSIStorageApi api, ServerPlayer player, StorageSession session) {
            this.api = api; this.player = player; this.owner = player.getUUID(); this.session = session;
        }
        @Override public String backendId() { return session.reference().backendId().value(); }
        @Override public String networkId() { return session.reference().networkId(); }
        @Override public boolean available() {
            return api.active() && onServerThread(player) && guarded("availability",
                    () -> session.isAvailable() && session.hasPermission(player, StoragePermission.VIEW), false);
        }
    }
}
