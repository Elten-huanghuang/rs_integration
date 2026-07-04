package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.backpack.SophisticatedBackpacksItems;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.aether.AetherRSModule;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksRSModule;
import com.huanghuang.rsintegration.mods.aetherworks.client.AetherworksClientSetup;
import com.huanghuang.rsintegration.mods.avaritia.AvaritiaRSModule;
import com.huanghuang.rsintegration.mods.confluence.ConfluenceRSModule;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotRSModule;
import com.huanghuang.rsintegration.mods.eidolon.EidolonRSModule;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyRSModule;
import com.huanghuang.rsintegration.mods.farmingforblockheads.FarmingForBlockheadsRSModule;
import com.huanghuang.rsintegration.mods.forbidden.FaRSModule;
import com.huanghuang.rsintegration.mods.goety.GoetyRSModule;
import com.huanghuang.rsintegration.mods.immortalers_delight.ImmortalersDelightRSModule;
import com.huanghuang.rsintegration.mods.malum.MalumRSModule;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRSModule;
import com.huanghuang.rsintegration.mods.tacz.TaczRSModule;
import com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmRSModule;
import com.huanghuang.rsintegration.mods.wizards_reborn.WizardsRebornRSModule;
import com.huanghuang.rsintegration.network.AltarBinding;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.BindingEventHandler;
import com.huanghuang.rsintegration.network.BindingTooltipHandler;
import com.huanghuang.rsintegration.network.RSBindingHook;
import com.huanghuang.rsintegration.network.RemoteGuiAuth;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelClient;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelModule;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.transfer.ContainerTransferClient;
import com.huanghuang.rsintegration.transfer.ContainerTransferNetworkHandler;
import com.huanghuang.rsintegration.util.ModIds;
import com.refinedmods.refinedstorage.api.network.INetwork;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(RSIntegrationMod.MOD_ID)
public final class RSIntegrationMod {

