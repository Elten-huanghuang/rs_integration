package com.huanghuang.rsintegration.autoeat.network;

import com.huanghuang.rsintegration.autoeat.AutoEatEngine;
import com.huanghuang.rsintegration.autoeat.AutoEatRateLimiter;

import com.huanghuang.rsintegration.autoeat.AutoEatMode;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

public class AutoEatPacket {
    public final AutoEatMode mode;
    public final List<ResourceLocation> selectedItems;

    public AutoEatPacket(AutoEatMode mode, Collection<ResourceLocation> selectedItems) {
        this.mode = mode;
        this.selectedItems = AutoEatSelectionCodec.copy(selectedItems);
    }

    public static void encode(AutoEatPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.mode);
        AutoEatSelectionCodec.write(buf, packet.selectedItems);
    }

    public static AutoEatPacket decode(FriendlyByteBuf buf) {
        AutoEatMode mode = buf.readEnum(AutoEatMode.class);
        return new AutoEatPacket(mode, AutoEatSelectionCodec.read(buf));
    }

    public static void handle(AutoEatPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var sender = ctx.get().getSender();
            if (sender != null && !(sender instanceof FakePlayer)) {
                if (!RSIntegrationConfig.ENABLE_AUTO_EAT.get()) {
                    AutoEatEngine.sendFailure(sender, packet.mode,
                            "rsi.autoeat.error.disabled");
                    return;
                }
                // Throttle: each accepted eat clones the full network storage
                // list + edibility scan. Dedicated limiter (not the GUI-open one)
                // so eating never falsely throttles an unrelated GUI open.
                if (AutoEatRateLimiter.isRateLimited(sender.getUUID())) {
                    return;
                }
                AutoEatEngine.execute(sender, packet.mode, packet.selectedItems);
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
