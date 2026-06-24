package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftManager;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.network.AltarBinding;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSBindingHook;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(RSIntegrationMod.MOD_ID)
public final class RSIntegrationMod {

    public static final String MOD_ID = "rs_integration";
    public static final String MOD_NAME = "RS Integration";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);

    public RSIntegrationMod() {
        registerDisplayTest();
        RSIntegrationConfig.register();
        AltarBindingRegistry.registerHook(AltarBinding.RS_NETWORK, RSBindingHook.INSTANCE);

        // Per-mod init (includes binding target registration)
        if (enabled(RSIntegrationConfig.ENABLE_GOETY, "goety")) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.mods.goety.GoetyRSModule::initClient);
            com.huanghuang.rsintegration.mods.goety.GoetyRSModule.initCommon();
        }
        if (enabled(RSIntegrationConfig.ENABLE_WIZARDS_REBORN, "wizards_reborn"))
            com.huanghuang.rsintegration.mods.wizards_reborn.WizardsRebornRSModule.initCommon();
        if (enabled(RSIntegrationConfig.ENABLE_MALUM, "malum"))
            com.huanghuang.rsintegration.mods.malum.MalumRSModule.initCommon();
        if (enabled(RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS, "forbidden_arcanus"))
            com.huanghuang.rsintegration.mods.forbidden.FARSModule.initCommon();
        if (enabled(RSIntegrationConfig.ENABLE_EIDOLON, "eidolon"))
            com.huanghuang.rsintegration.mods.eidolon.EidolonRSModule.initCommon();

        // Subsystems
        if (RSIntegrationConfig.ENABLE_CONTAINER_TRANSFER.get()) {
            com.huanghuang.rsintegration.transfer.ContainerTransferNetworkHandler.register();
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.transfer.ContainerTransferClient::init);
        }
        if (RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.sidepanel.RSSidePanelModule::initClient);
            com.huanghuang.rsintegration.sidepanel.RSSidePanelModule.initCommon();
        }
        if (enabled(RSIntegrationConfig.ENABLE_SOPHISTICATED_BACKPACKS, "sophisticatedbackpacks")) {
            com.huanghuang.rsintegration.backpack.SophisticatedBackpacksItems
                    .init(FMLJavaModLoadingContext.get().getModEventBus());
        }

        // Batch crafting (always registered)
        BatchCraftNetworkHandler.register();
        MinecraftForge.EVENT_BUS.addListener(BatchCraftManager.getInstance()::onServerTick);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer sp)
                BatchCraftManager.getInstance().cancelAllForPlayer(sp.getUUID());
        });

        // Async craft chains
        MinecraftForge.EVENT_BUS.register(com.huanghuang.rsintegration.crafting.AsyncCraftManager.getInstance());
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer sp)
                com.huanghuang.rsintegration.crafting.AsyncCraftManager.getInstance().cancelAllForPlayer(sp.getUUID());
        });

        LOGGER.info("{} initialized.", MOD_NAME);
    }

    private static boolean enabled(net.minecraftforge.common.ForgeConfigSpec.BooleanValue config, String modId) {
        if (!config.get()) return false;
        if (!ModList.get().isLoaded(modId)) return false;
        return true;
    }

    @SuppressWarnings("removal")
    private static void registerDisplayTest() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(
                        () -> NetworkConstants.IGNORESERVERONLY,
                        (remoteVersion, isServer) -> true));
    }
}
