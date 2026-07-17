package com.huanghuang.rsintegration.compat.ftbquests;

import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tracks the player that requested an RS autocrafting task. */
public final class RsAutocraftProgressTracker {
    private static final Map<UUID, UUID> OWNERS = new ConcurrentHashMap<>();

    private RsAutocraftProgressTracker() {}

    public static void remember(UUID taskId, Object requester) {
        if (taskId == null || !(requester instanceof ServerPlayer player)) return;
        OWNERS.put(taskId, player.getUUID());
    }

    public static UUID owner(UUID taskId) {
        return taskId == null ? null : OWNERS.get(taskId);
    }

    public static void forget(UUID taskId) {
        if (taskId != null) OWNERS.remove(taskId);
    }

    public static void clear() {
        OWNERS.clear();
    }
}
