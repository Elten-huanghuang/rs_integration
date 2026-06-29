package com.huanghuang.rsintegration.machine;

import net.minecraft.network.FriendlyByteBuf;

/** Live status of a bound machine, pushed from server to client. */
public enum MachineState {
    /** Machine is idle, input slot empty or ready to accept items. */
    IDLE,
    /** Machine is actively processing (furnace burning, etc.). */
    WORKING,
    /** Machine has finished and output is available for collection. */
    HAS_OUTPUT,
    /** Chunk unloaded, machine destroyed, or type does not support status reads. */
    UNKNOWN;

    public void encode(FriendlyByteBuf buf) {
        buf.writeByte(ordinal());
    }

    public static MachineState decode(FriendlyByteBuf buf) {    
        int idx = buf.readByte();
        MachineState[] values = values();
        return idx >= 0 && idx < values.length ? values[idx] : UNKNOWN;
    }
}
