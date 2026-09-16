package com.huanghuang.rsintegration.anvilmemory;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

public record AnvilMemorySyncPacket(String adapterId, Status status, int inventoryCount,
                                    int rsCount, ItemStack missingStack, int missingCount,
                                    List<ItemStack> memories, List<Boolean> locked) {
    public enum Status { SYNC, COMPLETE, PARTIAL, NO_NETWORK, NO_PERMISSION, OCCUPIED, REJECTED, SWAPPED, INVALID }

    public AnvilMemorySyncPacket(String adapterId, Status status, int inventoryCount,
                                 int rsCount, ItemStack missingStack, int missingCount,
                                 List<ItemStack> memories) {
        this(adapterId, status, inventoryCount, rsCount, missingStack, missingCount,
                memories, Collections.nCopies(memories.size(), false));
    }

    public AnvilMemorySyncPacket {
        memories = List.copyOf(memories);
        locked = locked == null || locked.size() != memories.size()
                ? Collections.nCopies(memories.size(), false) : List.copyOf(locked);
    }

    public static AnvilMemorySyncPacket sync(String id, List<ItemStack> memories) {
        return result(id, Status.SYNC, 0, 0, ItemStack.EMPTY, 0, memories);
    }

    public static AnvilMemorySyncPacket syncEntries(String id,
                                                     List<AnvilMemoryData.MemoryEntry> entries) {
        return resultEntries(id, Status.SYNC, 0, 0, ItemStack.EMPTY, 0, entries);
    }

    public static AnvilMemorySyncPacket invalid(String id) {
        return result(id, Status.INVALID, 0, 0, ItemStack.EMPTY, 0, List.of());
    }

    public static AnvilMemorySyncPacket result(String id, Status status, int inventory, int rs,
                                                ItemStack missing, int missingCount,
                                                List<ItemStack> memories) {
        return new AnvilMemorySyncPacket(id, status, inventory, rs, missing.copyWithCount(
                missing.isEmpty() ? 0 : 1), missingCount, memories);
    }

    public static AnvilMemorySyncPacket resultEntries(String id, Status status, int inventory, int rs,
                                                       ItemStack missing, int missingCount,
                                                       List<AnvilMemoryData.MemoryEntry> entries) {
        List<ItemStack> memories = new ArrayList<>(entries.size());
        List<Boolean> locked = new ArrayList<>(entries.size());
        for (AnvilMemoryData.MemoryEntry entry : entries) {
            memories.add(entry.stack());
            locked.add(entry.locked());
        }
        return new AnvilMemorySyncPacket(id, status, inventory, rs, missing.copyWithCount(
                missing.isEmpty() ? 0 : 1), missingCount, memories, locked);
    }

    public static void encode(AnvilMemorySyncPacket packet, FriendlyByteBuf buf) {
        buf.writeUtf(packet.adapterId, 64); buf.writeEnum(packet.status);
        buf.writeVarInt(packet.inventoryCount); buf.writeVarInt(packet.rsCount);
        buf.writeItem(packet.missingStack); buf.writeVarInt(packet.missingCount);
        buf.writeVarInt(packet.memories.size());
        for (int i = 0; i < packet.memories.size(); i++) {
            buf.writeItem(packet.memories.get(i));
            buf.writeBoolean(packet.locked.get(i));
        }
    }

    public static AnvilMemorySyncPacket decode(FriendlyByteBuf buf) {
        String id = buf.readUtf(64); Status status = buf.readEnum(Status.class);
        int inventory = buf.readVarInt(); int rs = buf.readVarInt();
        ItemStack missing = buf.readItem(); int missingCount = buf.readVarInt();
        int size = buf.readVarInt();
        if (inventory < 0 || rs < 0 || missingCount < 0 || size < 0 || size > AnvilMemoryData.LIMIT) {
            throw new IllegalArgumentException("Invalid anvil memory sync packet");
        }
        List<ItemStack> memories = new ArrayList<>(size);
        List<Boolean> locked = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            memories.add(buf.readItem().copyWithCount(1));
            locked.add(buf.readBoolean());
        }
        return new AnvilMemorySyncPacket(id, status, inventory, rs, missing, missingCount,
                memories, locked);
    }

    public static void handle(AnvilMemorySyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> AnvilMemoryClient.accept(packet)));
        context.setPacketHandled(true);
    }
}
