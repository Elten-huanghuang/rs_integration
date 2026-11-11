package com.huanghuang.rsintegration.mods.rs.monitor;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.progress.CraftMaterialView;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;
import java.util.function.Supplier;

public record RsMonitorMaterialsPacket(int containerId, UUID session, UUID craft, long revision, long baseRevision,
                                       boolean full, List<CraftMaterialView.Row> changed,
                                       List<MaterialKey> removed, boolean complete) {
    public RsMonitorMaterialsPacket {
        changed = List.copyOf(changed);
        removed = List.copyOf(removed);
        if (revision <= 0 || baseRevision < 0 || revision <= baseRevision
                || changed.size() > CraftMaterialView.MAX_ROWS || removed.size() > CraftMaterialView.MAX_ROWS) {
            throw new IllegalArgumentException("invalid material update");
        }
        var keys = new HashSet<MaterialKey>();
        for (var row : changed) if (!keys.add(row.material())) throw new IllegalArgumentException("duplicate material");
        for (var key : removed) if (!keys.add(key)) throw new IllegalArgumentException("conflicting material update");
        if (full && !removed.isEmpty()) throw new IllegalArgumentException("removals in full material update");
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(containerId);
        buf.writeUUID(session);
        buf.writeUUID(craft);
        buf.writeVarLong(revision);
        buf.writeVarLong(baseRevision);
        buf.writeBoolean(full);
        buf.writeBoolean(complete);
        buf.writeVarInt(changed.size());
        for (var row : changed) {
            InkFluidSupport.writePacket(buf, row.material().toStack(1));
            buf.writeVarLong(row.available());
            buf.writeVarLong(row.processing());
            buf.writeVarLong(row.crafting());
            buf.writeVarLong(row.scheduled());
            buf.writeVarLong(row.toExtract());
            buf.writeVarLong(row.produced());
            buf.writeBoolean(row.uncertain());
        }
        buf.writeVarInt(removed.size());
        for (var key : removed) InkFluidSupport.writePacket(buf, key.toStack(1));
    }

    public static RsMonitorMaterialsPacket decode(FriendlyByteBuf buf) {
        int menu = buf.readVarInt();
        UUID session = buf.readUUID(), craft = buf.readUUID();
        long revision = buf.readVarLong(), base = buf.readVarLong();
        boolean full = buf.readBoolean(), complete = buf.readBoolean();
        int count = count(buf);
        var rows = new ArrayList<CraftMaterialView.Row>(count);
        for (int i = 0; i < count; i++) rows.add(new CraftMaterialView.Row(key(buf), buf.readVarLong(),
                buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), buf.readBoolean()));
        count = count(buf);
        var removed = new ArrayList<MaterialKey>(count);
        for (int i = 0; i < count; i++) removed.add(key(buf));
        return new RsMonitorMaterialsPacket(menu, session, craft, revision, base, full, rows, removed, complete);
    }

    private static MaterialKey key(FriendlyByteBuf buf) {
        var stack = InkFluidSupport.readPacket(buf);
        if (stack.isEmpty()) throw new IllegalArgumentException("empty monitor material");
        return MaterialKey.of(stack);
    }
    private static int count(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > CraftMaterialView.MAX_ROWS) throw new IllegalArgumentException("invalid material count");
        return count;
    }

    public static void handle(RsMonitorMaterialsPacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> RsMonitorClient.accept(packet)));
        context.setPacketHandled(true);
    }
}
