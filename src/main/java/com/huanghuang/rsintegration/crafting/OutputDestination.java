package com.huanghuang.rsintegration.crafting;

import net.minecraft.network.FriendlyByteBuf;

public enum OutputDestination {
    RS_NETWORK,
    PLAYER_INVENTORY;

    public static OutputDestination byOrdinal(int ordinal) {
        return ordinal == PLAYER_INVENTORY.ordinal() ? PLAYER_INVENTORY : RS_NETWORK;
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(ordinal());
    }

    public static OutputDestination read(FriendlyByteBuf buffer) {
        return byOrdinal(buffer.readVarInt());
    }
}
