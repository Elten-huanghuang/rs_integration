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
    public final boolean enableMachineGuiTabs;
    public final int machineTabThreshold;

    public ConfigSyncPacket(boolean enableMachineGuiTabs, int machineTabThreshold) {
        this.enableMachineGuiTabs = enableMachineGuiTabs;
        this.machineTabThreshold = machineTabThreshold;
    }

    public static void encode(ConfigSyncPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.enableMachineGuiTabs);
        buf.writeVarInt(packet.machineTabThreshold);
    }

    public static ConfigSyncPacket decode(FriendlyByteBuf buf) {
        return new ConfigSyncPacket(buf.readBoolean(), buf.readVarInt());
    }

    public static void handle(ConfigSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> ClientSyncedConfig.apply(packet));
        ctx.get().setPacketHandled(true);
    }
}
