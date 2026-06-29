package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.network.AltarBinding;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSBindingHook;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.refinedmods.refinedstorage.api.network.INetwork;

import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStoppingEvent;
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
        if (enabled(RSIntegrationConfig.ENABLE_GOETY, ModIds.GOETY)) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.mods.goety.GoetyRSModule::initClient);
            com.huanghuang.rsintegration.mods.goety.GoetyRSModule.initCommon();
        }
        if (enabled(RSIntegrationConfig.ENABLE_WIZARDS_REBORN, ModIds.WIZARDS_REBORN)) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.mods.wizards_reborn.WizardsRebornRSModule::initClient);
            com.huanghuang.rsintegration.mods.wizards_reborn.WizardsRebornRSModule.initCommon();
        }
        if (enabled(RSIntegrationConfig.ENABLE_MALUM, ModIds.MALUM))
            com.huanghuang.rsintegration.mods.malum.MalumRSModule.initCommon();
        if (enabled(RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS, ModIds.FORBIDDEN_ARCANUS))
            com.huanghuang.rsintegration.mods.forbidden.FARSModule.initCommon();
        if (enabled(RSIntegrationConfig.ENABLE_EIDOLON, ModIds.EIDOLON))
            com.huanghuang.rsintegration.mods.eidolon.EidolonRSModule.initCommon();

        // --- Touhou Little Maid -------------------------------------------
        if (enabled(RSIntegrationConfig.ENABLE_TOUHOU_LITTLE_MAID, ModIds.TOUHOU_LITTLE_MAID))
            com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmRSModule.initCommon();

        // --- Embers Rekindled ------------------------------------------
        if (enabled(RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY, ModIds.EMBERS))
            com.huanghuang.rsintegration.mods.embers.EreAlchemyRSModule.initCommon();

        // --- Aetherworks (Embers addon) ---------------------------------
        if (enabled(RSIntegrationConfig.ENABLE_AETHERWORKS, ModIds.AETHERWORKS)) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.huanghuang.rsintegration.mods.aetherworks.client.AetherworksClientSetup
                            .init(FMLJavaModLoadingContext.get().getModEventBus()));
            com.huanghuang.rsintegration.mods.aetherworks.AetherworksRSModule.initCommon();
        }

        // --- Vanilla Machines ------------------------------------------
        if (RSIntegrationConfig.ENABLE_VANILLA_MACHINES.get()) {
            com.huanghuang.rsintegration.network.BindingEventHandler.registerTarget(
                    new com.huanghuang.rsintegration.network.BindingEventHandler.MachineBindingTarget(
                            "minecraft", ModType.VANILLA_MACHINE,
                            RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                            List.of(
                                    "net.minecraft.world.level.block.FurnaceBlock",
                                    "net.minecraft.world.level.block.BlastFurnaceBlock",
                                    "net.minecraft.world.level.block.SmokerBlock",
                                    "net.minecraft.world.level.block.CampfireBlock",
                                    "net.minecraft.world.level.block.StonecutterBlock",
                                    "net.minecraft.world.level.block.SmithingTableBlock"
                            ),
                            "vanilla_machine"
                    ));
        }

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
        if (RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get()) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.machine.MachineHubClient::init);
        }
        if (enabled(RSIntegrationConfig.ENABLE_SOPHISTICATED_BACKPACKS, ModIds.SOPHISTICATED_BACKPACKS)) {
            com.huanghuang.rsintegration.backpack.SophisticatedBackpacksItems
                    .init(FMLJavaModLoadingContext.get().getModEventBus());
        }

        // Crafting
        BatchCraftNetworkHandler.register();

        // Async craft chains
        MinecraftForge.EVENT_BUS.register(com.huanghuang.rsintegration.crafting.AsyncCraftManager.getInstance());
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer sp)
                com.huanghuang.rsintegration.crafting.AsyncCraftManager.getInstance().cancelAllForPlayer(sp.getUUID());
        });

        // Cross-dimension: unpin the old dimension's IStorageCache listener
        // and re-register against the new dimension's network.
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer sp) {
                if (RSSidePanelNetworkHandler.hasListener(sp.getUUID())) {
                    RSSidePanelNetworkHandler.unregisterListener(sp.getUUID());
                    INetwork network = RSIntegration.resolveNetworkFromPlayer(sp);
                    if (network != null) {
                        RSSidePanelNetworkHandler.registerListener(sp, network);
                    }
                }
            }
        });

        // Server shutdown: abort all active async craft chains so committed
        // materials are refunded rather than silently lost.
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            com.huanghuang.rsintegration.crafting.AsyncCraftManager.abortAll();
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
