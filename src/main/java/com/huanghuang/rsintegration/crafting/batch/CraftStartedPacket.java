package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.CraftProgressTracker;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** S2C metadata for one accepted craft. */
public final class CraftStartedPacket {

    private final UUID craftId;
    private final int totalNodes;
    private final boolean graphMode;
    private final ItemStack target;

    public CraftStartedPacket(UUID craftId, int totalNodes, boolean graphMode) {
        this(craftId, totalNodes, graphMode, ItemStack.EMPTY);
    }

    public CraftStartedPacket(UUID craftId, int totalNodes, boolean graphMode, ItemStack target) {
        this.craftId = craftId;
        this.totalNodes = totalNodes;
        this.graphMode = graphMode;
        this.target = target == null ? ItemStack.EMPTY : target.copy();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(craftId);
        buf.writeVarInt(totalNodes);
        buf.writeBoolean(graphMode);
        buf.writeItem(target);
    }

    public static CraftStartedPacket decode(FriendlyByteBuf buf) {
        return new CraftStartedPacket(buf.readUUID(), buf.readVarInt(), buf.readBoolean(), buf.readItem());
    }

    @OnlyIn(Dist.CLIENT)
    public static void handle(CraftStartedPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> CraftProgressTracker.onStarted(packet));
        ctx.get().setPacketHandled(true);
    }

    public UUID craftId() { return craftId; }
    public int totalNodes() { return totalNodes; }
    public boolean graphMode() { return graphMode; }
    public ItemStack target() { return target.copy(); }
}
