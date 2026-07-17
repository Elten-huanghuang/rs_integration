package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.mods.sophisticatedbackpacks.SophisticatedBackpacksItems;
import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.sidepanel.client.RSIKeyBindings;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.CraftProgressClientEvents;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.aether.AetherRSModule;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisRSModule;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksRSModule;
import com.huanghuang.rsintegration.mods.aetherworks.client.AetherworksClientSetup;
import com.huanghuang.rsintegration.mods.avaritia.AvaritiaRSModule;
import com.huanghuang.rsintegration.mods.confluence.ConfluenceRSModule;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotRSModule;
import com.huanghuang.rsintegration.mods.eidolon.EidolonRSModule;
import com.huanghuang.rsintegration.mods.farmersdelight.FarmersDelightRSModule;
import com.huanghuang.rsintegration.mods.farmersrespite.FarmersRespiteRSModule;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyRSModule;
import com.huanghuang.rsintegration.mods.farmingforblockheads.FarmingForBlockheadsRSModule;
import com.huanghuang.rsintegration.mods.forbidden.FaRSModule;
import com.huanghuang.rsintegration.mods.goety.GoetyRSModule;
import com.huanghuang.rsintegration.mods.immortalersdelight.ImmortalersDelightRSModule;
import com.huanghuang.rsintegration.mods.ironfurnaces.IronFurnacesRSModule;
import com.huanghuang.rsintegration.mods.malum.MalumRSModule;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRSModule;
import com.huanghuang.rsintegration.mods.tacz.TaczRSModule;
import com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmRSModule;
import com.huanghuang.rsintegration.mods.wizardsreborn.WizardsRebornRSModule;
import com.huanghuang.rsintegration.mods.youkaishomecoming.YoukaisHomecomingRSModule;
import com.huanghuang.rsintegration.network.binding.AltarBinding;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.binding.BindingTooltipHandler;
import com.huanghuang.rsintegration.network.binding.RSBindingHook;
import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import com.huanghuang.rsintegration.autoeat.network.AutoEatNetworkHandler;
import com.huanghuang.rsintegration.network.packet.ConfigSyncPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.ResonanceNetworkHandler;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelClient;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelModule;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.huanghuang.rsintegration.transfer.ContainerTransferClient;
import com.huanghuang.rsintegration.transfer.ContainerTransferNetworkHandler;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskFactory;
import com.huanghuang.rsintegration.resonance.disk.ResonanceDiskWrapper;
import com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine;
import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.apiimpl.API;

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
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkConstants;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(RSIntegrationMod.MOD_ID)
public final class RSIntegrationMod {

