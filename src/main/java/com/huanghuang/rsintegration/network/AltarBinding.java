package com.huanghuang.rsintegration.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public record AltarBinding(
        ResourceLocation type,
        Component displayName,
        CompoundTag data
) {
    public static final ResourceLocation RS_NETWORK = ResourceLocation.fromNamespaceAndPath("rs_integration", "rs_network");
}
