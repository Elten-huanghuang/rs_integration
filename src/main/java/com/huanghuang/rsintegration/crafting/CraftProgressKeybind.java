package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Keybind to toggle the craft-progress HUD overlay on/off.
 * Unbound by default; works only when an active craft exists.
 */
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
        if (!CraftProgressTracker.hasActive()) return;
        while (TOGGLE_PROGRESS.consumeClick()) {
            Minecraft.getInstance().setScreen(new CraftProgressScreen());
        }
    }
}
