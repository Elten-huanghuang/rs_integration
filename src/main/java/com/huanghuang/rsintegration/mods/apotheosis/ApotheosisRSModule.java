package com.huanghuang.rsintegration.mods.apotheosis;

import com.huanghuang.rsintegration.mods.apotheosis.client.ApotheosisLibraryClientEvents;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.IModIntegration;
import com.huanghuang.rsintegration.network.binding.BindingEventHandler;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryImportRequestPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryImportResultPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryLevelPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryScanRequestPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApotheosisLibraryScanResponsePacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerExecutePacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerRefreshPacket;
import com.huanghuang.rsintegration.mods.apotheosis.network.ApothSpawnerStatePacket;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.DistExecutor;

import java.util.List;
import java.util.function.Supplier;

/** Optional Apotheosis integration. Classes from Apotheosis are referenced lazily by name. */
public final class ApotheosisRSModule implements IModIntegration {
    public static final ApotheosisRSModule INSTANCE = new ApotheosisRSModule();

    public static final String FLETCHING_TYPE = "apotheosis_fletching";
    public static final String LIBRARY_TYPE = "apotheosis_library";
    public static final String GEM_CUTTING_TYPE = "apotheosis_gem_cutting";

    private ApotheosisRSModule() {}

    @Override
    public ForgeConfigSpec.BooleanValue configFlag() {
        return RSIntegrationConfig.ENABLE_APOTHEOSIS;
    }

    @Override
    public String modId() {
        return "apotheosis";
    }

    @Override
    public void registerModType() {
        ModType.register(
                FLETCHING_TYPE,
                new String[]{"dev.shadowsoffire.apotheosis.village.fletching.FletchingRecipe"},
                new String[]{"fletching_table", "fletching"},
                new String[]{"apotheosis_fletching"},
                ModType.delegateSupplier(
                        "com.huanghuang.rsintegration.mods.apotheosis.ApotheosisFletchingBatchDelegate"));
        ModType.register(
                LIBRARY_TYPE,
                new String[0],
                new String[]{"library", "ender_library"},
                new String[]{LIBRARY_TYPE},
                () -> null);
        ModType.register(
                GEM_CUTTING_TYPE,
                new String[]{ApotheosisGemCuttingRecipe.class.getName()},
                new String[]{"gem_cutting_table", "gem_cutting"},
                new String[]{GEM_CUTTING_TYPE},
                ModType.delegateSupplier("com.huanghuang.rsintegration.mods.apotheosis.ApotheosisGemCuttingBatchDelegate"));
        // Apotheosis 7.4.3 exposes FletchingCategory.TYPE as apotheosis:fletching.
        ModType.configureJei(
                "apotheosis_fletching",
                new String[][]{{"apotheosis:fletching", "apotheosis_fletching"}},
                new String[][]{{
                        "dev.shadowsoffire.apotheosis.village.fletching.FletchingRecipe",
                        "apotheosis_fletching"
                }},
                "gui.rs_integration.jei.apotheosis_fletching");
        ModType.configureJei(
                GEM_CUTTING_TYPE,
                new String[][]{{"apotheosis:gem_cutting", GEM_CUTTING_TYPE}},
                new String[][]{{
                        "dev.shadowsoffire.apotheosis.adventure.compat.GemCuttingCategory$GemCuttingRecipe",
                        GEM_CUTTING_TYPE
                }},
                "gui.rs_integration.jei.apotheosis_gem_cutting");
    }

