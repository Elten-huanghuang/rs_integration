package com.huanghuang.rsintegration.network.packet;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import java.util.Optional;
import net.minecraftforge.network.NetworkDirection;

import java.util.function.Supplier;

/**
 * Server→Client: synchronizes server-authoritative config values to the client.
 * Sent on PlayerLoggedInEvent. Values override client-local config in memory only.
 */
public class ConfigSyncPacket {
    private static boolean registered;

    public final boolean enableMachineGuiTabs;
    public final int machineTabThreshold;
    public final boolean enableAutoEat;
    public final boolean enableJei;
    public final boolean enableJeiNetworkOverlay;
    public final boolean enableJeiCraftingShortageOverlay;
    public final boolean enableJeiMarquee;
    public final boolean enableJeiBookmarkMarquee;
    public final boolean enableGridSwipeExtract;
    // Read by client-side screens/overlays but declared COMMON/SERVER, so the
    // client's own file would otherwise win over the server's setting.
    public final boolean enableApotheosis;
    public final boolean enableDistantWorlds;
    public final boolean enableEmbersAlchemyCalc;
    public final int recipeTreeMaxCandidates;
    public final int repeatCountMax;

    public ConfigSyncPacket(boolean enableMachineGuiTabs, int machineTabThreshold,
                            boolean enableAutoEat, boolean enableJei, boolean enableJeiNetworkOverlay,
                            boolean enableJeiCraftingShortageOverlay, boolean enableJeiMarquee,
                            boolean enableJeiBookmarkMarquee, boolean enableGridSwipeExtract,
                            boolean enableApotheosis, boolean enableDistantWorlds,
                            boolean enableEmbersAlchemyCalc, int recipeTreeMaxCandidates,
                            int repeatCountMax) {
        this.enableMachineGuiTabs = enableMachineGuiTabs;
        this.machineTabThreshold = machineTabThreshold;
        this.enableAutoEat = enableAutoEat;
        this.enableJei = enableJei;
        this.enableJeiNetworkOverlay = enableJeiNetworkOverlay;
        this.enableJeiCraftingShortageOverlay = enableJeiCraftingShortageOverlay;
        this.enableJeiMarquee = enableJeiMarquee;
        this.enableJeiBookmarkMarquee = enableJeiBookmarkMarquee;
        this.enableGridSwipeExtract = enableGridSwipeExtract;
        this.enableApotheosis = enableApotheosis;
        this.enableDistantWorlds = enableDistantWorlds;
        this.enableEmbersAlchemyCalc = enableEmbersAlchemyCalc;
        this.recipeTreeMaxCandidates = recipeTreeMaxCandidates;
        this.repeatCountMax = repeatCountMax;
    }

    public static ConfigSyncPacket fromServerConfig() {
        return new ConfigSyncPacket(
                RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get(),
                RSIntegrationConfig.MACHINE_TAB_THRESHOLD.get(),
                RSIntegrationConfig.ENABLE_AUTO_EAT.get(),
                RSIntegrationConfig.ENABLE_JEI.get(),
                RSIntegrationConfig.ENABLE_JEI_NETWORK_OVERLAY.get(),
                RSIntegrationConfig.ENABLE_JEI_CRAFTING_SHORTAGE_OVERLAY.get(),
                RSIntegrationConfig.ENABLE_JEI_MARQUEE_SELECTION.get(),
                RSIntegrationConfig.ENABLE_JEI_BOOKMARK_MARQUEE_SELECTION.get(),
                RSIntegrationConfig.ENABLE_RS_GRID_SWIPE_EXTRACT.get(),
                RSIntegrationConfig.ENABLE_APOTHEOSIS.get(),
                RSIntegrationConfig.ENABLE_DISTANT_WORLDS.get(),
                RSIntegrationConfig.ENABLE_EMBERS_ALCHEMY_CALC.get(),
                RSIntegrationConfig.RECIPE_TREE_MAX_CANDIDATES.get(),
                RSIntegrationConfig.REPEAT_COUNT_MAX.get());
    }

    public static void encode(ConfigSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enableMachineGuiTabs);
        buf.writeVarInt(packet.machineTabThreshold);
        buf.writeBoolean(packet.enableAutoEat);
        buf.writeBoolean(packet.enableJei);
        buf.writeBoolean(packet.enableJeiNetworkOverlay);
        buf.writeBoolean(packet.enableJeiCraftingShortageOverlay);
        buf.writeBoolean(packet.enableJeiMarquee);
        buf.writeBoolean(packet.enableJeiBookmarkMarquee);
        buf.writeBoolean(packet.enableGridSwipeExtract);
        buf.writeBoolean(packet.enableApotheosis);
        buf.writeBoolean(packet.enableDistantWorlds);
        buf.writeBoolean(packet.enableEmbersAlchemyCalc);
        buf.writeVarInt(packet.recipeTreeMaxCandidates);
        buf.writeVarInt(packet.repeatCountMax);
    }

    public static ConfigSyncPacket decode(FriendlyByteBuf buf) {
        return new ConfigSyncPacket(buf.readBoolean(), Math.max(0, Math.min(buf.readVarInt(), 4096)), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                // Clamp to the config's own declared range (2-32).
                Math.max(2, Math.min(buf.readVarInt(), 32)),
                // Server-authoritative crafting request limit.
                Math.max(1, Math.min(buf.readVarInt(),
                        RSIntegrationConfig.REPEAT_COUNT_ABSOLUTE_MAX)));
    }

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.CONFIG_SYNC, ConfigSyncPacket.class,
                ConfigSyncPacket::encode, ConfigSyncPacket::decode, ConfigSyncPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    public static void handle(ConfigSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ConfigSyncClientPacketHandler.handle(packet)));
        ctx.get().setPacketHandled(true);
    }
}
