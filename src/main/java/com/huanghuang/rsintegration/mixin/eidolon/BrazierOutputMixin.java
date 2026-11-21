package com.huanghuang.rsintegration.mixin.eidolon;

import com.huanghuang.rsintegration.crafting.batch.NativeItemOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

@Pseudo
@Mixin(targets = "elucent.eidolon.common.tile.BrazierTileEntity", remap = false)
public abstract class BrazierOutputMixin implements NativeItemOutput {
    @Unique @Nullable private Receiver rsi$outputReceiver;

    @Override public Receiver rsi$getOutputReceiver() { return rsi$outputReceiver; }
    @Override public void rsi$setOutputReceiver(@Nullable Receiver receiver) { rsi$outputReceiver = receiver; }
}
