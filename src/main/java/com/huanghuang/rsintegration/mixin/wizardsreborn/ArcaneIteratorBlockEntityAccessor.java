package com.huanghuang.rsintegration.mixin.wizardsreborn;

import mod.maxbogomol.wizards_reborn.common.block.arcane_pedestal.ArcanePedestalBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(targets = "mod.maxbogomol.wizards_reborn.common.block.arcane_iterator.ArcaneIteratorBlockEntity",
        remap = false)
public interface ArcaneIteratorBlockEntityAccessor {

    @Accessor("startCraft")
    boolean rsi$isCraftStarted();

    @Accessor("wissenInCraft")
    int rsi$getWissenInCraft();

    @Accessor("wissenIsCraft")
    int rsi$getWissenIsCraft();

    @Accessor("experienceIsCraft")
    int rsi$getExperienceIsCraft();

    @Accessor("healthIsCraft")
    int rsi$getHealthIsCraft();

    @Accessor("wissen")
    int rsi$getWissen();

    @Invoker("wissenWandFunction")
    void rsi$invokeWissenWandFunction();

    @Invoker("getPedestals")
    List<?> rsi$getPedestals();

    @Invoker("getMainPedestal")
    ArcanePedestalBlockEntity rsi$getMainPedestal();
}
