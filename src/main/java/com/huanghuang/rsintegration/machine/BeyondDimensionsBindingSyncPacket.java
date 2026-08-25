package com.huanghuang.rsintegration.machine;

import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Server snapshot of BD terminal machine bindings for standalone clients. */
public final class BeyondDimensionsBindingSyncPacket {
    private final List<BindingInfo> bindings;

    public BeyondDimensionsBindingSyncPacket(List<BindingInfo> bindings) {
        this.bindings = bindings == null ? List.of() : List.copyOf(bindings);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(bindings.size());
        for (BindingInfo binding : bindings) BindingInfo.encode(buf, binding);
    }

    public static BeyondDimensionsBindingSyncPacket decode(FriendlyByteBuf buf) {
        int count = Math.max(0, Math.min(buf.readVarInt(), 4096));
        List<BindingInfo> bindings = new ArrayList<>(count);
        for (int i = 0; i < count; i++) bindings.add(BindingInfo.decode(buf));
        return new BeyondDimensionsBindingSyncPacket(bindings);
    }

    public static void handle(BeyondDimensionsBindingSyncPacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT, () -> () -> BeyondDimensionsBindingSyncClientPacketHandler.handle(packet)));
        context.setPacketHandled(true);
    }

    List<BindingInfo> bindings() {
        return bindings;
    }
}
