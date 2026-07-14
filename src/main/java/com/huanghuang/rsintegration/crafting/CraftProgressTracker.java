package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.batch.CraftStartedPacket;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client-side tracker for running craft progress.
 * Receives S2C updates and deduplicates by sequence number.
 */
@OnlyIn(Dist.CLIENT)
public final class CraftProgressTracker {

    private static final Map<UUID, CraftProgressSnapshot> ACTIVE = new HashMap<>();
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
        if (snapshot.chainState() == CraftProgressSnapshot.STATE_STOPPING || snapshot.failedStep() != null) {
            // Will be removed after a short display timeout
        }
    }

    @Nullable
    public static CraftProgressSnapshot get(UUID craftId) {
        return ACTIVE.get(craftId);
    }

    @Nullable
    public static CraftProgressSnapshot first() {
        return ACTIVE.values().stream().findFirst().orElse(null);
    }

    public static void remove(UUID craftId) {
        ACTIVE.remove(craftId);
    }

    public static boolean hasActive() {
        return !ACTIVE.isEmpty();
    }

    public static boolean isVisible() { return visible; }

    public static void toggleVisible() { visible = !visible; }
}
