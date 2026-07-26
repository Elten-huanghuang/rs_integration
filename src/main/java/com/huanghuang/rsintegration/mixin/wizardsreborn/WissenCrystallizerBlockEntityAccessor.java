package com.huanghuang.rsintegration.mixin.wizardsreborn;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "mod.maxbogomol.wizards_reborn.common.block.wissen_crystallizer.WissenCrystallizerBlockEntity",
        remap = false)
public interface WissenCrystallizerBlockEntityAccessor {

    @Accessor("startCraft")
    boolean rsi$isCraftStarted();

    @Accessor("wissenInCraft")
    int rsi$getWissenInCraft();

    @Accessor("wissen")
    int rsi$getWissen();

    @Invoker("wissenWandFunction")
    void rsi$invokeWissenWandFunction();
}
