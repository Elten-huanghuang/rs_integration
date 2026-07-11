package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public class AutoEatPacket {
    public final AutoEatMode mode;
    @Nullable
    public final ResourceLocation selectedItem;

    public AutoEatPacket(AutoEatMode mode, @Nullable ResourceLocation selectedItem) {
        this.mode = mode;
        this.selectedItem = selectedItem;
    }

    public static void encode(AutoEatPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.mode);
        buf.writeBoolean(packet.selectedItem != null);
        if (packet.selectedItem != null) {
            buf.writeResourceLocation(packet.selectedItem);
        }
    }

    public static AutoEatPacket decode(FriendlyByteBuf buf) {
        AutoEatMode mode = buf.readEnum(AutoEatMode.class);
        ResourceLocation item = buf.readBoolean() ? buf.readResourceLocation() : null;
        return new AutoEatPacket(mode, item);
    }

    public static void handle(AutoEatPacket packet, java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null && !(sender instanceof net.minecraftforge.common.util.FakePlayer)) {
                com.huanghuang.rsintegration.autoeat.AutoEatEngine.execute(sender, packet.mode, packet.selectedItem);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
