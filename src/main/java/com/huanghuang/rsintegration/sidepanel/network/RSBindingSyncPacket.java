package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.mods.ironfurnaces.client.IronFurnaceJeiRefresh;
import com.huanghuang.rsintegration.util.ModIds;

import com.huanghuang.rsintegration.machine.MachineHub;
import com.huanghuang.rsintegration.sidepanel.data.BindingCache;
import com.huanghuang.rsintegration.sidepanel.data.BindingInfo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client: pushes updated machine bindings after bind/unbind.
 * Sent immediately so the Hub/tabs/tooltip reflect the change without waiting
 * for the next periodic full sync.
 */
public final class RSBindingSyncPacket {

    private final List<BindingInfo> bindings;

    public RSBindingSyncPacket(List<BindingInfo> bindings) {
        this.bindings = bindings;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(bindings.size());
        for (BindingInfo info : bindings) {
            BindingInfo.encode(buf, info);
        }
    }

    public static RSBindingSyncPacket decode(FriendlyByteBuf buf) {
        int count = Math.max(0, Math.min(buf.readVarInt(), 4096));
        List<BindingInfo> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(BindingInfo.decode(buf));
        }
        return new RSBindingSyncPacket(list);
    }

    public static void handle(RSBindingSyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> applyOnClient(packet));
        ctx.get().setPacketHandled(true);
    }

    @OnlyIn(Dist.CLIENT)
    private static void applyOnClient(RSBindingSyncPacket packet) {
        BindingCache.getInstance().updateBindings(packet.bindings);
        MachineHub.refreshMachines();
        if (net.minecraftforge.fml.ModList.get().isLoaded(
                ModIds.JEI)) {
            IronFurnaceJeiRefresh.refreshIfOpen();
        }
    }
}
