package com.huanghuang.rsintegration.network.packet;

import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.storage.StorageReferenceCodec;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Initial/chunked inventory snapshot and ordered incremental JEI updates. */
public final class JeiNetworkInventoryPacket {
    public static final int MAX_ENTRIES = 512;
    public static final int MAX_CHUNKS = 4096;

    private final boolean full;
    private final long epoch;
    private final long sequence;
    private final int chunkIndex;
    private final int chunkCount;
    @Nullable private final StorageReference reference;
    private final List<Entry> entries;

    public JeiNetworkInventoryPacket(boolean full, long epoch, long sequence,
                                     int chunkIndex, int chunkCount,
                                     @Nullable StorageReference reference,
                                     List<Entry> entries) {
        if (epoch < 0 || sequence < 0) throw new IllegalArgumentException("negative JEI sync version");
        if (chunkCount < 1 || chunkCount > MAX_CHUNKS
                || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new IllegalArgumentException("invalid JEI sync chunk");
        }
        if (!full && reference != null) throw new IllegalArgumentException("delta cannot replace storage reference");
        this.full = full;
        this.epoch = epoch;
        this.sequence = sequence;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.reference = reference;
        this.entries = List.copyOf(entries);
    }

    public void encode(FriendlyByteBuf buf) {
        if (entries.size() > MAX_ENTRIES) throw new IllegalArgumentException("too many JEI inventory entries");
        buf.writeBoolean(full);
        buf.writeVarLong(epoch);
        buf.writeVarLong(sequence);
        buf.writeVarInt(chunkIndex);
        buf.writeVarInt(chunkCount);
        buf.writeBoolean(reference != null);
        if (reference != null) buf.writeNbt(StorageReferenceCodec.encode(reference));
        buf.writeVarInt(entries.size());
        for (Entry entry : entries) {
            buf.writeItem(entry.stack().copyWithCount(1));
            buf.writeVarLong(entry.amount());
        }
    }

    public static JeiNetworkInventoryPacket decode(FriendlyByteBuf buf) {
        boolean full = buf.readBoolean();
        long epoch = buf.readVarLong();
        long sequence = buf.readVarLong();
        int chunkIndex = buf.readVarInt();
        int chunkCount = buf.readVarInt();
        if (epoch < 0 || sequence < 0 || chunkCount < 1 || chunkCount > MAX_CHUNKS
                || chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new DecoderException("JEI inventory version/chunk out of bounds");
        }
        StorageReference reference = null;
        if (buf.readBoolean()) {
            reference = StorageReferenceCodec.decode(buf.readNbt())
                    .orElseThrow(() -> new DecoderException("invalid JEI storage reference"));
        }
        if (!full && reference != null) throw new DecoderException("JEI delta contains storage reference");
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_ENTRIES) throw new DecoderException("JEI inventory entry count out of bounds");
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) entries.add(new Entry(buf.readItem(), buf.readVarLong()));
        return new JeiNetworkInventoryPacket(full, epoch, sequence, chunkIndex, chunkCount,
                reference, entries);
    }

    public static void handle(JeiNetworkInventoryPacket packet, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context ctx = supplier.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> JeiNetworkInventoryClientPacketHandler.accept(packet)));
        ctx.setPacketHandled(true);
    }

    public boolean full() { return full; }
    public long epoch() { return epoch; }
    public long sequence() { return sequence; }
    public int chunkIndex() { return chunkIndex; }
    public int chunkCount() { return chunkCount; }
    @Nullable public StorageReference reference() { return reference; }
    public List<Entry> entries() { return entries; }

    /** Wire-neutral item/count pair shared by server sync and the client cache. */
    public record Entry(ItemStack stack, long amount) {
        public Entry {
            if (stack == null || stack.isEmpty()) {
                throw new IllegalArgumentException("JEI inventory entry requires a non-empty stack");
            }
            if (amount < 0) throw new IllegalArgumentException("negative JEI inventory amount");
            stack = stack.copyWithCount(1);
        }
    }

    public static String key(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        var id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        // Backends may return an explicitly-created empty CompoundTag while JEI's
        // ingredient stack has no tag at all. They are the same display variant.
        var tag = stack.getTag();
        return id + "|" + (tag == null || tag.isEmpty() ? "" : tag.toString());
    }
}
