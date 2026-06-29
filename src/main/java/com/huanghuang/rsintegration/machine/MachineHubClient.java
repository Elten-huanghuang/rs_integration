package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.sidepanel.client.MachineTabHandler;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
public final class MachineHubClient {

    public static KeyMapping KEY_TOGGLE_HUB;

    private MachineHubClient() {}

    public static void init() {
        KEY_TOGGLE_HUB = new KeyMapping(
                "key.rsi.machine_hub",
                KeyConflictContext.UNIVERSAL,
                InputConstants.Type.KEYSYM,
                RSIntegrationConfig.MACHINE_HUB_TOGGLE_KEY.get(),
                "key.categories.rsi"
        );

        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((RegisterKeyMappingsEvent e) -> e.register(KEY_TOGGLE_HUB));

        var bus = MinecraftForge.EVENT_BUS;
        bus.addListener(EventPriority.NORMAL, MachineHubClient::onKeyInput);
    }

    private static void onKeyInput(InputEvent.Key event) {
        if (!RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get()) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        if (KEY_TOGGLE_HUB.isActiveAndMatches(
                InputConstants.getKey(event.getKey(), event.getScanCode()))) {
            var allMachines = MachineTabHandler.getAllMachines();
            if (!allMachines.isEmpty()) {
                MachineHub.toggle(allMachines);
            }
        }
    }
}
