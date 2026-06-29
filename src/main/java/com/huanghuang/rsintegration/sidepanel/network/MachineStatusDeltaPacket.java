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

    public MachineStatusDeltaPacket(List<Entry> entries) {
        this.entries = entries;
    }

    public List<Entry> entries() { return entries; }

    // ── Encode / Decode ──────────────────────────────────────────

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeResourceLocation(e.dim);
            buf.writeBlockPos(e.pos);
            e.status.encode(buf);
        }
    }

    public static MachineStatusDeltaPacket decode(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Entry> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Entry(
                buf.readResourceLocation(),
                buf.readBlockPos(),
                MachineStatus.decode(buf)
            ));
        }
        return new MachineStatusDeltaPacket(list);
    }

    // ── Handle (client side) ─────────────────────────────────────

    public static void handle(MachineStatusDeltaPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            MachineStatusCache cache = MachineStatusCache.getInstance();
            for (Entry e : packet.entries) {
                cache.put(e.dim, e.pos, e.status);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // ── Entry ────────────────────────────────────────────────────

    public record Entry(ResourceLocation dim, BlockPos pos, MachineStatus status) {}
}
