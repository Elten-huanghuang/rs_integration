package com.huanghuang.rsintegration.crafting.planning;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class PlanningProgressClientPacketHandler {
    private PlanningProgressClientPacketHandler() {}

    static void handle(PlanningProgressSnapshot snapshot) {
        PlanningProgressTracker.update(snapshot);
    }
}
