package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressSnapshot;
import com.huanghuang.rsintegration.crafting.CraftProgressTracker;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.huanghuang.rsintegration.crafting.CraftFailureClientCommands;

import java.util.List;
import java.util.UUID;

@OnlyIn(Dist.CLIENT)
final class CraftProgressClientPacketHandler {
    private CraftProgressClientPacketHandler() {}

    static void onStarted(CraftStartedPacket packet) {
        CraftProgressTracker.onStarted(packet);
    }

    static void onProgress(CraftProgressSnapshot snapshot) {
        if (CraftProgressTracker.onProgress(snapshot)) {
            CraftFailureClientCommands.notifyFailure(snapshot.craftId());
        }
    }

    static void onDelta(CraftProgressDeltaPacket packet) {
        if (CraftProgressTracker.onDelta(packet)) {
            CraftFailureClientCommands.notifyFailure(packet.craftId());
        }
    }

    static void onStatusSync(boolean full, List<UUID> craftIds) {
        if (full) CraftProgressTracker.retainOnly(craftIds);
        else CraftProgressTracker.remove(craftIds.get(0));
    }
}
