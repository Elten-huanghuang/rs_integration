package com.huanghuang.rsintegration.sidepanel.network;

import com.huanghuang.rsintegration.machine.MachineStatus;
import com.huanghuang.rsintegration.sidepanel.data.MachineStatusCache;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Server → Client: pushes changed machine statuses for Quick-type bound machines.
 * Sent at tick-end (every 40 ticks), only includes machines whose status has changed.
 */
public final class MachineStatusDeltaPacket {

    private final List<Entry> entries;
    private final long sequence;

    public MachineStatusDeltaPacket(List<Entry> entries) {
        this(entries, 0L);
    }

    public MachineStatusDeltaPacket(List<Entry> entries, long sequence) {
        this.entries = entries;
        this.sequence = sequence;
    }

    public List<Entry> entries() { return entries; }
    public long sequence() { return sequence; }

    // ── Encode / Decode ──────────────────────────────────────────

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeResourceLocation(e.dim);
            buf.writeBlockPos(e.pos);
            buf.writeBoolean(e.removed);
            if (!e.removed) e.status.encode(buf);
        }
        buf.writeVarLong(sequence);
    }

    public static MachineStatusDeltaPacket decode(FriendlyByteBuf buf) {
        int count = Math.max(0, Math.min(buf.readVarInt(), 4096));
        List<Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ResourceLocation dim = buf.readResourceLocation();
            BlockPos pos = buf.readBlockPos();
            boolean removed = buf.readBoolean();
            list.add(new Entry(dim, pos, removed ? MachineStatus.UNKNOWN : MachineStatus.decode(buf), removed));
        }
        return new MachineStatusDeltaPacket(list, buf.readableBytes() > 0 ? buf.readVarLong() : 0L);
    }

    // ── Handle (client side) ─────────────────────────────────────

    public static void handle(MachineStatusDeltaPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            MachineStatusCache cache = MachineStatusCache.getInstance();
            if (!cache.acceptSequence(packet.sequence)) return;
            for (Entry e : packet.entries) {
                if (e.removed) cache.remove(e.dim, e.pos);
                else cache.put(e.dim, e.pos, e.status);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // ── Entry ────────────────────────────────────────────────────

    public record Entry(ResourceLocation dim, BlockPos pos, MachineStatus status, boolean removed) {
        public Entry(ResourceLocation dim, BlockPos pos, MachineStatus status) {
            this(dim, pos, status, false);
        }

        public static Entry removed(ResourceLocation dim, BlockPos pos) {
            return new Entry(dim, pos, MachineStatus.UNKNOWN, true);
        }
    }
}
