package com.huanghuang.rsintegration.crafting;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

/** Client-selected routing policy for the target recipe's bound machine. */
public enum MachineSelectionMode {
    AUTO,
    PREFERRED,
    EXCLUSIVE;

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(ordinal());
    }

    public static MachineSelectionMode read(FriendlyByteBuf buf) {
        int ordinal = buf.readVarInt();
        MachineSelectionMode[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new DecoderException("Invalid machine selection mode: " + ordinal);
        }
        return values[ordinal];
    }
}
