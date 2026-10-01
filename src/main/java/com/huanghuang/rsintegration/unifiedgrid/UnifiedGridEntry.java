package com.huanghuang.rsintegration.unifiedgrid;

import com.refinedmods.refinedstorage.api.storage.tracker.StorageTrackerEntry;
import com.refinedmods.refinedstorage.util.StackUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import java.util.UUID;

/** template 为空表示数量增量；metadata=true 且两个 UUID 均空表示删除。 */
public record UnifiedGridEntry(int serial, int amount, boolean metadata, UUID storedId,
                               UUID craftableId, Object template, StorageTrackerEntry tracker) {
    public UnifiedGridEntry {
        if (serial <= 0 || amount < 0) throw new IllegalArgumentException("无效终端条目");
    }

    public boolean removed() { return metadata && storedId == null && craftableId == null; }

    public void write(FriendlyByteBuf buf, GridResourceKind kind) {
        buf.writeVarInt(serial);
        buf.writeVarInt(amount);
        buf.writeBoolean(metadata);
        if (metadata) {
            writeId(buf, storedId);
            writeId(buf, craftableId);
            if (!removed()) {
                if (kind == GridResourceKind.ITEM) StackUtils.writeItemStack(buf, (ItemStack) template);
                else ((FluidStack) template).writeToPacket(buf);
            }
        }
        buf.writeBoolean(tracker != null);
        if (tracker != null) {
            buf.writeLong(tracker.getTime());
            buf.writeUtf(tracker.getName(), 256);
        }
    }

    public static UnifiedGridEntry read(FriendlyByteBuf buf, GridResourceKind kind) {
        int serial = buf.readVarInt();
        int amount = buf.readVarInt();
        boolean metadata = buf.readBoolean();
        UUID stored = metadata ? readId(buf) : null;
        UUID craftable = metadata ? readId(buf) : null;
        Object template = null;
        if (metadata && (stored != null || craftable != null)) {
            template = kind == GridResourceKind.ITEM ? StackUtils.readItemStack(buf) : FluidStack.readFromPacket(buf);
            if (kind.amount(template) != 1) throw new IllegalArgumentException("显示模板数量必须为 1");
        }
        StorageTrackerEntry tracker = buf.readBoolean() ? new StorageTrackerEntry(buf.readLong(), buf.readUtf(256)) : null;
        return new UnifiedGridEntry(serial, amount, metadata, stored, craftable, template, tracker);
    }

    private static void writeId(FriendlyByteBuf buf, UUID id) {
        buf.writeBoolean(id != null);
        if (id != null) buf.writeUUID(id);
    }

    private static UUID readId(FriendlyByteBuf buf) { return buf.readBoolean() ? buf.readUUID() : null; }
}
