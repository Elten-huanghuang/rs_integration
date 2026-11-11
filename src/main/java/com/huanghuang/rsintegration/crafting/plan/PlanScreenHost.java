package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import javax.annotation.Nullable;
import java.util.UUID;

/** 将计划组件嵌入容器页面，不切换 Minecraft 的原生菜单屏幕。 */
public interface PlanScreenHost {
    @Nullable Screen rsi$planPage();
    void rsi$setPlanPage(@Nullable Screen page);
    void rsi$openPlans();
    default void rsi$planSubmitted(long requestId) {}
    default void rsi$planAccepted(long requestId, UUID craftId) {}

    @Nullable
    static CraftingPlanScreen currentPlan() {
        Screen screen = Minecraft.getInstance().screen;
        if (screen instanceof CraftingPlanScreen plan) return plan;
        return screen instanceof PlanScreenHost host && host.rsi$planPage() instanceof CraftingPlanScreen plan
                ? plan : null;
    }
}
