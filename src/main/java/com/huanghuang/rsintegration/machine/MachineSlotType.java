package com.huanghuang.rsintegration.machine;

import net.minecraft.network.FriendlyByteBuf;

/** Slot types for Quick machine insert operations. */
public enum MachineSlotType {
    INPUT,
    FUEL;

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(ordinal());
    }

    public static MachineSlotType decode(FriendlyByteBuf buf) {
        int idx = buf.readByte();
        MachineSlotType[] values = values();
        return idx >= 0 && idx < values.length ? values[idx] : INPUT;
    }
}
