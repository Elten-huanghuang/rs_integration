package com.huanghuang.rsintegration.crafting.progress;

import com.huanghuang.rsintegration.crafting.MachineManagementScreen;
import com.huanghuang.rsintegration.crafting.planning.PlanningTasksScreen;
import com.huanghuang.rsintegration.crafting.plan.PlanScreenHost;
import com.huanghuang.rsintegration.mods.rs.monitor.RsMonitorClient;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.lwjgl.glfw.GLFW;

/** 世界内的控制中心入口；有计算/待确认计划时先打开列表，编辑其他界面时不抢键。 */
@OnlyIn(Dist.CLIENT)
public final class CraftProgressKeybind {

    private static volatile boolean registered;
    public static KeyMapping TOGGLE_PROGRESS;

    private CraftProgressKeybind() {}

    public static void register() {
        if (registered) return;
        registered = true;
        TOGGLE_PROGRESS = new KeyMapping(
                "key.rsi.toggle_progress",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "key.categories.rsi"
        );
        RSIntegrationMod.MOD_BUS.addListener(
                (RegisterKeyMappingsEvent event) -> event.register(TOGGLE_PROGRESS));
        MinecraftForge.EVENT_BUS.register(CraftProgressKeybind.class);
    }

    public static Component translatedKeyMessage() {
        return TOGGLE_PROGRESS == null
                ? InputConstants.UNKNOWN.getDisplayName()
                : TOGGLE_PROGRESS.getTranslatedKeyMessage();
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (TOGGLE_PROGRESS == null) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        while (TOGGLE_PROGRESS.consumeClick()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null) continue;
            if (minecraft.screen != null && minecraft.screen.getFocused() instanceof EditBox) continue;
            if (minecraft.screen instanceof PlanScreenHost host) {
                if (host.rsi$planPage() != null && host.rsi$planPage().getFocused() instanceof EditBox) continue;
                if (host.rsi$planPage() == null) host.rsi$openPlans();
                else host.rsi$setPlanPage(null);
            } else if (minecraft.screen instanceof CraftProgressScreen
                    || minecraft.screen instanceof PlanningTasksScreen
                    || minecraft.screen instanceof MachineManagementScreen) {
                minecraft.setScreen(null);
            } else if (minecraft.screen == null) {
                // Shift+P 始终保留个人计划入口；P 优先使用背包中的原生无线监控器。
                if (!Screen.hasShiftDown() && ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                        && RsMonitorClient.openWirelessMonitor()) continue;
                if (PlanningTasksScreen.taskCount() > 0 || Screen.hasShiftDown()) {
                    minecraft.setScreen(new PlanningTasksScreen(null));
                } else if (!CraftProgressTracker.taskSnapshots().isEmpty()) {
                    ExecutionMonitorClient.open();
                } else {
                    minecraft.setScreen(new MachineManagementScreen(null, MachineManagementScreen.View.ALL_BOUND));
                }
            }
        }
    }
}
