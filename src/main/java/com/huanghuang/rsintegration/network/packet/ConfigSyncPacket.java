package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.config.ClientSyncedConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

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
    public final boolean enableJeiMarquee;
    public final boolean enableJeiBookmarkMarquee;
    public final boolean enableGridSwipeExtract;

    public ConfigSyncPacket(boolean enableMachineGuiTabs, int machineTabThreshold,
                            boolean enableAutoEat, boolean enableJei, boolean enableJeiMarquee,
                            boolean enableJeiBookmarkMarquee, boolean enableGridSwipeExtract) {
        this.enableMachineGuiTabs = enableMachineGuiTabs;
        this.machineTabThreshold = machineTabThreshold;
        this.enableAutoEat = enableAutoEat;
        this.enableJei = enableJei;
        this.enableJeiMarquee = enableJeiMarquee;
        this.enableJeiBookmarkMarquee = enableJeiBookmarkMarquee;
        this.enableGridSwipeExtract = enableGridSwipeExtract;
    }

    public static ConfigSyncPacket fromServerConfig() {
        return new ConfigSyncPacket(
                com.huanghuang.rsintegration.config.RSIntegrationConfig.ENABLE_MACHINE_GUI_TABS.get(),
                com.huanghuang.rsintegration.config.RSIntegrationConfig.MACHINE_TAB_THRESHOLD.get(),
                com.huanghuang.rsintegration.config.RSIntegrationConfig.ENABLE_AUTO_EAT.get(),
                com.huanghuang.rsintegration.config.RSIntegrationConfig.ENABLE_JEI.get(),
                com.huanghuang.rsintegration.config.RSIntegrationConfig.ENABLE_JEI_MARQUEE_SELECTION.get(),
                com.huanghuang.rsintegration.config.RSIntegrationConfig.ENABLE_JEI_BOOKMARK_MARQUEE_SELECTION.get(),
                com.huanghuang.rsintegration.config.RSIntegrationConfig.ENABLE_RS_GRID_SWIPE_EXTRACT.get());
    }

    public static void encode(ConfigSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enableMachineGuiTabs);
        buf.writeVarInt(packet.machineTabThreshold);
        buf.writeBoolean(packet.enableAutoEat);
        buf.writeBoolean(packet.enableJei);
        buf.writeBoolean(packet.enableJeiMarquee);
        buf.writeBoolean(packet.enableJeiBookmarkMarquee);
        buf.writeBoolean(packet.enableGridSwipeExtract);
    }

    public static ConfigSyncPacket decode(FriendlyByteBuf buf) {
        return new ConfigSyncPacket(buf.readBoolean(), Math.max(0, Math.min(buf.readVarInt(), 4096)), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean());
    }

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.CONFIG_SYNC, ConfigSyncPacket.class,
                ConfigSyncPacket::encode, ConfigSyncPacket::decode, ConfigSyncPacket::handle,
                java.util.Optional.of(net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    public static void handle(ConfigSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientSyncedConfig.apply(packet));
        ctx.get().setPacketHandled(true);
    }
}
