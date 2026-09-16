package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.mods.sophisticatedbackpacks.SophisticatedBackpacksItems;
import com.huanghuang.rsintegration.reflection.contract.ContractValidation;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.mods.aether.AetherRSModule;
import com.huanghuang.rsintegration.mods.apotheosis.ApotheosisRSModule;
import com.huanghuang.rsintegration.mods.aetherworks.AetherworksRSModule;
import com.huanghuang.rsintegration.mods.arsnouveau.ArsNouveauRSModule;
import com.huanghuang.rsintegration.mods.avaritia.AvaritiaRSModule;
import com.huanghuang.rsintegration.mods.confluence.ConfluenceRSModule;
import com.huanghuang.rsintegration.mods.crockpot.CrockPotRSModule;
import com.huanghuang.rsintegration.mods.distantworlds.DistantWorldsRSModule;
import com.huanghuang.rsintegration.mods.eidolon.EidolonRSModule;
import com.huanghuang.rsintegration.mods.farmersdelight.FarmersDelightRSModule;
import com.huanghuang.rsintegration.mods.farmersrespite.FarmersRespiteRSModule;
import com.huanghuang.rsintegration.mods.embers.EreAlchemyRSModule;
import com.huanghuang.rsintegration.mods.farmingforblockheads.FarmingForBlockheadsRSModule;
import com.huanghuang.rsintegration.mods.forbidden.FaRSModule;
import com.huanghuang.rsintegration.mods.goety.GoetyRSModule;
import com.huanghuang.rsintegration.mods.immortalersdelight.ImmortalersDelightRSModule;
import com.huanghuang.rsintegration.mods.ironfurnaces.IronFurnacesRSModule;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRSModule;
import com.huanghuang.rsintegration.mods.apprenticecodex.ApprenticeCodexRSModule;
import com.huanghuang.rsintegration.mods.isscsw.IssCswRSModule;
import com.huanghuang.rsintegration.mods.malum.MalumRSModule;
import com.huanghuang.rsintegration.mods.lychee.LycheeRSModule;
import com.huanghuang.rsintegration.mods.pmmo.PmmoRSModule;
import com.huanghuang.rsintegration.mods.botania.BotaniaRSModule;
import com.huanghuang.rsintegration.mods.slashblade.SlashBladeRSModule;
import com.huanghuang.rsintegration.mods.tacz.TaczRSModule;
import com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmRSModule;
import com.huanghuang.rsintegration.mods.wizardsreborn.WizardsRebornRSModule;
import com.huanghuang.rsintegration.mods.youkaishomecoming.YoukaisHomecomingRSModule;
import com.huanghuang.rsintegration.mods.wishingfountain.WishingFountainRSModule;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.gui.RemoteGuiAuth;
import com.huanghuang.rsintegration.autoeat.network.AutoEatNetworkHandler;
import com.huanghuang.rsintegration.network.packet.ConfigSyncPacket;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.huanghuang.rsintegration.network.packet.ResonanceNetworkHandler;
import com.huanghuang.rsintegration.storage.StorageBackendDescriptors;
import com.huanghuang.rsintegration.storage.StorageBackendLoadResult;
import com.huanghuang.rsintegration.storage.StorageBackendRuntime;
import com.huanghuang.rsintegration.transfer.ContainerTransferClient;
import com.huanghuang.rsintegration.transfer.ContainerTransferNetworkHandler;
import com.huanghuang.rsintegration.util.ModIds;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.PacketDistributor;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.config.Configurator;

