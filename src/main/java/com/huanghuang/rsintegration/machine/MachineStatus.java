package com.huanghuang.rsintegration.machine;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * Snapshot of a machine's live state, pushed S→C via {@code MachineStatusDeltaPacket}.
 * Only sent for {@link MachineInteractType#QUICK} machines.
 */
public record MachineStatus(
    MachineState state,
    int progress,        // current progress ticks (0..maxProgress)
    int maxProgress,     // total ticks needed; 0 if unknown
    ItemStack inputItem, // snapshot of input slot
    ItemStack outputItem,// snapshot of output slot
    ItemStack fuelItem   // snapshot of fuel slot
) {
    public static final MachineStatus UNKNOWN = new MachineStatus(
        MachineState.UNKNOWN, 0, 0, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);

    /** Progress as a 0.0–1.0 fraction, or 0 if unknown. */
    public float progressFraction() {
        if (maxProgress <= 0) return 0f;
        return Math.min(1f, (float) progress / (float) maxProgress);
    }

    // ── Network ──────────────────────────────────────────────────

    public void encode(FriendlyByteBuf buf) {
        state.encode(buf);
        buf.writeVarInt(progress);
        buf.writeVarInt(maxProgress);
        buf.writeItem(inputItem);
        buf.writeItem(outputItem);
        buf.writeItem(fuelItem);
    }

    public static MachineStatus decode(FriendlyByteBuf buf) {
        return new MachineStatus(
            MachineState.decode(buf),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readItem(),
            buf.readItem(),
            buf.readItem()
        );
    }

    // ── Equality (record default, but ItemStack.equals is strict) ──

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MachineStatus that)) return false;
        return state == that.state
            && progress == that.progress
            && maxProgress == that.maxProgress
            && ItemStack.isSameItemSameTags(inputItem, that.inputItem)
            && inputItem.getCount() == that.inputItem.getCount()
            && ItemStack.isSameItemSameTags(outputItem, that.outputItem)
            && outputItem.getCount() == that.outputItem.getCount()
            && ItemStack.isSameItemSameTags(fuelItem, that.fuelItem)
            && fuelItem.getCount() == that.fuelItem.getCount();
    }

    @Override
    public int hashCode() {
        int h = state.hashCode();
        h = 31 * h + progress;
        h = 31 * h + maxProgress;
        h = 31 * h + stackHash(inputItem);
        h = 31 * h + stackHash(outputItem);
        h = 31 * h + stackHash(fuelItem);
        return h;
    }

    private static int stackHash(ItemStack s) {
        if (s.isEmpty()) return 0;
        return 31 * s.getItem().hashCode() + s.getCount();
    }
}
