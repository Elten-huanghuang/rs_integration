package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
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
    private static final Map<UUID, Long> TERMINAL_SINCE = new LinkedHashMap<>();
    private static final long TERMINAL_TIMEOUT_MS = 5_000;
    private static boolean visible = true;

    private CraftProgressTracker() {}

    public static void onStarted(CraftStartedPacket packet) {
        ACTIVE.put(packet.craftId(),
                new CraftProgressSnapshot(packet.craftId(), 0, (byte) 0, 0,
                        packet.totalNodes(), 0, null));
    }

    public static void onProgress(CraftProgressSnapshot snapshot) {
        CraftProgressSnapshot existing = ACTIVE.get(snapshot.craftId());
        if (existing != null && snapshot.sequence() <= existing.sequence() && !snapshot.isTerminal()) return;
        ACTIVE.put(snapshot.craftId(), snapshot);
        if (snapshot.isTerminal() || snapshot.failedStep() != null
                || snapshot.chainState() == CraftProgressSnapshot.STATE_STOPPING) {
            TERMINAL_SINCE.putIfAbsent(snapshot.craftId(), System.currentTimeMillis());
        }
    }

    private static void expireTerminal() {
        long now = System.currentTimeMillis();
        TERMINAL_SINCE.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > TERMINAL_TIMEOUT_MS) {
                ACTIVE.remove(entry.getKey());
                return true;
            }
            return false;
        });
    }

    @Nullable
    public static CraftProgressSnapshot get(UUID craftId) {
        return ACTIVE.get(craftId);
    }

    @Nullable
    public static CraftProgressSnapshot first() {
        expireTerminal();
        return ACTIVE.values().stream().findFirst().orElse(null);
    }

    public static void remove(UUID craftId) {
        ACTIVE.remove(craftId);
        TERMINAL_SINCE.remove(craftId);
    }

    public static boolean hasActive() {
        expireTerminal();
        return !ACTIVE.isEmpty();
    }

    public static boolean isVisible() { return visible; }

    public static void toggleVisible() { visible = !visible; }
}
