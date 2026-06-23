package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.batch.BatchCraftManager;
import com.huanghuang.rsintegration.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.batch.ModType;
import com.huanghuang.rsintegration.integration.AltarBinding;
import com.huanghuang.rsintegration.integration.AltarBindingRegistry;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.integration.BindingEventHandler;
import com.huanghuang.rsintegration.integration.RSBindingHook;
import com.huanghuang.rsintegration.integration.sophisticatedbackpacks.SophisticatedBackpacksItems;

import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
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

        // Register RS binding hook (always — no mod dependency)
        AltarBindingRegistry.registerHook(AltarBinding.RS_NETWORK, RSBindingHook.INSTANCE);

        // Register unified binding targets
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "goety", ModType.GOETY, RSIntegrationConfig.ENABLE_GOETY, List.of(
                "com.Polarice3.Goety.common.blocks.DarkAltarBlock",
                "com.Polarice3.Goety.common.blocks.NecroBrazierBlock",
                "com.Polarice3.Goety.common.blocks.CursedCageBlock",
                "com.Polarice3.Goety.common.blocks.SoulCandlestickBlock"
        ), null));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "malum", ModType.MALUM, RSIntegrationConfig.ENABLE_MALUM, List.of(
                "com.sammy.malum.common.block.curiosities.spirit_altar.SpiritAltarBlock"
        ), null));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "forbidden_arcanus", ModType.FORBIDDEN_ARCANUS, RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS, List.of(
                "com.stal111.forbidden_arcanus.common.block.forge.HephaestusForgeBlock"
        ), null));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "eidolon", ModType.EIDOLON, RSIntegrationConfig.ENABLE_EIDOLON, List.of(
                "elucent.eidolon.common.block.CrucibleBlock"
        ), null));
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "wizards_reborn", ModType.WIZARDS_REBORN, RSIntegrationConfig.ENABLE_WIZARDS_REBORN, List.of(
                "mod.maxbogomol.wizards_reborn.common.block.wissen_crystallizer.WissenCrystallizerBlock",
                "mod.maxbogomol.wizards_reborn.common.block.arcane_iterator.ArcaneIteratorBlock",
                "mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlock"
        ), null));
        // CrystalBlock uses a special key prefix for recipe lookup
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "wizards_reborn", ModType.WIZARDS_REBORN, RSIntegrationConfig.ENABLE_WIZARDS_REBORN, List.of(
                "mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlock"
        ), "crystal_ritual"));

        // Container-to-RS transfer
        if (RSIntegrationConfig.ENABLE_CONTAINER_TRANSFER.get()) {
            com.huanghuang.rsintegration.transfer.ContainerTransferNetworkHandler.register();
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.transfer.ContainerTransferClient::init);
        }

        // RS Side Panel
        if (RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.sidepanel.RSSidePanelModule::initClient);
            com.huanghuang.rsintegration.sidepanel.RSSidePanelModule.initCommon();
        }

        // Sophisticated Backpacks
        if (enabled(RSIntegrationConfig.ENABLE_SOPHISTICATED_BACKPACKS, "sophisticatedbackpacks")) {
            SophisticatedBackpacksItems.init(FMLJavaModLoadingContext.get().getModEventBus());
        }

        // Goety
        if (enabled(RSIntegrationConfig.ENABLE_GOETY, "goety")) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT, () ->
                    com.huanghuang.rsintegration.module.goety.GoetyRSModule::initClient);
            com.huanghuang.rsintegration.module.goety.GoetyRSModule.initCommon();
        }

        // Wizards Reborn
        if (enabled(RSIntegrationConfig.ENABLE_WIZARDS_REBORN, "wizards_reborn")) {
            com.huanghuang.rsintegration.module.wizards_reborn.WizardsRebornRSModule.initCommon();
        }

        // Malum
        if (enabled(RSIntegrationConfig.ENABLE_MALUM, "malum")) {
            com.huanghuang.rsintegration.module.malum.MalumNetworkHandler.register();
        }

        // Forbidden & Arcanus
        if (enabled(RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS, "forbidden_arcanus")) {
            com.huanghuang.rsintegration.module.forbidden.FaNetworkHandler.register();
        }

        // Eidolon Repraised
        if (enabled(RSIntegrationConfig.ENABLE_EIDOLON, "eidolon")) {
            com.huanghuang.rsintegration.module.eidolon.EidolonNetworkHandler.register();
        }

        // Batch crafting (always registered — delegates validate mod availability internally)
        BatchCraftNetworkHandler.register();
        MinecraftForge.EVENT_BUS.addListener(BatchCraftManager.getInstance()::onServerTick);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer sp) {
                BatchCraftManager.getInstance().cancelAllForPlayer(sp.getUUID());
            }
        });

        // Async craft chains (multi-block recursive crafting)
        MinecraftForge.EVENT_BUS.register(com.huanghuang.rsintegration.crafting.AsyncCraftManager.getInstance());
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer sp) {
                com.huanghuang.rsintegration.crafting.AsyncCraftManager.getInstance().cancelAllForPlayer(sp.getUUID());
            }
        });

        LOGGER.info("{} initialized.", MOD_NAME);
    }

    private static boolean enabled(ForgeConfigSpec.BooleanValue config, String modId) {
        if (!config.get()) {
            LOGGER.debug("{} integration disabled by config.", modId);
            return false;
        }
        if (!ModList.get().isLoaded(modId)) {
            LOGGER.debug("{} mod not loaded, skipping integration.", modId);
            return false;
        }
        return true;
    }

    @SuppressWarnings("removal")
    private static void registerDisplayTest() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(
                        () -> NetworkConstants.IGNORESERVERONLY,
                        (remoteVersion, isServer) -> true
                ));
    }
}
