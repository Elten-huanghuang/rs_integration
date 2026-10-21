package com.huanghuang.rsintegration.network.machine.bd;

import com.huanghuang.rsintegration.machine.bd.BeyondDimensionsMachineOperations;
import com.huanghuang.rsintegration.network.binding.BindingSyncRequestRateLimiter;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** BD 独立安装时请求服务端重新发送权威机器绑定快照。 */
public final class BeyondDimensionsBindingSyncRequestPacket {
    public static void encode(BeyondDimensionsBindingSyncRequestPacket packet,
                              FriendlyByteBuf buffer) {}

    public static BeyondDimensionsBindingSyncRequestPacket decode(FriendlyByteBuf buffer) {
        return new BeyondDimensionsBindingSyncRequestPacket();
    }

    public static void handle(BeyondDimensionsBindingSyncRequestPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        if (player != null && !(player instanceof FakePlayer)
                && !BindingSyncRequestRateLimiter.isRateLimited(player.getUUID())) {
            context.enqueueWork(() -> BeyondDimensionsMachineOperations.sendBindingSync(player));
        }
        context.setPacketHandled(true);
    }
}
