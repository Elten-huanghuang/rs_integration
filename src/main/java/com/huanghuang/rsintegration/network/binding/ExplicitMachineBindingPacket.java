package com.huanghuang.rsintegration.network.binding;

import com.huanghuang.rsintegration.network.packet.NetworkHandler;
import com.huanghuang.rsintegration.network.packet.NetworkPacketIds;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.InteractionHand;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Client request sent by the configurable Alt+right-click machine-binding chord. */
public record ExplicitMachineBindingPacket(BlockPos pos, InteractionHand hand) {
    private static boolean registered;
    private static final long DUPLICATE_WINDOW_NANOS = 250_000_000L;
    private static final ConcurrentHashMap<UUID, RecentRequest> RECENT_REQUESTS =
            new ConcurrentHashMap<>();

    private record RecentRequest(BlockPos pos, InteractionHand hand, long timeNanos) {}

    public static void encode(ExplicitMachineBindingPacket packet, FriendlyByteBuf buf) {
        buf.writeBlockPos(packet.pos());
        buf.writeEnum(packet.hand());
    }

    public static ExplicitMachineBindingPacket decode(FriendlyByteBuf buf) {
        return new ExplicitMachineBindingPacket(buf.readBlockPos(), buf.readEnum(InteractionHand.class));
    }

    public static void handle(ExplicitMachineBindingPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            var player = context.getSender();
            if (player == null || packet.pos().distSqr(player.blockPosition()) > 64.0 * 64.0) return;
            long now = System.nanoTime();
            RecentRequest previous = RECENT_REQUESTS.put(
                    player.getUUID(), new RecentRequest(packet.pos(), packet.hand(), now));
            if (previous != null
                    && previous.pos().equals(packet.pos())
                    && previous.hand() == packet.hand()
                    && now - previous.timeNanos() <= DUPLICATE_WINDOW_NANOS) {
                return;
            }
            ExplicitMachineBindingPacketValidator.bind(player, packet.pos(), packet.hand());
        });
        context.setPacketHandled(true);
    }

    public static void register() {
        if (registered) return;
        NetworkHandler.CHANNEL.registerMessage(
                NetworkPacketIds.EXPLICIT_MACHINE_BINDING,
                ExplicitMachineBindingPacket.class,
                ExplicitMachineBindingPacket::encode,
                ExplicitMachineBindingPacket::decode,
                ExplicitMachineBindingPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }
}
