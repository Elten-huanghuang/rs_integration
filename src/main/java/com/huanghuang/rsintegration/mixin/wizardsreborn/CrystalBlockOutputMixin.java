package com.huanghuang.rsintegration.mixin.wizardsreborn;

import com.huanghuang.rsintegration.mods.wizardsreborn.WRCrystalOutputHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;
import java.util.function.Predicate;

@Pseudo
@Mixin(targets = "mod.maxbogomol.wizards_reborn.common.block.crystal.CrystalBlockEntity", remap = false)
public abstract class CrystalBlockOutputMixin implements WRCrystalOutputHook {
    @Unique @Nullable private Predicate<ItemStack> rsi$crystalOutputReceiver;

    @Override
    public void rsi$setCrystalOutputReceiver(@Nullable Predicate<ItemStack> receiver) {
        rsi$crystalOutputReceiver = receiver;
    }

    @Override
    public boolean rsi$receiveCrystalOutput(ItemStack stack) {
        if (rsi$crystalOutputReceiver == null || !rsi$crystalOutputReceiver.test(stack)) return false;
        rsi$crystalOutputReceiver = null;
        return true;
    }
}
