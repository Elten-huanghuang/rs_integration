package com.huanghuang.rsintegration.villager;

import com.huanghuang.rsintegration.villager.client.VillagerRestockClient;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public record VillagerRestockResultPacket(Status status, int inventoryCount, int rsCount, List<Missing> missing) {
    public enum Status { COMPLETE, PARTIAL, NO_NETWORK, NO_PERMISSION, INVALID }
    public record Missing(ItemStack stack, int count) {}
    public static void encode(VillagerRestockResultPacket p, FriendlyByteBuf b) {
        b.writeEnum(p.status); b.writeVarInt(p.inventoryCount); b.writeVarInt(p.rsCount);
        b.writeVarInt(p.missing.size());
        for (Missing m:p.missing) { b.writeItem(m.stack); b.writeVarInt(m.count); }
    }
    public static VillagerRestockResultPacket decode(FriendlyByteBuf b) {
        Status status=b.readEnum(Status.class); int inventory=b.readVarInt(); int rs=b.readVarInt();
        int size=b.readVarInt();
        if (size < 0 || size > 2) throw new IllegalArgumentException("invalid missing item count");
        List<Missing> missing=new ArrayList<>(size);
        for (int i=0;i<size;i++) missing.add(new Missing(b.readItem(), b.readVarInt()));
        return new VillagerRestockResultPacket(status, inventory, rs, List.copyOf(missing));
    }
    public static void handle(VillagerRestockResultPacket p, Supplier<NetworkEvent.Context> cs) {
        NetworkEvent.Context c=cs.get(); c.enqueueWork(() -> VillagerRestockClient.accept(p)); c.setPacketHandled(true);
    }
}
