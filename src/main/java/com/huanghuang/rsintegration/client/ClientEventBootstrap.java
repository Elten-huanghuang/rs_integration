package com.huanghuang.rsintegration.client;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.client.recipe.JeiCraftingPlanContext;
import com.huanghuang.rsintegration.client.recipe.JeiNetworkItemCache;
import com.huanghuang.rsintegration.client.recipe.RecipeAvailabilityClient;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressClientEvents;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressKeybind;
import com.huanghuang.rsintegration.crafting.progress.CraftProgressOverlay;
import com.huanghuang.rsintegration.crafting.planning.PlanningProgressOverlay;
import com.huanghuang.rsintegration.client.storage.DiskTooltipBackground;
import com.huanghuang.rsintegration.client.storage.EquipmentTooltipClient;
import com.huanghuang.rsintegration.mods.aetherworks.client.AetherworksClientSetup;
import com.huanghuang.rsintegration.mods.distantworlds.client.DistantWorldsClientSetup;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.storage.disk.UnifiedDiskVisibility;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import com.huanghuang.rsintegration.feature.autoeat.client.AutoEatClientEvents;
import com.huanghuang.rsintegration.feature.ftbquests.client.FtbQuestJeiRuntime;
import com.huanghuang.rsintegration.crafting.progress.CraftFailureClientCommands;
import com.huanghuang.rsintegration.crafting.tree.RecipeBrowserSlowCallDiagnostics;
import com.huanghuang.rsintegration.machine.client.BeyondDimensionsMachineHubClient;
import com.huanghuang.rsintegration.feature.resonance.bridge.ClientDiskData;
import com.huanghuang.rsintegration.feature.sidepanel.RSSidePanelClient;
import com.huanghuang.rsintegration.feature.villager.tradelock.client.VillagerTradeLockClient;

/** Owns client-only listener references so the common mod class remains dedicated-server safe. */
@OnlyIn(Dist.CLIENT)
public final class ClientEventBootstrap {
    private ClientEventBootstrap() {}

    public static void register() {
        RSIntegrationMod.MOD_BUS.addListener(AetherworksClientSetup::onRegisterOverlays);
        RSIntegrationMod.MOD_BUS.addListener(DistantWorldsClientSetup::onRegisterOverlays);
        CraftProgressKeybind.register();

        MinecraftForge.EVENT_BUS.register(CraftProgressOverlay.class);
        MinecraftForge.EVENT_BUS.register(PlanningProgressOverlay.class);
        MinecraftForge.EVENT_BUS.register(CraftFailureClientCommands.class);
        MinecraftForge.EVENT_BUS.register(DiskTooltipBackground.class);
        MinecraftForge.EVENT_BUS.register(EquipmentTooltipClient.class);
        MinecraftForge.EVENT_BUS.register(
                VillagerTradeLockClient.class);
        // The auto-eat UI supports both RS Grid and the BD terminal. The
        // listener uses class-name checks, so it remains safe in BD-only
        // installations where RS client classes are absent.
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                || ModList.get().isLoaded("beyonddimensions")) {
            MinecraftForge.EVENT_BUS.register(
                    AutoEatClientEvents.class);
            MinecraftForge.EVENT_BUS.addListener(
                    AutoEatClientEvents::onScreenRender);
        }
        if (ModList.get().isLoaded("beyonddimensions")) {
            MinecraftForge.EVENT_BUS.register(
                    BeyondDimensionsMachineHubClient.class);
        }
        if (ModList.get().isLoaded(ModIds.FTB_QUESTS)) {
            MinecraftForge.EVENT_BUS.register(
                    FtbQuestJeiRuntime.class);
        }
        MinecraftForge.EVENT_BUS.addListener(CraftProgressClientEvents::onClientLogin);
        MinecraftForge.EVENT_BUS.addListener(CraftProgressClientEvents::onClientLogout);
        MinecraftForge.EVENT_BUS.addListener(ClientEventBootstrap::onClientLogout);
    }

    private static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        UnifiedDiskVisibility.disconnect();
        RecipeAvailabilityClient.clear();
        JeiNetworkItemCache.INSTANCE.clear();
        JeiCraftingPlanContext.INSTANCE.clear();
        RecipeBrowserSlowCallDiagnostics.reset();
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            RSSidePanelClient.clearOnLogout();
        }
        ClientDiskData.clear();
    }
}
