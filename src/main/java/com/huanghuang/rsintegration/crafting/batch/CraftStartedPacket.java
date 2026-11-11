package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.huanghuang.rsintegration.crafting.progress.CraftOutputProgress;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

/** S2C metadata for one accepted craft. */
public final class CraftStartedPacket {

    private final UUID craftId;
    private final int totalNodes;
    private final boolean graphMode;
    private final ItemStack target;
    private final CraftOutputProgress outputProgress;
    private final long previewRequestId;

    public CraftStartedPacket(UUID craftId, int totalNodes, boolean graphMode) {
        this(craftId, totalNodes, graphMode, ItemStack.EMPTY);
    }

    public CraftStartedPacket(UUID craftId, int totalNodes, boolean graphMode, ItemStack target) {
        this(craftId, totalNodes, graphMode, target, CraftOutputProgress.UNKNOWN);
    }

    public CraftStartedPacket(UUID craftId, int totalNodes, boolean graphMode, ItemStack target,
                              CraftOutputProgress outputProgress) {
        this(craftId, totalNodes, graphMode, target, outputProgress, 0L);
    }

    public CraftStartedPacket(UUID craftId, int totalNodes, boolean graphMode, ItemStack target,
                              CraftOutputProgress outputProgress, long previewRequestId) {
        if (previewRequestId < 0L) throw new IllegalArgumentException("negative preview request");
        this.craftId = craftId;
        this.totalNodes = totalNodes;
        this.graphMode = graphMode;
        this.target = target == null ? ItemStack.EMPTY : target.copy();
        this.outputProgress = outputProgress == null ? CraftOutputProgress.UNKNOWN : outputProgress;
        this.previewRequestId = previewRequestId;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(craftId);
        buf.writeVarInt(totalNodes);
        buf.writeBoolean(graphMode);
        InkFluidSupport.writePacket(buf, target);
        buf.writeVarLong(previewRequestId);
        // 产出数量是可选包尾，不能在其后追加其他字段。
        CraftProgressPacket.writeOutputProgress(buf, outputProgress);
    }

    public static CraftStartedPacket decode(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        int nodes = buf.readVarInt();
        boolean graph = buf.readBoolean();
        ItemStack target = InkFluidSupport.readPacket(buf);
        long preview = buf.readVarLong();
        return new CraftStartedPacket(id, nodes, graph, target, CraftProgressPacket.readOutputProgress(buf), preview);
    }

    public static void handle(CraftStartedPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> CraftProgressClientPacketHandler.onStarted(packet)));
        ctx.get().setPacketHandled(true);
    }

    public UUID craftId() { return craftId; }
    public int totalNodes() { return totalNodes; }
    public boolean graphMode() { return graphMode; }
    public ItemStack target() { return target.copy(); }
    public CraftOutputProgress outputProgress() { return outputProgress; }
    public long previewRequestId() { return previewRequestId; }
}
