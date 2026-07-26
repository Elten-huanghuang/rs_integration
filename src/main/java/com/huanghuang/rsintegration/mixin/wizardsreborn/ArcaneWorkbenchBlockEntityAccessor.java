package com.huanghuang.rsintegration.mixin.wizardsreborn;

import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "mod.maxbogomol.wizards_reborn.common.block.arcane_workbench.ArcaneWorkbenchBlockEntity",
        remap = false)
public interface ArcaneWorkbenchBlockEntityAccessor {

    @Accessor("itemHandler")
    ItemStackHandler rsi$getItemHandler();

    @Accessor("itemOutputHandler")
    ItemStackHandler rsi$getItemOutputHandler();

    @Accessor("startCraft")
    boolean rsi$isCraftStarted();

    @Accessor("wissenInCraft")
    int rsi$getWissenInCraft();

    @Accessor("wissen")
    int rsi$getWissen();

    @Invoker("wissenWandFunction")
    void rsi$invokeWissenWandFunction();
}
