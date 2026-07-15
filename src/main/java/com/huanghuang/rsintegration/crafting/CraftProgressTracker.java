package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.crafting.batch.CraftStatusRequestPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side tracker for running craft progress.
 * Receives S2C updates and deduplicates by sequence number.
 * Terminal entries auto-expire after 5 seconds.
 */
@OnlyIn(Dist.CLIENT)
public final class CraftProgressTracker {

    private static final Map<UUID, CraftProgressSnapshot> ACTIVE = new LinkedHashMap<>();
    private static final Map<UUID, ItemStack> TARGETS = new LinkedHashMap<>();
    private static final Map<UUID, Long> TERMINAL_SINCE = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_UPDATED = new LinkedHashMap<>();
    private static final Map<UUID, Long> LAST_STATUS_REQUEST = new LinkedHashMap<>();
    private static final long TERMINAL_TIMEOUT_MS = 5_000;
    private static final long STALE_STATUS_MS = 5_000;
    private static boolean visible = true;

    private CraftProgressTracker() {}

    public static void onStarted(CraftStartedPacket packet) {
        ACTIVE.putIfAbsent(packet.craftId(),
                new CraftProgressSnapshot(packet.craftId(), 0,
                        CraftProgressSnapshot.Result.RUNNING, CraftProgressSnapshot.Reason.NONE,
                        0, packet.totalNodes(), 0, null));
        TARGETS.put(packet.craftId(), packet.target());
        LAST_UPDATED.putIfAbsent(packet.craftId(), System.currentTimeMillis());
    }

    public static void onProgress(CraftProgressSnapshot snapshot) {
        CraftProgressSnapshot existing = ACTIVE.get(snapshot.craftId());
        if (existing != null && snapshot.sequence() <= existing.sequence() && !snapshot.isTerminal()) return;
        ACTIVE.put(snapshot.craftId(), snapshot);
        LAST_UPDATED.put(snapshot.craftId(), System.currentTimeMillis());
        LAST_STATUS_REQUEST.remove(snapshot.craftId());
        if (snapshot.isTerminal()) {
            TERMINAL_SINCE.putIfAbsent(snapshot.craftId(), System.currentTimeMillis());
        }
    }

    private static void expireTerminal() {
        long now = System.currentTimeMillis();
        TERMINAL_SINCE.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > TERMINAL_TIMEOUT_MS) {
                ACTIVE.remove(entry.getKey());
                TARGETS.remove(entry.getKey());
                LAST_UPDATED.remove(entry.getKey());
                LAST_STATUS_REQUEST.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    @Nullable
    public static CraftProgressSnapshot get(UUID craftId) {
        return ACTIVE.get(craftId);
    }

    private static void requestStaleStatuses() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, CraftProgressSnapshot> entry : List.copyOf(ACTIVE.entrySet())) {
            if (entry.getValue().isTerminal()) continue;
            long updated = LAST_UPDATED.getOrDefault(entry.getKey(), now);
            long requested = LAST_STATUS_REQUEST.getOrDefault(entry.getKey(), 0L);
            if (now - updated < STALE_STATUS_MS || now - requested < STALE_STATUS_MS) continue;
            LAST_STATUS_REQUEST.put(entry.getKey(), now);
            BatchCraftNetworkHandler.CHANNEL.sendToServer(new CraftStatusRequestPacket(entry.getKey()));
        }
    }

    @Nullable
    public static CraftProgressSnapshot first() {
        expireTerminal();
        requestStaleStatuses();
        return ACTIVE.values().stream().findFirst().orElse(null);
    }

    public static ItemStack target(UUID craftId) {
        ItemStack target = TARGETS.get(craftId);
        return target == null ? ItemStack.EMPTY : target.copy();
    }

    public static Collection<CraftProgressSnapshot> snapshots() {
        expireTerminal();
        return java.util.List.copyOf(ACTIVE.values());
    }

    public static void remove(UUID craftId) {
        ACTIVE.remove(craftId);
        TARGETS.remove(craftId);
        TERMINAL_SINCE.remove(craftId);
        LAST_UPDATED.remove(craftId);
        LAST_STATUS_REQUEST.remove(craftId);
    }

    /** Reconcile local state against one authoritative full-status response. */
    public static void retainOnly(Collection<UUID> craftIds) {
        HashSet<UUID> active = new HashSet<>(craftIds);
        ACTIVE.keySet().removeIf(craftId -> !active.contains(craftId));
        TARGETS.keySet().removeIf(craftId -> !active.contains(craftId));
        TERMINAL_SINCE.keySet().removeIf(craftId -> !active.contains(craftId));
        LAST_UPDATED.keySet().removeIf(craftId -> !active.contains(craftId));
        LAST_STATUS_REQUEST.keySet().removeIf(craftId -> !active.contains(craftId));
    }

    /** Clear all client-only state on disconnect before joining another server. */
    public static void clear() {
        ACTIVE.clear();
        TARGETS.clear();
        TERMINAL_SINCE.clear();
        LAST_UPDATED.clear();
        LAST_STATUS_REQUEST.clear();
        visible = true;
    }

    public static boolean hasActive() {
        expireTerminal();
        return !ACTIVE.isEmpty();
    }

    public static boolean isVisible() { return visible; }

    public static void toggleVisible() { visible = !visible; }
}
