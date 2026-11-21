package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/** 收货回调只挂在任务实际投料的方块实体上。 */
public interface NativeItemOutput {
    @FunctionalInterface
    interface Receiver {
        boolean receive(Object recipe, ItemStack stack, boolean primary);
    }

    @Nullable Receiver rsi$getOutputReceiver();

    void rsi$setOutputReceiver(@Nullable Receiver receiver);

    default boolean rsi$receiveNativeOutput(Object recipe, ItemStack stack, boolean primary) {
        Receiver receiver = rsi$getOutputReceiver();
        return receiver != null && receiver.receive(recipe, stack, primary);
    }

    default void rsi$clearOutputReceiver(Receiver receiver) {
        if (rsi$getOutputReceiver() == receiver) rsi$setOutputReceiver(null);
    }
}