@Mod(RSIntegrationMod.MOD_ID)
public final class RSIntegrationMod {
    private static final Set<String> REVIEWED_GRAPH_EXECUTION_TYPES = Set.of(
            "aether", "aether_altar", "aether_freezer", "aether_incubator",
            "aetherworks_anvil", "aetherworks_tool_station",
            "apotheosis_fletching", "apotheosis_gem_cutting", "apotheosis_library",
            "ars_nouveau_apparatus", "ars_nouveau_imbuement", "ars_nouveau_scribes_table",
            "avaritia_crafting", "avaritia_gui", "avaritia_smithing",
            "botania_apothecary", "botania_brewery", "botania_elven_trade",
            "botania_mana_pool", "botania_pure_daisy", "botania_runic_altar",
            "botania_terra_plate", "mythicbotany_mana_infuser",
            "confluence", "crabbersdelight", "crockpot",
            "distant_worlds_lithum_altar", "eidolon", "eidolon_worktable",
            "farmersdelight", "farmersdelight_cooking_pot", "miners_delight_copper_pot",
            "farmersdelight_skillet", "farmersrespite", "farmersrespite_kettle",
            "forbidden_arcanus", "forbidden_arcanus_clibano", "goety", "goety_brazier",
            "goety_cursed_infuser", "immortalers_delight",
            "immortalers_delight_hot_spring", "ironfurnaces_blast_furnace",
            "ironfurnaces_furnace", "ironfurnaces_smoker", "lychee_item_inside_virtual",
            "lychee_block_interacting",
            "malum", "malum_runic_workbench", "malum_spirit_crucible",
            "malum_void_favor_virtual", "slashblade", "smithing", "tacz",
            "touhou_little_maid", "vanilla_anvil", "vanilla_blast_furnace",
            "vanilla_brewing_stand", "vanilla_campfire", "vanilla_furnace",
            "vanilla_smoker", "vanilla_stonecutter", "wizards_reborn",
            "irons_spellbooks_scroll_forge", "irons_spellbooks_arcane_anvil",
            "apprenticecodex_spellcaster_workbench",
            "iss_csw_spell_forge",
            "youkaishomecoming", "youkaishomecoming_cooking_large",
            "youkaishomecoming_cooking_short", "youkaishomecoming_cooking_small",
            "youkaishomecoming_cuisine", "youkaishomecoming_ferment",
            "youkaishomecoming_kettle", "youkaishomecoming_moka",
            "youkaishomecoming_steamer", "wishing_fountain");

    public static final String MOD_ID = "rs_integration";
    public static final String MOD_NAME = "RS Integration";
    public static final Logger LOGGER = LogManager.getLogger(MOD_NAME);
    public static final int[] RS_FLOW_COLORS = {0x3355FF, 0x7733FF, 0xCC33FF, 0x3355FF};
    public static final StorageBackendRuntime STORAGE_BACKENDS = new StorageBackendRuntime();

    // Cached to avoid a ConfigValue lookup on every diagnostic call.
    private static volatile boolean verboseLogging;

    public static void refreshConfigCache() {
        verboseLogging = RSIntegrationConfig.DIAGNOSTIC_VERBOSE_LOGGING.get();
        // Most legacy call sites use LOGGER.debug directly. Keep the logger
        // itself at INFO unless diagnostics are explicitly enabled so those
        // calls cannot flood debug.log while this migration is in progress.
        Configurator.setLevel(LOGGER.getName(), verboseLogging ? Level.DEBUG : Level.INFO);
    }

    /** Emits only when diagnostic verbose logging is enabled in config. */
    public static void debug(String format, Object... args) {
        if (verboseLogging) {
            LOGGER.debug(format, args);
        }
    }

    public static final IEventBus MOD_BUS =
            FMLJavaModLoadingContext.get().getModEventBus();

    private record ModuleEntry(String modId, ForgeConfigSpec.BooleanValue configFlag,
                               Supplier<IModIntegration> supplier) {}

