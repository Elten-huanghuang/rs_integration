package com.huanghuang.rsintegration;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.AltarBinding;
import com.huanghuang.rsintegration.network.AltarBindingRegistry;
import com.huanghuang.rsintegration.network.RSBindingHook;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.util.ModIds;
import com.huanghuang.rsintegration.sidepanel.RSSidePanelNetworkHandler;
import com.refinedmods.refinedstorage.api.network.INetwork;

import java.util.List;
import java.util.function.Supplier;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
    /** RS-themed flow colors used by magnet tooltip and machine hub tooltip. */
    public static final int[] RS_FLOW_COLORS = {0x3355FF, 0x7733FF, 0xCC33FF, 0x3355FF};
    /** Captured mod event bus — safe to use from FMLCommonSetupEvent listeners
     *  where {@code FMLJavaModLoadingContext.get()} is no longer available. */
    public static final net.minecraftforge.eventbus.api.IEventBus MOD_BUS =
            net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus();

    static {
        DistExecutor.safeRunWhenOn(Dist.CLIENT, () -> () ->
                MOD_BUS.addListener(com.huanghuang.rsintegration.mods.aetherworks.client.AetherworksClientSetup::onRegisterOverlays));
    }

    private record ModuleEntry(String modId, ForgeConfigSpec.BooleanValue configFlag,
                               Supplier<IModIntegration> supplier) {}

    private static final List<ModuleEntry> MODULES = List.of(
            new ModuleEntry("goety", RSIntegrationConfig.ENABLE_GOETY,
                    () -> com.huanghuang.rsintegration.mods.goety.GoetyRSModule.INSTANCE),
            new ModuleEntry("malum", RSIntegrationConfig.ENABLE_MALUM,
                    () -> com.huanghuang.rsintegration.mods.malum.MalumRSModule.INSTANCE),
            new ModuleEntry("eidolon", RSIntegrationConfig.ENABLE_EIDOLON,
                    () -> com.huanghuang.rsintegration.mods.eidolon.EidolonRSModule.INSTANCE),
            new ModuleEntry("forbidden_arcanus", RSIntegrationConfig.ENABLE_FORBIDDEN_ARCANUS,
                    () -> com.huanghuang.rsintegration.mods.forbidden.FaRSModule.INSTANCE),
            new ModuleEntry("wizards_reborn", RSIntegrationConfig.ENABLE_WIZARDS_REBORN,
                    () -> com.huanghuang.rsintegration.mods.wizards_reborn.WizardsRebornRSModule.INSTANCE),
            new ModuleEntry("touhou_little_maid", RSIntegrationConfig.ENABLE_TOUHOU_LITTLE_MAID,
                    () -> com.huanghuang.rsintegration.mods.touhoulittlemaid.TlmRSModule.INSTANCE),
            new ModuleEntry("embers", RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY,
                    () -> com.huanghuang.rsintegration.mods.embers.EreAlchemyRSModule.INSTANCE),
            new ModuleEntry("aetherworks", RSIntegrationConfig.ENABLE_AETHERWORKS,
                    () -> com.huanghuang.rsintegration.mods.aetherworks.AetherworksRSModule.INSTANCE),
            new ModuleEntry("aether", RSIntegrationConfig.ENABLE_AETHER,
                    () -> com.huanghuang.rsintegration.mods.aether.AetherRSModule.INSTANCE),
            new ModuleEntry("crockpot", RSIntegrationConfig.ENABLE_CROCKPOT,
                    () -> com.huanghuang.rsintegration.mods.crockpot.CrockPotRSModule.INSTANCE),
            new ModuleEntry("tacz", RSIntegrationConfig.ENABLE_TACZ,
                    () -> com.huanghuang.rsintegration.mods.tacz.TaczRSModule.INSTANCE),
            new ModuleEntry("slashblade", RSIntegrationConfig.ENABLE_SLASHBLADE,
                    () -> com.huanghuang.rsintegration.mods.slashblade.SlashBladeRSModule.INSTANCE),
            new ModuleEntry(ModIds.AVARITIA, RSIntegrationConfig.ENABLE_AVARITIA,
                    () -> com.huanghuang.rsintegration.mods.avaritia.AvaritiaRSModule.INSTANCE)
    );

    public RSIntegrationMod() {
        registerDisplayTest();
        RSIntegrationConfig.register();
        AltarBindingRegistry.registerHook(AltarBinding.RS_NETWORK, RSBindingHook.INSTANCE);
        // Enable ForgeChunkManager.forceChunk for this mod (used by
        // RemoteGuiAuth to prevent chunk-unload dupes during remote GUI).
        // Null callback causes NPE in reinstatePersistentChunks on world
        // load if any ticket data leaked to disk — use a no-op instead.
        net.minecraftforge.common.world.ForgeChunkManager.setForcedChunkLoadingCallback(
                MOD_ID, (level, ticketHelper) -> {});
        // Backpack upgrades: DeferredRegister must be registered NOW
        // (during mod construction) so Forge's registry events pick them up.
        // Waiting until FMLCommonSetupEvent is too late.
        if (enabled(RSIntegrationConfig.ENABLE_SOPHISTICATED_BACKPACKS, ModIds.SOPHISTICATED_BACKPACKS)) {
            com.huanghuang.rsintegration.backpack.SophisticatedBackpacksItems.init(MOD_BUS);
        }

        // Defer all heavy initialization to FMLCommonSetupEvent so the
        // constructor finishes instantly.  This minimises RSI's impact
        // on Forge's parallel mod-loading schedule and avoids exposing
        // latent race conditions in other mods (e.g. l2hostility CME,
        // Enigmatic Legacy NPE).
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onCommonSetup);
    }

    private void onCommonSetup(final FMLCommonSetupEvent event) {
        // Per-mod init via IModIntegration interface.
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
            com.huanghuang.rsintegration.mods.farmingforblockheads.FarmingForBlockheadsRSModule.initCommon();

        // --- Confluence Workshop (must be explicit — customGuiMachineMods
        //     fallback requires MenuProvider, which WorkshopBlock may lack) ---
        if (ModList.get().isLoaded("confluence")) {
            com.huanghuang.rsintegration.network.BindingEventHandler.registerTarget(
                    new com.huanghuang.rsintegration.network.BindingEventHandler.MachineBindingTarget(
                            "confluence", ModType.byId("custom_gui"),
                            RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS,
                            List.of("org.confluence.mod.block.WorkshopBlock"),
                            "confluence"
                    ));
        }

        // --- Apotheosis Reforging Table (must be explicit — customGuiMachineMods
        //     fallback requires MenuProvider, which ReforgingTableBlock may lack) ---
        if (ModList.get().isLoaded("apotheosis")) {
            com.huanghuang.rsintegration.network.BindingEventHandler.registerTarget(
                    new com.huanghuang.rsintegration.network.BindingEventHandler.MachineBindingTarget(
                            "apotheosis", ModType.byId("custom_gui"),
                            RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS,
                            List.of("dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingTableBlock"),
                            "apotheosis"
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
            com.huanghuang.rsintegration.network.BindingEventHandler.registerTarget(
                    new com.huanghuang.rsintegration.network.BindingEventHandler.MachineBindingTarget(
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

            // Smithing table → separate ModType so it shows "锻造台" rather than "熔炉烧炼"
            ModType.register("smithing",
                    new String[]{
                            "net.minecraft.world.item.crafting.SmithingTransformRecipe",
                            "net.minecraft.world.item.crafting.SmithingTrimRecipe"
                    },
                    new String[]{"smithing_table"},
                    new String[]{"smithing"},
                    ModType.delegateSupplier("com.huanghuang.rsintegration.mods.vanilla.VanillaMachineBatchDelegate"));
            com.huanghuang.rsintegration.network.BindingEventHandler.registerTarget(
                    new com.huanghuang.rsintegration.network.BindingEventHandler.MachineBindingTarget(
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
            com.huanghuang.rsintegration.transfer.ContainerTransferNetworkHandler.register();
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.transfer.ContainerTransferClient::init);
        }
        if (RSIntegrationConfig.ENABLE_RS_SIDE_PANEL.get()) {
            DistExecutor.safeRunWhenOn(Dist.CLIENT,
                    () -> com.huanghuang.rsintegration.sidepanel.RSSidePanelModule::initClient);
            com.huanghuang.rsintegration.sidepanel.RSSidePanelModule.initCommon();
        }

        // Binding tooltip handler — shows bound machine list on Shift for any item with BindingStorage data
        DistExecutor.safeRunWhenOn(Dist.CLIENT,
                () -> () -> MinecraftForge.EVENT_BUS.register(
                        com.huanghuang.rsintegration.network.BindingTooltipHandler.class));
        // Crafting
        BatchCraftNetworkHandler.register();

        // Altar binding registry (BINDINGS cache + scan caches)
        MinecraftForge.EVENT_BUS.register(com.huanghuang.rsintegration.network.AltarBindingRegistry.class);

        // Async craft chains
        MinecraftForge.EVENT_BUS.register(com.huanghuang.rsintegration.crafting.AsyncCraftManager.getInstance());
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp)
                com.huanghuang.rsintegration.crafting.AsyncCraftManager.getInstance().cancelAllForPlayer(sp.getUUID());
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
            com.huanghuang.rsintegration.crafting.AsyncCraftManager.abortAll();
        });

        // Chunk unload safety net: force-close any remote GUI whose machine
        // chunk is being unloaded.  Primary prevention is ForgeChunkManager
        // force-loading in RemoteGuiAuth.authorize(); this catches edge cases
        // (e.g. another mod force-unloading the chunk).
        MinecraftForge.EVENT_BUS.addListener(
                com.huanghuang.rsintegration.network.RemoteGuiAuth::onChunkUnload);

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
