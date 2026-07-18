package com.huanghuang.rsintegration.mods.distantworlds.client;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import com.huanghuang.rsintegration.mods.distantworlds.LithumAltarStatusRequestPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;

public final class DistantWorldsClientSetup {
    private static int requestTicks;

    private DistantWorldsClientSetup() {}

    public static void initClient() {
        MinecraftForge.EVENT_BUS.register(DistantWorldsClientSetup.class);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++requestTicks < 10) return;
        requestTicks = 0;
        Minecraft mc = Minecraft.getInstance();
        if (!RSIntegrationConfig.ENABLE_DISTANT_WORLDS_HUD.get()
                || mc.level == null || mc.player == null
                || !(mc.hitResult instanceof BlockHitResult hit)) return;
        var block = mc.level.getBlockState(hit.getBlockPos()).getBlock();
        var coreClass = com.huanghuang.rsintegration.reflection.probes.DistantWorldsReflection.lithumCoreBlockClass;
        if (coreClass == null || !coreClass.isInstance(block)) return;
        NetworkHandler.CHANNEL.sendToServer(new LithumAltarStatusRequestPacket(
                mc.level.dimension().location(), hit.getBlockPos()));
    }

    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        requestTicks = 0;
        LithumAltarStatusCache.clear();
    }

    public static void onRegisterOverlays(RegisterGuiOverlaysEvent event) {
        if (ModList.get().isLoaded(ModIds.DISTANT_WORLDS)
                && RSIntegrationConfig.ENABLE_DISTANT_WORLDS.get()) {
            event.registerAbove(VanillaGuiOverlay.CROSSHAIR.id(),
                    "lithum_altar_hud", LithumAltarHUDOverlay.INSTANCE);
        }
    }
}
