package com.huanghuang.rsintegration.mixin.wizardsreborn;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlockEntity",
        remap = false)
public interface CrystalBlockEntityAccessor {

    @Accessor("startRitual")
    boolean rsi$isRitualStarted();

    @Accessor("cooldown")
    int rsi$getCooldown();

    @Invoker("wissenWandFunction")
    void rsi$invokeWissenWandFunction();
}
