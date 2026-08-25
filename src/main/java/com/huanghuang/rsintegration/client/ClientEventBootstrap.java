package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftProgressClientEvents;
import com.huanghuang.rsintegration.crafting.CraftProgressKeybind;
import com.huanghuang.rsintegration.crafting.CraftProgressOverlay;
import com.huanghuang.rsintegration.mods.aetherworks.client.AetherworksClientSetup;
import com.huanghuang.rsintegration.mods.distantworlds.client.DistantWorldsClientSetup;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;

/** Owns client-only listener references so the common mod class remains dedicated-server safe. */
@OnlyIn(Dist.CLIENT)
public final class ClientEventBootstrap {
    private ClientEventBootstrap() {}

    public static void register() {
        RSIntegrationMod.MOD_BUS.addListener(AetherworksClientSetup::onRegisterOverlays);
        RSIntegrationMod.MOD_BUS.addListener(DistantWorldsClientSetup::onRegisterOverlays);
        CraftProgressKeybind.register();

        MinecraftForge.EVENT_BUS.register(CraftProgressOverlay.class);
        MinecraftForge.EVENT_BUS.register(
                com.huanghuang.rsintegration.villager.tradelock.client.VillagerTradeLockClient.class);
        // The auto-eat UI supports both RS Grid and the BD terminal. The
        // listener uses class-name checks, so it remains safe in BD-only
        // installations where RS client classes are absent.
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                || ModList.get().isLoaded("beyonddimensions")) {
            MinecraftForge.EVENT_BUS.register(
                    com.huanghuang.rsintegration.autoeat.client.AutoEatClientEvents.class);
            MinecraftForge.EVENT_BUS.addListener(
                    com.huanghuang.rsintegration.autoeat.client.AutoEatClientEvents::onScreenRender);
        }
        if (ModList.get().isLoaded("beyonddimensions")) {
            MinecraftForge.EVENT_BUS.register(
                    com.huanghuang.rsintegration.machine.BeyondDimensionsMachineHubClient.class);
        }
        if (ModList.get().isLoaded(ModIds.FTB_QUESTS)) {
            MinecraftForge.EVENT_BUS.register(
                    com.huanghuang.rsintegration.compat.ftbquests.client.FtbQuestJeiRuntime.class);
        }
        MinecraftForge.EVENT_BUS.addListener(CraftProgressClientEvents::onClientLogin);
        MinecraftForge.EVENT_BUS.addListener(CraftProgressClientEvents::onClientLogout);
        MinecraftForge.EVENT_BUS.addListener(ClientEventBootstrap::onClientLogout);
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        com.huanghuang.rsintegration.mods.goety.RSClientAvailabilityCache.clear();
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            com.huanghuang.rsintegration.sidepanel.RSSidePanelClient.clearOnLogout();
        }
        com.huanghuang.rsintegration.resonance.bridge.ClientDiskData.clear();
    }
}
