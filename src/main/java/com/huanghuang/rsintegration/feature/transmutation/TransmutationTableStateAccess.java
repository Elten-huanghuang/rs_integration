package com.huanghuang.rsintegration.feature.transmutation;

import net.minecraft.world.item.ItemStack;

import java.util.UUID;

public interface TransmutationTableStateAccess {
    UUID rsIntegration$getRerollPlayer();

    ItemStack rsIntegration$getPossibility(int index);
}