    @Override
    public void registerBindingTargets() {
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "apotheosis", ModType.byId(FLETCHING_TYPE),
                RSIntegrationConfig.ENABLE_APOTHEOSIS,
                List.of("dev.shadowsoffire.apotheosis.village.fletching.ApothFletchingBlock"),
                List.of("minecraft:fletching_table"),
                FLETCHING_TYPE, true));

        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "apotheosis", ModType.byId(GEM_CUTTING_TYPE),
                RSIntegrationConfig.ENABLE_APOTHEOSIS,
                List.of("dev.shadowsoffire.apotheosis.adventure.socket.gem.cutting.GemCuttingBlock"),
                List.of("apotheosis:gem_cutting_table"), GEM_CUTTING_TYPE, true));

        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "apotheosis", ModType.byId(LIBRARY_TYPE),
                RSIntegrationConfig.ENABLE_APOTHEOSIS,
                List.of("dev.shadowsoffire.apotheosis.ench.library.EnchLibraryBlock"),
                List.of("apotheosis:library", "apotheosis:ender_library"),
                LIBRARY_TYPE, true));

        // Reforging remains a GUI-only machine, but is now registered in the same
        // optional module so the generic custom-GUI fallback cannot claim it first.
        BindingEventHandler.registerTarget(new BindingEventHandler.MachineBindingTarget(
                "apotheosis", ModType.byId("custom_gui"),
                RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS,
                List.of("dev.shadowsoffire.apotheosis.adventure.affix.reforging.ReforgingTableBlock"),
                "apotheosis"));
    }

    @Override
    public void registerRecipeHandler() {
        ModRecipeHandlers.register(new ApotheosisFletchingRecipeHandler());
        ModRecipeHandlers.register(new ApotheosisGemCuttingRecipeHandler());
    }

    @Override
    public void registerNetworkPackets() {
        var channel = NetworkHandler.CHANNEL;
        channel.registerMessage(NetworkPacketIds.APOTHEOSIS_LIBRARY_LEVEL,
                ApotheosisLibraryLevelPacket.class,
                ApotheosisLibraryLevelPacket::encode,
                ApotheosisLibraryLevelPacket::decode,
                ApotheosisLibraryLevelPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(NetworkPacketIds.APOTHEOSIS_LIBRARY_SCAN_REQUEST,
                ApotheosisLibraryScanRequestPacket.class,
                ApotheosisLibraryScanRequestPacket::encode,
                ApotheosisLibraryScanRequestPacket::decode,
                ApotheosisLibraryScanRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(NetworkPacketIds.APOTHEOSIS_LIBRARY_SCAN_RESPONSE,
                ApotheosisLibraryScanResponsePacket.class,
                ApotheosisLibraryScanResponsePacket::encode,
                ApotheosisLibraryScanResponsePacket::decode,
                ApotheosisLibraryScanResponsePacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(NetworkPacketIds.APOTHEOSIS_LIBRARY_IMPORT_REQUEST,
                ApotheosisLibraryImportRequestPacket.class,
                ApotheosisLibraryImportRequestPacket::encode,
                ApotheosisLibraryImportRequestPacket::decode,
                ApotheosisLibraryImportRequestPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(NetworkPacketIds.APOTHEOSIS_LIBRARY_IMPORT_RESULT,
                ApotheosisLibraryImportResultPacket.class,
                ApotheosisLibraryImportResultPacket::encode,
                ApotheosisLibraryImportResultPacket::decode,
                ApotheosisLibraryImportResultPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(NetworkPacketIds.APOTHEOSIS_SPAWNER_STATE,
                ApothSpawnerStatePacket.class, ApothSpawnerStatePacket::encode,
                ApothSpawnerStatePacket::decode, ApothSpawnerStatePacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        channel.registerMessage(NetworkPacketIds.APOTHEOSIS_SPAWNER_EXECUTE,
                ApothSpawnerExecutePacket.class, ApothSpawnerExecutePacket::encode,
                ApothSpawnerExecutePacket::decode, ApothSpawnerExecutePacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
        channel.registerMessage(NetworkPacketIds.APOTHEOSIS_SPAWNER_REFRESH,
                ApothSpawnerRefreshPacket.class, ApothSpawnerRefreshPacket::encode,
                ApothSpawnerRefreshPacket::decode, ApothSpawnerRefreshPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_SERVER));
    }

    @Override
    public void initCommon() {
        MinecraftForge.EVENT_BUS.register(ApothSpawnerInteractionHandler.class);
    }

    @Override
    public Supplier<DistExecutor.SafeRunnable> clientInitSupplier() {
        return () -> () -> MinecraftForge.EVENT_BUS.register(
                ApotheosisLibraryClientEvents.class);
    }
}