    private static final List<ModuleEntry> MODULES = List.of(
            new ModuleEntry(ModIds.GOETY, RSIntegrationConfig.ENABLE_GOETY,
                    () -> GoetyRSModule.INSTANCE),
            new ModuleEntry(ModIds.MALUM, RSIntegrationConfig.ENABLE_MALUM,
                    () -> MalumRSModule.INSTANCE),
            new ModuleEntry(ModIds.BOTANIA, RSIntegrationConfig.ENABLE_BOTANIA,
                    () -> BotaniaRSModule.INSTANCE),
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
            new ModuleEntry(ModIds.ARS_NOUVEAU, RSIntegrationConfig.ENABLE_ARS_NOUVEAU,
                    () -> ArsNouveauRSModule.INSTANCE),
            new ModuleEntry(ModIds.APOTHEOSIS, RSIntegrationConfig.ENABLE_APOTHEOSIS,
                    () -> ApotheosisRSModule.INSTANCE),
            new ModuleEntry(ModIds.IRONS_SPELLBOOKS, RSIntegrationConfig.ENABLE_IRONS_SPELLBOOKS,
                    () -> IronSpellBooksRSModule.INSTANCE),
            new ModuleEntry(ModIds.APPRENTICE_CODEX, RSIntegrationConfig.ENABLE_APPRENTICE_CODEX,
                    () -> ApprenticeCodexRSModule.INSTANCE),
            new ModuleEntry(ModIds.ISS_CSW, RSIntegrationConfig.ENABLE_ISS_CSW,
                    () -> IssCswRSModule.INSTANCE),
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
                    () -> IronFurnacesRSModule.INSTANCE),
            new ModuleEntry(ModIds.DISTANT_WORLDS, RSIntegrationConfig.ENABLE_DISTANT_WORLDS,
                    () -> DistantWorldsRSModule.INSTANCE),
            new ModuleEntry(ModIds.LYCHEE, RSIntegrationConfig.ENABLE_LYCHEE,
                    () -> LycheeRSModule.INSTANCE),
            new ModuleEntry(ModIds.PMMO, RSIntegrationConfig.ENABLE_PMMO,
                    () -> PmmoRSModule.INSTANCE),
            new ModuleEntry(ModIds.WISHING_FOUNTAIN, RSIntegrationConfig.ENABLE_WISHING_FOUNTAIN,
                    () -> WishingFountainRSModule.INSTANCE)
    );

    public RSIntegrationMod() {
        RSIntegrationConfig.register();
        MOD_BUS.addListener((ModConfigEvent.Loading e) -> {
            if (e.getConfig().getType() == ModConfig.Type.COMMON) {
                refreshConfigCache();
            }
            if (e.getConfig().getType() == ModConfig.Type.SERVER) {
                migrateServerConfig(e.getConfig());
                com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket.reloadPlanningConfig();
            }
        });
        MOD_BUS.addListener((ModConfigEvent.Reloading e) -> {
            if (e.getConfig().getType() == ModConfig.Type.COMMON) {
                refreshConfigCache();
                if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
                    com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler
                            .onSidePanelConfigReload();
                }
            }
            if (e.getConfig().getType() == ModConfig.Type.SERVER) {
                com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket.reloadPlanningConfig();
            }
            com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge.refreshEnabled();
            broadcastConfigSync();
        });
        ForgeChunkManager.setForcedChunkLoadingCallback(
                MOD_ID, (level, ticketHelper) -> {});
        // Registry contents cannot depend on a config that is loaded after mod construction.
        if (ModList.get().isLoaded(ModIds.SOPHISTICATED_BACKPACKS)) {
            SophisticatedBackpacksItems.init(MOD_BUS);
        }
        ModItems.init(MOD_BUS);
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            RSOptionalBootstrap.registerItems(MOD_BUS);
            RSOptionalBootstrap.registerBindings();
        }
        if (ModList.get().isLoaded("beyonddimensions")) {
            ModItems.registerOptionalBeyondDimensions(MOD_BUS,
                    com.huanghuang.rsintegration.resonance.bd.BDResonanceDiskItem::new);
        }

        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                || ModList.get().isLoaded("beyonddimensions")) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> ContainerTransferClient::registerKeyMappings);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.client.ClientEventBootstrap::register);
        }
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> RSOptionalBootstrap::registerClientKeyMappings);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.mods.rs.recentsearch.RecentSearchClient::init);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> RSOptionalBootstrap::registerClientEventSubscribers);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.sidepanel.client.MachineFavoritesClient::init);
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.sidepanel.client.WorldPickClient::init);
        }
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                || ModList.get().isLoaded("beyonddimensions")) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.client.StorageClientBootstrap::register);
        }
        MOD_BUS.addListener(this::onClientSetup);

        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
        MinecraftForge.EVENT_BUS.register(
                com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge.class);
    }

    private static void migrateServerConfig(ModConfig config) {
        int schema = RSIntegrationConfig.SERVER_CONFIG_SCHEMA_VERSION.get();
        if (schema >= RSIntegrationConfig.SERVER_CONFIG_SCHEMA) return;

        int currentMax = RSIntegrationConfig.REPEAT_COUNT_MAX.get();
        int migratedMax = RSIntegrationConfig.migrateRepeatCountMax(schema, currentMax);
        if (migratedMax != currentMax) {
            RSIntegrationConfig.REPEAT_COUNT_MAX.set(migratedMax);
            LOGGER.info("[RSI-Config] Migrated repeatCountMax from 64 to {}",
                    migratedMax);
        }
        var currentAdapters = RSIntegrationConfig.ANVIL_MEMORY_ADAPTERS.get();
        var migratedAdapters = RSIntegrationConfig.migrateAnvilMemoryAdapters(schema, currentAdapters);
        if (!migratedAdapters.equals(currentAdapters)) {
            RSIntegrationConfig.ANVIL_MEMORY_ADAPTERS.set(migratedAdapters);
            LOGGER.info("[RSI-Config] Enabled new anvil memory adapters: {}", migratedAdapters);
        }
        int currentTypedTimeout = RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.get();
        int migratedTypedTimeout = RSIntegrationConfig.migrateTypedPreviewTimeoutMs(
                schema, currentTypedTimeout);
        if (migratedTypedTimeout != currentTypedTimeout) {
            RSIntegrationConfig.CRAFTING_TYPED_PREVIEW_TIMEOUT_MS.set(migratedTypedTimeout);
            LOGGER.info("[RSI-Config] Migrated craftingTypedPreviewTimeoutMs from 50 to {}",
                    migratedTypedTimeout);
        }
        int currentPureTimeout = RSIntegrationConfig.CRAFTING_PURE_PLANNING_TIMEOUT_MS.get();
        int migratedPureTimeout = RSIntegrationConfig.migratePurePlanningTimeoutMs(
                schema, currentPureTimeout);
        if (migratedPureTimeout != currentPureTimeout) {
            RSIntegrationConfig.CRAFTING_PURE_PLANNING_TIMEOUT_MS.set(migratedPureTimeout);
            LOGGER.info("[RSI-Config] Migrated craftingPurePlanningTimeoutMs from 500 to {}",
                    migratedPureTimeout);
        }
        int currentPlanCacheTtl = RSIntegrationConfig.CRAFTING_PLAN_CACHE_TTL_MS.get();
        int migratedPlanCacheTtl = RSIntegrationConfig.migratePlanCacheTtlMs(
                schema, currentPlanCacheTtl);
        if (migratedPlanCacheTtl != currentPlanCacheTtl) {
            RSIntegrationConfig.CRAFTING_PLAN_CACHE_TTL_MS.set(migratedPlanCacheTtl);
            LOGGER.info("[RSI-Config] Migrated craftingPlanCacheTtlMs from 500 to {}",
                    migratedPlanCacheTtl);
        }
        RSIntegrationConfig.SERVER_CONFIG_SCHEMA_VERSION
                .set(RSIntegrationConfig.SERVER_CONFIG_SCHEMA);
        config.save();
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        StorageBackendLoadResult rsBackend = STORAGE_BACKENDS.load(
                StorageBackendDescriptors.REFINED_STORAGE);
        if (rsBackend.loaded()) {
            LOGGER.info("[RSI-Storage] Registered backend {}", rsBackend.backendId());
        } else {
            LOGGER.warn("[RSI-Storage] Backend {} was not registered: {}",
                    rsBackend.backendId(), rsBackend.status());
        }
        StorageBackendLoadResult bdBackend = STORAGE_BACKENDS.load(
                StorageBackendDescriptors.BEYOND_DIMENSIONS);
        if (bdBackend.loaded()) {
            LOGGER.info("[RSI-Storage] Registered backend {}", bdBackend.backendId());
            AltarBindingRegistry.registerHook(
                    com.huanghuang.rsintegration.network.binding.AltarBinding.BD_NETWORK,
                    com.huanghuang.rsintegration.network.binding.BeyondDimensionsBindingHook.INSTANCE);
        } else {
            LOGGER.info("[RSI-Storage] Optional backend {} was not registered: {}",
                    bdBackend.backendId(), bdBackend.status());
        }
        if (!ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            LOGGER.info("[RSI-Storage] Refined Storage is absent; storage operations stay disabled, but cross-mod recipe and JEI metadata remain registered.");
        }
        com.huanghuang.rsintegration.compat.ftbquests.ExternalItemProgressBridge.initialize();
        for (ModuleEntry entry : MODULES) {
            if (!entry.configFlag().get()) continue;
            IModIntegration module = entry.supplier().get();
            if (module.modIds().stream().noneMatch(id -> ModList.get().isLoaded(id))) continue;
            module.registerModType();
            module.registerBindingTargets();
            module.registerRecipeHandler();
            module.registerNetworkPackets();
            module.initCommon();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> module.clientInitSupplier().get()::run);
        }

        // --- FarmingForBlockheads Market (virtual exchange, no IModIntegration) ---
        if (enabled(RSIntegrationConfig.ENABLE_FARMINGFORBLOCKHEADS, ModIds.FARMINGFORBLOCKHEADS))
            FarmingForBlockheadsRSModule.initCommon();

        // --- Confluence Workshop 閳?binding target registered by the module;
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

            // 閳光偓閳光偓 Furnace 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓
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

            // 閳光偓閳光偓 Blast Furnace 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓
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

            // 閳光偓閳光偓 Smoker 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓
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

            // 閳光偓閳光偓 Campfire (no GUI) 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓
            // When FD is loaded, farmersdelight_skillet handles all
            // CampfireCookingRecipe classification and campfire binding.
            // Avoid registering vanilla_campfire at all 閳?if both it and
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

            // 閳光偓閳光偓 Stonecutter 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓
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

            ModType.register("vanilla_brewing_stand",
                    new String[]{"com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingRecipeDefinition"},
                    new String[]{"brewing_stand"},
                    new String[]{"vanilla_brewing_stand"},
                    ModType.delegateSupplier("com.huanghuang.rsintegration.mods.vanilla.brewing.BrewingStandBatchDelegate"));
            ModType.configureJei("vanilla_brewing_stand",
                    new String[][]{{"minecraft:brewing", "vanilla_brewing_stand"}},
                    new String[][]{{"com.huanghuang.rsintegration.mods.vanilla.brewing.VanillaBrewingRecipeDefinition",
                            "vanilla_brewing_stand"}},
                    "gui.rs_integration.jei.vanilla_brewing_stand_craft");
            BindingEventHandler.registerTarget(
                    new BindingEventHandler.MachineBindingTarget(
                            "minecraft", ModType.byId("vanilla_brewing_stand"),
                            RSIntegrationConfig.ENABLE_VANILLA_MACHINES,
                            List.of("net.minecraft.world.level.block.BrewingStandBlock"),
                            "vanilla_brewing_stand"));

            // 閳光偓閳光偓 Anvil (JEI-only, opens remote GUI) 閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓閳光偓
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

        // Enchanting is player/seed driven rather than recipe driven. Register
        // only its GUI so it can be bound and opened remotely without exposing
        // a batch-crafting or recursive-crafting action.
        BindingEventHandler.registerTarget(
                new BindingEventHandler.MachineBindingTarget(
                        "minecraft", ModType.CUSTOM_GUI,
                        RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS,
                        List.of(), List.of("minecraft:enchanting_table"),
                        "custom_gui", true));

        // Subsystems
        if (RSIntegrationConfig.ENABLE_CONTAINER_TRANSFER.get()
                && (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                || ModList.get().isLoaded(ModIds.SOPHISTICATED_BACKPACKS)
                || ModList.get().isLoaded("beyonddimensions"))) {
            ContainerTransferNetworkHandler.register();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> ContainerTransferClient::init);
        }
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> RSOptionalBootstrap::registerSidePanelClient);
            // This registers the shared RS integration channel even when the
            // side-panel UI itself is disabled.
            RSOptionalBootstrap.registerSidePanelCommon();
        }

        // Binding tooltip handler
        com.huanghuang.rsintegration.network.binding.NearbyBindingRequestPacket.register();
        com.huanghuang.rsintegration.network.binding.ExplicitMachineBindingPacket.register();
        if (ModList.get().isLoaded("beyonddimensions")) {
            com.huanghuang.rsintegration.machine.BeyondDimensionsMachineNetworkHandler.register();
        }
        // Crafting
        BatchCraftNetworkHandler.register();
        // Resonance storage is shared by RS and Beyond Dimensions. Register
        // its sync packet independently of either optional storage backend.
        ResonanceNetworkHandler.register();
        com.huanghuang.rsintegration.villager.VillagerRestockNetworkHandler.register();
        com.huanghuang.rsintegration.enchanting.EnchantingRestockNetworkHandler.register();
        com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockSnapshotPacket.register();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> com.huanghuang.rsintegration.villager.client.VillagerRestockClient::init);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> com.huanghuang.rsintegration.enchanting.client.EnchantingRestockClient::init);
        com.huanghuang.rsintegration.reforging.ReforgingRestockNetworkHandler.register();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> com.huanghuang.rsintegration.reforging.client.ReforgingRestockClient::init);
        com.huanghuang.rsintegration.anvilmemory.AnvilMemoryNetworkHandler.register();
        MinecraftForge.EVENT_BUS.register(com.huanghuang.rsintegration.anvilmemory.AnvilMemoryEvents.class);
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> com.huanghuang.rsintegration.anvilmemory.AnvilMemoryClient::init);
        ConfigSyncPacket.register();
        com.huanghuang.rsintegration.mods.jei.JeiCheatDropPacket.register();
        com.huanghuang.rsintegration.network.packet.StorageSearchTextPacket.register();
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.JEI_NETWORK_INVENTORY,
                com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket.class,
                com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket::encode,
                com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket::decode,
                com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.JEI_NETWORK_INVENTORY_RESYNC,
                com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryResyncRequestPacket.class,
                com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryResyncRequestPacket::encode,
                com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryResyncRequestPacket::decode,
                com.huanghuang.rsintegration.network.packet.JeiNetworkInventoryResyncRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        MinecraftForge.EVENT_BUS.register(com.huanghuang.rsintegration.server.JeiNetworkInventorySyncManager.class);

        // Altar binding registry (BINDINGS cache + scan caches)
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                || ModList.get().isLoaded("beyonddimensions")) {
            MinecraftForge.EVENT_BUS.register(AltarBindingRegistry.class);
        }

        // /reload clears recipe output caches so new datapack recipes take effect
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.AddReloadListenerEvent e) -> {
            com.huanghuang.rsintegration.recipe.ModRecipeHandlers.clearResultCaches();
            com.huanghuang.rsintegration.crafting.CraftPlanningRevision.bump();
            com.huanghuang.rsintegration.crafting.RecipeIndex.invalidate();
        });

        // Async craft chains
        MinecraftForge.EVENT_BUS.register(AsyncCraftManager.getInstance());
        // Sync server config to client on login
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                        ConfigSyncPacket.fromServerConfig());
                if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
                    RSOptionalBootstrap.onPlayerLoggedIn(sp);
                }
                sp.server.execute(() -> com.huanghuang.rsintegration.crafting.RecipeIndex
                        .refreshDynamicRuntimeIfNeeded(sp.server.overworld()));
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                AsyncCraftManager.getInstance().cancelAllForPlayer(sp.getUUID());
                com.huanghuang.rsintegration.autoeat.AutoEatRateLimiter.onPlayerLogout(sp.getUUID());
                com.huanghuang.rsintegration.autoeat.AutoEatEngine.onPlayerLogout(sp.getUUID());
                com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket
                        .onPlayerLogout(sp.getUUID());
                com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockService
                        .remove(sp.getUUID());
            }
        });
        // Compile and publish one complete recipe generation before normal server
        // ticks begin. Preview clicks never advance this work or wait behind it.
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) ->
                com.huanghuang.rsintegration.crafting.RecipeIndex
                        .warmUp(e.getServer().overworld()));
        // /reload fires this event after the new recipes have been applied and
        // before they are sent to clients. Rebuild against that completed revision.
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.OnDatapackSyncEvent e) -> {
            if (e.getPlayer() == null) {
                com.huanghuang.rsintegration.crafting.RecipeIndex
                        .warmUp(e.getPlayerList().getServer().overworld());
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ServerTickEvent e) -> {
            if (e.phase == net.minecraftforge.event.TickEvent.Phase.END) {
                com.huanghuang.rsintegration.autoeat.AutoEatEngine.tick(e.getServer());
                com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket
                        .tickWarmUpRequests(e.getServer());
            }
        });
        // Cross-dimension: unpin the old dimension's IStorageCache listener
        // and re-register against the new dimension's network.
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) {
                if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
                    RSOptionalBootstrap.onPlayerChangedDimension(sp);
                }
                com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache.getInstance().clearDimension(e.getFrom().location());
            }
        });

        // Server shutdown: abort all active async craft chains so committed
        // materials are refunded rather than silently lost.
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            com.huanghuang.rsintegration.crafting.batch.GenericCraftPacket.cancelAllPlanning();
            com.huanghuang.rsintegration.crafting.RecipeIndex.invalidate();
            AsyncCraftManager.abortAll();
            com.huanghuang.rsintegration.crafting.CraftOutputInterceptor.clearAll();
            com.huanghuang.rsintegration.mods.embers.EreAlchemyLock.clearAll();
            if (ModList.get().isLoaded(ModIds.IRON_FURNACES)) {
                com.huanghuang.rsintegration.mods.ironfurnaces.IronFurnacesBatchDelegate
                        .clearFactoryLeases();
            }
            RemoteGuiAuth.clearServerState();
            if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
                RSOptionalBootstrap.clearServerState();
            }
            com.huanghuang.rsintegration.villager.tradelock.VillagerTradeLockService.clear();
        });

        // Chunk unload safety net: force-close remote GUI whose machine
        // chunk is being unloaded. Primary prevention is ForgeChunkManager
        // active-chunk retention in RemoteGuiAuth.authorize(); this catches edge cases
        // (e.g. another mod force-unloading the chunk).
        MinecraftForge.EVENT_BUS.addListener(RemoteGuiAuth::onChunkUnload);

        // Auto-eat system
        AutoEatNetworkHandler.register();
        if (ModList.get().isLoaded("beyonddimensions")) {
            com.huanghuang.rsintegration.resonance.api.ResonanceStorageResolvers.register(
                    com.huanghuang.rsintegration.resonance.bd.BDResonanceDiskAccess::resolve);
        }
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)) {
            RSOptionalBootstrap.registerCommon();
        } else if (ModList.get().isLoaded("beyonddimensions")) {
            MinecraftForge.EVENT_BUS.register(
                    com.huanghuang.rsintegration.resonance.passive.PassiveEffectEngine.class);
        }

        ModType.confirmReviewedGraphExecution(REVIEWED_GRAPH_EXECUTION_TYPES,
                "startup registry audit: standard machine lease, material transaction and output capture apply");
        ContractValidation.validateAll();

        LOGGER.info("{} initialized.", MOD_NAME);
    }

    private void onClientSetup(final net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        if (ModList.get().isLoaded(ModIds.REFINED_STORAGE)
                || ModList.get().isLoaded("beyonddimensions")) {
            net.minecraft.client.gui.screens.MenuScreens.register(
                    (net.minecraft.world.inventory.MenuType) ModItems.RESONANCE_BACKPACK.get(),
                    com.huanghuang.rsintegration.resonance.backpack.ResonanceBackpackScreen::new);
        }
    }

    private static boolean enabled(ForgeConfigSpec.BooleanValue config, String modId) {
        if (!ModList.get().isLoaded(modId)) return false;
        return config.get();
    }

    @SuppressWarnings("removal")
    private static void broadcastConfigSync() {
        var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        var packet = ConfigSyncPacket.fromServerConfig();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            NetworkHandler.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
        }
    }

}