    public static final String MOD_ID = "rs_integration";
    public static final String MOD_NAME = "RS Integration";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);
    public static final int[] RS_FLOW_COLORS = {0x3355FF, 0x7733FF, 0xCC33FF, 0x3355FF};

    // Cached boolean — avoids per-tick ConfigValue.get() Map lookup + sync overhead
    private static boolean verboseLogging;

    public static void refreshConfigCache() {
        verboseLogging = RSIntegrationConfig.DIAGNOSTIC_VERBOSE_LOGGING.get();
    }

    /** Guarded debug — only emits when diagnostic verbose logging is enabled in config. */
    public static void debug(String format, Object... args) {
        if (verboseLogging) {
            LOGGER.debug(format, args);
        }
    }

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
            new ModuleEntry(ModIds.APOTHEOSIS, RSIntegrationConfig.ENABLE_APOTHEOSIS,
                    () -> ApotheosisRSModule.INSTANCE),
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
                    () -> ImmortalersDelightRSModule.INSTANCE),
            new ModuleEntry(ModIds.FARMERSDELIGHT, RSIntegrationConfig.ENABLE_FARMERSDELIGHT,
                    () -> FarmersDelightRSModule.INSTANCE),
            new ModuleEntry(ModIds.YOUKAISHOMECOMING, RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING,
                    () -> YoukaisHomecomingRSModule.INSTANCE),
            new ModuleEntry(ModIds.FARMERSRESPITE, RSIntegrationConfig.ENABLE_FARMERSRESPITE,
                    () -> FarmersRespiteRSModule.INSTANCE),
            new ModuleEntry(ModIds.IRON_FURNACES, RSIntegrationConfig.ENABLE_IRON_FURNACES,
                    () -> IronFurnacesRSModule.INSTANCE)
    );

    public RSIntegrationMod() {
        registerDisplayTest();
        RSIntegrationConfig.register();
        refreshConfigCache();
        MOD_BUS.addListener((ModConfigEvent.Reloading e) -> {
            refreshConfigCache();
            com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge.refreshEnabled();
        });
        AltarBindingRegistry.registerHook(AltarBinding.RS_NETWORK, RSBindingHook.INSTANCE);
        ForgeChunkManager.setForcedChunkLoadingCallback(
                MOD_ID, (level, ticketHelper) -> {});
        if (enabled(RSIntegrationConfig.ENABLE_SOPHISTICATED_BACKPACKS, ModIds.SOPHISTICATED_BACKPACKS)) {
            SophisticatedBackpacksItems.init(MOD_BUS);
        }
        ModItems.init(MOD_BUS);

        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> ContainerTransferClient::registerKeyMappings);
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> RSSidePanelClient::registerKeyMappings);
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> RSIKeyBindings::registerKeyMappings);
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> () -> {
                    com.huanghuang.rsintegration.crafting.CraftProgressKeybind.register();
                    MinecraftForge.EVENT_BUS.register(com.huanghuang.rsintegration.crafting.CraftProgressOverlay.class);
                });
        MOD_BUS.addListener(this::onClientSetup);

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(
                com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge.class);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge.initialize();
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
            String delegateClass = "com.huanghuang.rsintegration.mods.vanilla.VanillaMachineBatchDelegate";
            String cookingDelegateClass = "com.huanghuang.rsintegration.mods.vanilla.CookingMachineBatchDelegate";

            // ── Furnace ────────────────────────────────────────────
            ModType.register("vanilla_furnace",
                    new String[]{
                            "net.minecraft.world.item.crafting.SmeltingRecipe",
                            "cech12.brickfurnace.crafting.BrickSmeltingRecipe"
                    },
                    new String[]{"furnace"},
                    new String[]{"vanilla_furnace"},
                    ModType.delegateSupplier(cookingDelegateClass));
            ModType.configureJei("vanilla_furnace",
                    new String[][]{
                            {"minecraft:smelting", "vanilla_furnace"},
                            {"brickfurnace:smelting", "vanilla_furnace"}
                    },
                    new String[][]{
                            {"net.minecraft.world.item.crafting.SmeltingRecipe", "vanilla_furnace"},
                            {"cech12.brickfurnace.crafting.BrickSmeltingRecipe", "vanilla_furnace"}
                    },
                    "gui.rs_integration.jei.vanilla_furnace_craft");
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "minecraft", ModType.byId("vanilla_furnace"),
                            RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                            List.of("net.minecraft.world.level.block.FurnaceBlock"),
                            "vanilla_furnace"));

            // ── Blast Furnace ──────────────────────────────────────
            ModType.register("vanilla_blast_furnace",
                    new String[]{
                            "net.minecraft.world.item.crafting.BlastingRecipe",
                            "cech12.brickfurnace.crafting.BrickBlastingRecipe"
                    },
                    new String[]{"blast_furnace"},
                    new String[]{"vanilla_blast_furnace"},
                    ModType.delegateSupplier(cookingDelegateClass));
            ModType.configureJei("vanilla_blast_furnace",
                    new String[][]{
                            {"minecraft:blasting", "vanilla_blast_furnace"},
                            {"brickfurnace:blasting", "vanilla_blast_furnace"}
                    },
                    new String[][]{
                            {"net.minecraft.world.item.crafting.BlastingRecipe", "vanilla_blast_furnace"},
                            {"cech12.brickfurnace.crafting.BrickBlastingRecipe", "vanilla_blast_furnace"}
                    },
                    "gui.rs_integration.jei.vanilla_blast_furnace_craft");
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "minecraft", ModType.byId("vanilla_blast_furnace"),
                            RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                            List.of("net.minecraft.world.level.block.BlastFurnaceBlock"),
                            "vanilla_blast_furnace"));

            // ── Smoker ─────────────────────────────────────────────
            ModType.register("vanilla_smoker",
                    new String[]{
                            "net.minecraft.world.item.crafting.SmokingRecipe",
                            "cech12.brickfurnace.crafting.BrickSmokingRecipe"
                    },
                    new String[]{"smoker"},
                    new String[]{"vanilla_smoker"},
                    ModType.delegateSupplier(cookingDelegateClass));
            ModType.configureJei("vanilla_smoker",
                    new String[][]{
                            {"minecraft:smoking", "vanilla_smoker"},
                            {"brickfurnace:smoking", "vanilla_smoker"}
                    },
                    new String[][]{
                            {"net.minecraft.world.item.crafting.SmokingRecipe", "vanilla_smoker"},
                            {"cech12.brickfurnace.crafting.BrickSmokingRecipe", "vanilla_smoker"}
                    },
                    "gui.rs_integration.jei.vanilla_smoker_craft");
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "minecraft", ModType.byId("vanilla_smoker"),
                            RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                            List.of("net.minecraft.world.level.block.SmokerBlock"),
                            "vanilla_smoker"));

            // ── Campfire (no GUI) ──────────────────────────────────
            // When FD is loaded, farmersdelight_skillet handles all
            // CampfireCookingRecipe classification and campfire binding.
            // Avoid registering vanilla_campfire at all — if both it and
            // farmersdelight_skillet are registered with the same recipe
            // prefix, classifyRecipe's >= tiebreaker picks whichever was
            // registered last (vanilla, since modules run first in
            // onCommonSetup).  That would route campfire recipes to
            // vanilla_campfire, whose bindings don't exist (campfires are
            // bound under farmersdelight_skillet by FD's module).
            if (!ModList.get().isLoaded("farmersdelight")) {
                ModType.register("vanilla_campfire",
                        new String[]{"net.minecraft.world.item.crafting.CampfireCookingRecipe"},
                        new String[]{"campfire"},
                        new String[]{"vanilla_campfire"},
                        ModType.delegateSupplier(delegateClass));
                ModType.configureJei("vanilla_campfire",
                        new String[][]{{"minecraft:campfire_cooking", "vanilla_campfire"}},
                        new String[][]{{"net.minecraft.world.item.crafting.CampfireCookingRecipe", "vanilla_campfire"}},
                        "gui.rs_integration.jei.vanilla_campfire_craft");
                BindingEventHandler.registerTarget(
                        new BindingEventHandler.MachineBindingTarget(
                                "minecraft", ModType.byId("vanilla_campfire"),
                                RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                                List.of("net.minecraft.world.level.block.CampfireBlock"),
                                "vanilla_campfire", false));
            }

            // ── Stonecutter ────────────────────────────────────────
            ModType.register("vanilla_stonecutter",
                    new String[]{"net.minecraft.world.item.crafting.StonecutterRecipe"},
                    new String[]{"stonecutter"},
                    new String[]{"vanilla_stonecutter"},
                    ModType.delegateSupplier(delegateClass));
            ModType.configureJei("vanilla_stonecutter",
                    new String[][]{{"minecraft:stonecutting", "vanilla_stonecutter"}},
                    new String[][]{{"net.minecraft.world.item.crafting.StonecutterRecipe", "vanilla_stonecutter"}},
                    "gui.rs_integration.jei.vanilla_stonecutter_craft");
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "minecraft", ModType.byId("vanilla_stonecutter"),
                            RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                            List.of("net.minecraft.world.level.block.StonecutterBlock"),
                            "vanilla_stonecutter"));

            // ── Anvil (JEI-only, opens remote GUI) ─────────────────
            ModType.register("vanilla_anvil",
                    new String[0],
                    new String[]{"anvil"},
                    new String[]{"vanilla_anvil"},
                    ModType.delegateSupplier(delegateClass));
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "minecraft", ModType.byId("vanilla_anvil"),
                            RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                            List.of("net.minecraft.world.level.block.AnvilBlock"),
                            "vanilla_anvil"));

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

        // /reload clears recipe output caches so new datapack recipes take effect
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.AddReloadListenerEvent e) -> {
            com.huanghuang.rsintegration.recipe.ModRecipeHandlers.clearResultCaches();
            com.huanghuang.rsintegration.crafting.CraftPlanningRevision.bump();
        });

        // Async craft chains
        MinecraftForge.EVENT_BUS.register(AsyncCraftManager.getInstance());
        // Sync server config to client on login
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                        new ConfigSyncPacket(
                                RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get(),
                                RSIntegrationConfig.MACHINE_TAB_THRESHOLD.get()));
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                AsyncCraftManager.getInstance().cancelAllForPlayer(sp.getUUID());
                com.huanghuang.rsintegration.autoeat.AutoEatRateLimiter.onPlayerLogout(sp.getUUID());
            }
        });

        // Cross-dimension: unpin the old dimension's IStorageCache listener
        // and re-register against the new dimension's network.
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                if (RSSidePanelNetworkHandler.hasListener(sp.getUUID())) {
                    RSSidePanelNetworkHandler.unregisterListener(sp.getUUID());
                    INetwork network = RSIntegrationNetwork.resolveNetworkFromPlayer(sp);
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

        // Auto-eat system
        AutoEatNetworkHandler.register();
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () ->
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                        com.huanghuang.rsintegration.autoeat.client.AutoEatClientEvents.class));
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () -> {
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(
                    com.huanghuang.rsintegration.compat.ftbquests.client.FtbQuestJeiRuntime.class);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    CraftProgressClientEvents::onClientLogin);
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                    CraftProgressClientEvents::onClientLogout);
        });
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () ->
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                        (net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) ->
                                com.huanghuang.rsintegration.mods.goety.RSClientAvailabilityCache.clear()));
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () ->
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                        (net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) ->
                                com.huanghuang.rsintegration.sidepanel.RSSidePanelClient.clearOnLogout()));

        // Resonance disk factory — register with RS storage disk registry
        if (RSIntegrationConfig.ENABLE_RS_PASSIVE_EFFECTS.get()) {
            ResonanceNetworkHandler.register();
            API.instance().getStorageDiskRegistry().add(
                    ResonanceDiskWrapper.FACTORY_ID, new ResonanceDiskFactory());
            MinecraftForge.EVENT_BUS.register(PassiveEffectEngine.class);
        }

        ContractValidation.validateAll();

        LOGGER.info("{} initialized.", MOD_NAME);
    }

    private void onClientSetup(final net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        net.minecraft.client.gui.screens.MenuScreens.register(
                ModItems.RESONANCE_BACKPACK.get(),
                com.huanghuang.rsintegration.resonance.backpack.ResonanceBackpackScreen::new);
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
