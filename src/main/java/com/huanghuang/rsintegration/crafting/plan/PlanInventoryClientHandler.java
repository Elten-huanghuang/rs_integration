package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
final class PlanInventoryClientHandler {
    private PlanInventoryClientHandler() {}

    static void handle(PlanInventoryUpdatePacket packet) {
        if (Minecraft.getInstance().screen instanceof CraftingPlanScreen screen) screen.updateInventory(packet);
    }
}
