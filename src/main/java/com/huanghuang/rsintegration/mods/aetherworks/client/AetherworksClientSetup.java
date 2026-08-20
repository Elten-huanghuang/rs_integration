package com.huanghuang.rsintegration.mods.aetherworks.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.fml.ModList;

@OnlyIn(Dist.CLIENT)
public final class AetherworksClientSetup {

    private AetherworksClientSetup() {}

    public static void initClient() {
        MinecraftForge.EVENT_BUS.register(AetherworksClientSetup.class);

        if (ModList.get().isLoaded(ModIds.AETHERWORKS)) {
            MinecraftForge.EVENT_BUS.register(LeverInterceptHandler.class);
        }
    }

    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        if (ModList.get().isLoaded(ModIds.AETHERWORKS)) {
            event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(), "anvil_hud", AnvilHUDOverlay.INSTANCE);
        }
    }

    /**
     * Registered on the MOD bus via {@code MOD_BUS.addListener()} in RSIntegrationMod.
     * Must not carry @SubscribeEvent so the AUTO (MOD-bus) registration in
     * ForgeEventBus registration within initClient() does not also pick it up.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!ModList.get().isLoaded(ModIds.AETHERWORKS)) return;

        LeverBinder.onTick();

    }
}
