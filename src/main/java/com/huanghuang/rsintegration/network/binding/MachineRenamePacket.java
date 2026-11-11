package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.machine.bd.BeyondDimensionsMachineOperations;
import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** RS 与 BD 共用的绑定名称修改请求。 */
public record MachineRenamePacket(UUID bookId, MachineEndpointId endpointId, String name) {
    private static boolean registered;

    public MachineRenamePacket {
        name = BindingStorage.normalizeCustomName(name);
    }

    public static void encode(MachineRenamePacket packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.bookId);
        packet.endpointId.encode(buffer);
        buffer.writeUtf(packet.name, BindingStorage.MAX_CUSTOM_NAME_LENGTH);
    }

    public static MachineRenamePacket decode(FriendlyByteBuf buffer) {
        return new MachineRenamePacket(buffer.readUUID(), MachineEndpointId.decode(buffer),
                buffer.readUtf(BindingStorage.MAX_CUSTOM_NAME_LENGTH));
    }

    public static void handle(MachineRenamePacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null && !(player instanceof FakePlayer)) {
            context.enqueueWork(() -> {
                MachineBindingService.rename(player, packet.bookId, packet.endpointId, packet.name);
                BeyondDimensionsMachineOperations.sendBindingSync(player);
            });
        }
        context.setPacketHandled(true);
    }

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(NetworkPacketIds.MACHINE_RENAME,
                MachineRenamePacket.class, MachineRenamePacket::encode,
                MachineRenamePacket::decode, MachineRenamePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }
}