    public static final String MOD_ID = "rs_integration";
    public static final String MOD_NAME = "RS Integration";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);
    public static final int[] RS_FLOW_COLORS = {0x3355FF, 0x7733FF, 0xCC33FF, 0x3355FF};

    public static final IEventBus MOD_BUS =
            FMLJavaModLoadingContext.get().getModEventBus();

    static {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () ->
                MOD_BUS.addListener(AetherworksClientSetup::onRegisterOverlays));
    }

    private record ModuleEntry(String modId, ForgeConfigSpec.BooleanValue configFlag,
                               Supplier<IModIntegration> supplier) {}

    private static final List<ModuleEntry> MODULES = List.of(
            new ModuleEntry(ModIds.GOETY, RSIntegrationConfig.ENABLE_GOETY,
                    () -> GoetyRSModule.INSTANCE),
            new ModuleEntry(ModIds.MALUM, RSIntegrationConfig.ENABLE_MALUM,
                    () -> MalumRSModule.INSTANCE),
            new ModuleEntry(ModIds.EIDOLON, RSIntegrationConfig.ENABLE_EIDOLON,
                    () -> EidolonRSModule.INSTANCE),
            new ModuleEntry(ModIds.FORBIDDEN_ARCANUS, RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS,
                    () -> FaRSModule.INSTANCE),
            new ModuleEntry(ModIds.WIZARDS_REBORN, RSIntegrationConfig.ENABLE_WIZARDS_REBORN,
                    () -> WizardsRebornRSModule.INSTANCE),
            new ModuleEntry(ModIds.TOUHOU_LITTLE_MAID, RSIntegrationConfig.ENABLE_TOUHOU_LITTLE_MAID,
                    () -> TlmRSModule.INSTANCE),
            new ModuleEntry(ModIds.EMBERS, RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY,
                    () -> EreAlchemyRSModule.INSTANCE),
            new ModuleEntry(ModIds.AETHERWORKS, RSIntegrationConfig.ENABLE_AETHERWORKS,
                    () -> AetherworksRSModule.INSTANCE),
            new ModuleEntry(ModIds.AETHER, RSIntegrationConfig.ENABLE_AETHER,
                    () -> AetherRSModule.INSTANCE),
            new ModuleEntry(ModIds.CROCKPOT, RSIntegrationConfig.ENABLE_CROCKPOT,
                    () -> CrockPotRSModule.INSTANCE),
            new ModuleEntry(ModIds.TACZ, RSIntegrationConfig.ENABLE_TACZ,
                    () -> TaczRSModule.INSTANCE),
            new ModuleEntry(ModIds.SLASHBLADE, RSIntegrationConfig.ENABLE_SLASHBLADE,
                    () -> SlashBladeRSModule.INSTANCE),
            new ModuleEntry(ModIds.AVARITIA, RSIntegrationConfig.ENABLE_AVARITIA,
                    () -> AvaritiaRSModule.INSTANCE),
            new ModuleEntry(ModIds.CONFLUENCE, RSIntegrationConfig.ENABLE_CONFLUENCE,
                    () -> ConfluenceRSModule.INSTANCE),
            new ModuleEntry(ModIds.IMMORTERS_DELIGHT, RSIntegrationConfig.ENABLE_IMMORTERS_DELIGHT,
                    () -> ImmortalersDelightRSModule.INSTANCE)
    );

    public RSIntegrationMod() {
        registerDisplayTest();
        RSIntegrationConfig.register();
        AltarBindingRegistry.registerHook(AltarBinding.RS_NETWORK, RSBindingHook.INSTANCE);
        ForgeChunkManager.setForcedChunkLoadingCallback(
                MOD_ID, (level, ticketHelper) -> {});
        if (enabled(RSIntegrationConfig.ENABLE_SOPHISTICATED_BACKPACKS, ModIds.SOPHISTICATED_BACKPACKS)) {
            SophisticatedBackpacksItems.init(MOD_BUS);
        }

        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> ContainerTransferClient::registerKeyMappings);
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> RSSidePanelClient::registerKeyMappings);

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        for (ModuleEntry entry : MODULES) {
            if (!entry.configFlag().get() || !ModList.get().isLoaded(entry.modId())) continue;
            IModIntegration module = entry.supplier().get();
            module.registerModType();
            module.registerBindingTargets();
            module.registerRecipeHandler();
            module.registerNetworkPackets();
            module.initCommon();
            DistExecutor.safeRunWhenOn(Dist.CLIENT, module.clientInitSupplier());
        }

        // --- FarmingForBlockheads Market (virtual exchange, no IModIntegration) ---
        if (enabled(RSIntegrationConfig.ENABLE_FARMINGFORBLOCKHEADS, ModIds.FARMINGFORBLOCKHEADS))
            FarmingForBlockheadsRSModule.initCommon();

        // --- Confluence Workshop — binding target registered by the module;
        //     this fallback ensures it works even when the full module is disabled ---
        if (ModList.get().isLoaded("confluence")) {
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "confluence", ModType.byId("confluence"),
                            RSIntegrationConfig.ENABLE_CONFLUENCE,
                            List.of("org.confluence.mod.block.WorkshopBlock"),
                            "confluence", true
                    ));
        }

        // --- Apotheosis Reforging Table ---
        if (ModList.get().isLoaded("apotheosis")) {
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "apotheosis", ModType.byId("custom_gui"),
                            RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS,
                            List.of("dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingTableBlock"),
                            "apotheosis"
                    ));
        }

        // --- CrabbersDelight Crab Trap (loot-table driven, needs dedicated delegate) ---
        if (ModList.get().isLoaded("crabbersdelight")) {
            ModType.register("crabbersdelight",
                    new String[]{
                            "com.huanghuang.rsintegration.mods.crabbersdelight.CrabTrapLootWrapper"
                    },
                    new String[]{"crab_trap", "crabbersdelight"},
                    new String[]{"crabbersdelight"},
                    ModType.delegateSupplier("com.huanghuang.rsintegration.mods.crabbersdelight.CrabTrapBatchDelegate"));
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "crabbersdelight", ModType.byId("crabbersdelight"),
                            RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS,
                            List.of("alabaster.crabbersdelight.common.block.CrabTrapBlock"),
                            "crabbersdelight", true
                    ));
        }

        // --- Vanilla Machines (built-in, not IModIntegration) ----------
        if (RSIntegrationConfig.ENABLE_VANILLA_MACHINES.get()) {
            ModType.register("vanilla_machine",
                    new String[]{
                            "net.minecraft.world.item.crafting.SmeltingRecipe",
                            "net.minecraft.world.item.crafting.BlastingRecipe",
                            "net.minecraft.world.item.crafting.SmokingRecipe",
                            "net.minecraft.world.item.crafting.CampfireCookingRecipe",
                            "net.minecraft.world.item.crafting.StonecutterRecipe"
                    },
                    new String[]{"furnace", "smoker", "stonecutter"},
                    new String[]{"vanilla_machine"},
                    ModType.delegateSupplier("com.huanghuang.rsintegration.mods.vanilla.VanillaMachineBatchDelegate"));
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "minecraft", ModType.byId("vanilla_machine"),
                            RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                            List.of(
                                    "net.minecraft.world.level.block.FurnaceBlock",
                                    "net.minecraft.world.level.block.BlastFurnaceBlock",
                                    "net.minecraft.world.level.block.SmokerBlock",
                                    "net.minecraft.world.level.block.StonecutterBlock",
                                    "net.minecraft.world.level.block.AnvilBlock",
                                    "net.minecraft.world.level.block.EnchantmentTableBlock"
                            ),
                            "vanilla_machine"
                    ));

            // Smithing table -> separate ModType so it shows as smithing, not furnace
            ModType.register("smithing",
                    new String[]{
                            "net.minecraft.world.item.crafting.SmithingTransformRecipe",
                            "net.minecraft.world.item.crafting.SmithingTrimRecipe"
                    },
                    new String[]{"smithing_table"},
                    new String[]{"smithing"},
                    ModType.delegateSupplier("com.huanghuang.rsintegration.mods.vanilla.VanillaMachineBatchDelegate"));
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "minecraft", ModType.byId("smithing"),
                            RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                            List.of(
                                    "net.minecraft.world.level.block.SmithingTableBlock"
                            ),
                            "smithing"
                    ));
        }

        // Subsystems
        if (RSIntegrationConfig.ENABLE_CONTAINER_TRANSFER.get()) {
            ContainerTransferNetworkHandler.register();
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> ContainerTransferClient::init);
        }
        if (RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> RSSidePanelModule::initClient);
            RSSidePanelModule.initCommon();
        }

        // Binding tooltip handler
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> () -> MinecraftForge.EVENT_BUS.register(BindingTooltipHandler.class));
        // Crafting
        BatchCraftNetworkHandler.register();

        // Altar binding registry (BINDINGS cache + scan caches)
        MinecraftForge.EVENT_BUS.register(AltarBindingRegistry.class);

        // Async craft chains
        MinecraftForge.EVENT_BUS.register(AsyncCraftManager.getInstance());
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp)
                AsyncCraftManager.getInstance().cancelAllForPlayer(sp.getUUID());
        });

        // Cross-dimension: unpin the old dimension's IStorageCache listener
        // and re-register against the new dimension's network.
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
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
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            AsyncCraftManager.abortAll();
        });

        // Chunk unload safety net: force-close remote GUI whose machine
        // chunk is being unloaded. Primary prevention is ForgeChunkManager
        // force-loading in RemoteGuiAuth.authorize(); this catches edge cases
        // (e.g. another mod force-unloading the chunk).
        MinecraftForge.EVENT_BUS.addListener(RemoteGuiAuth::onChunkUnload);

        LOGGER.info("{} initialized.", MOD_NAME);
    }

    private static boolean enabled(ForgeConfigSpec.BooleanValue config, String modId) {
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
