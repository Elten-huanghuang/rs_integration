package com.huanghuang.rsintegration.mods.aetherworks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

@OnlyIn(Dist.CLIENT)
public final class AetherworksClientSetup {

    private static boolean autoHammerEnabled;

    private AetherworksClientSetup() {}

    public static void initClient() {
        MinecraftForge.EVENT_BUS.register(AetherworksClientSetup.class);

        if (ModList.get().isLoaded("aetherworks")) {
            MinecraftForge.EVENT_BUS.register(AutoHammerHandler.class);
            MinecraftForge.EVENT_BUS.register(LeverInterceptHandler.class);
        }
    }

    /**
     * Registered on the MOD bus via {@code MOD_BUS.addListener()} in RSIntegrationMod.
     * Must not carry @SubscribeEvent so the AUTO (MOD-bus) registration in
     * ForgeEventBus registration within initClient() does not also pick it up.
     */
    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        if (ModList.get().isLoaded("aetherworks")) {
            event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "anvil_hud", AnvilHUDOverlay.INSTANCE);
        }
    }

    public static void toggleAutoHammer() {
        autoHammerEnabled = !autoHammerEnabled;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal(autoHammerEnabled ? "§a自动锤炼: 开启" : "§c自动锤炼: 关闭"), true);
        }
    }

    public static boolean isAutoHammerEnabled() { return autoHammerEnabled; }
    public static void setAutoHammerEnabled(boolean v) { autoHammerEnabled = v; }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!ModList.get().isLoaded("aetherworks")) return;

        LeverBinder.onTick();

        if (autoHammerEnabled) {
            AutoHammerHandler.tryAutoHammer();
        }
    }
}
