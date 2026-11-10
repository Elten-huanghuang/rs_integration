package com.huanghuang.rsintegration.crafting.plan;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 稳定材料行编号 + long 数量。分片收齐后一起应用，避免先显示足量再收到缺料行。 */
public record PlanInventoryUpdatePacket(long requestId, long watchId, long sequence,
                                        boolean full, PreviewInventoryModel.Status status,
                                        int part, int parts, List<Entry> entries) {
    // 传输边界沿用计划分页的字节预算；每行固定 4 + 8 + 8 字节，预留包头空间。
    public static final int MAX_ENTRIES = (PlanResponsePagePacket.PAGE_BYTES / 2)
            / (Integer.BYTES + Long.BYTES * 2);
    public static final int MAX_PARTS = (PlanResponsePacket.MAX_DECODE_COUNT + MAX_ENTRIES - 1) / MAX_ENTRIES;

    public record Entry(int row, long available, long missing) {
        public Entry {
            if (row < 0 || row >= PlanResponsePacket.MAX_DECODE_COUNT || available < 0 || missing < 0) {
                throw new IllegalArgumentException("invalid inventory row");
            }
        }
    }

    public PlanInventoryUpdatePacket {
        if (requestId < 0 || watchId <= 0 || sequence <= 0 || status == null
                || parts <= 0 || parts > MAX_PARTS || part < 0 || part >= parts
                || entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("invalid inventory update");
        entries = List.copyOf(entries);
    }

    public static List<PlanInventoryUpdatePacket> split(long requestId, long watchId, long sequence,
                                                       boolean full, PreviewInventoryModel.Status status,
                                                       List<Entry> entries) {
        if (entries.size() > PlanResponsePacket.MAX_DECODE_COUNT) throw new IllegalArgumentException("too many inventory rows");
        int parts = Math.max(1, (entries.size() + MAX_ENTRIES - 1) / MAX_ENTRIES);
        List<PlanInventoryUpdatePacket> result = new ArrayList<>();
        for (int i = 0; i < parts; i++) {
            int from = i * MAX_ENTRIES;
            result.add(new PlanInventoryUpdatePacket(requestId, watchId, sequence, full, status, i, parts,
                    entries.subList(from, Math.min(entries.size(), from + MAX_ENTRIES))));
        }
        return List.copyOf(result);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarLong(requestId);
        buf.writeVarLong(watchId);
        buf.writeVarLong(sequence);
        buf.writeBoolean(full);
        buf.writeEnum(status);
        buf.writeVarInt(part);
        buf.writeVarInt(parts);
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeInt(entry.row());
            buf.writeLong(entry.available());
            buf.writeLong(entry.missing());
        }
    }

    public static PlanInventoryUpdatePacket decode(FriendlyByteBuf buf) {
        try {
            long request = buf.readVarLong();
            long watch = buf.readVarLong();
            long sequence = buf.readVarLong();
            boolean full = buf.readBoolean();
            var status = buf.readEnum(PreviewInventoryModel.Status.class);
            int part = buf.readVarInt();
            int parts = buf.readVarInt();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ENTRIES) throw new DecoderException("invalid inventory row count");
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) entries.add(new Entry(buf.readInt(), buf.readLong(), buf.readLong()));
            if (buf.isReadable()) throw new DecoderException("trailing inventory update data");
            return new PlanInventoryUpdatePacket(request, watch, sequence, full, status, part, parts, entries);
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new DecoderException("invalid inventory update", failure);
        }
    }

    public static void handle(PlanInventoryUpdatePacket packet, Supplier<NetworkEvent.Context> supplier) {
        var context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> PlanInventoryClientHandler.handle(packet)));
        context.setPacketHandled(true);
    }
}
