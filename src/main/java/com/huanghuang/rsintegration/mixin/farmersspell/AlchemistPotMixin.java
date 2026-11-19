package com.huanghuang.rsintegration.mixin.farmersspell;

import com.huanghuang.rsintegration.mods.farmersdelight.FarmersSpellPotAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraftforge.items.ItemStackHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "com.chenjdy.farmers_spell.block.entity.AlchemistPotBlockEntity", remap = false)
public abstract class AlchemistPotMixin implements FarmersSpellPotAccess {
    @Unique private boolean rsi$managedCooking;

    @Override
    @Accessor("inventory")
    public abstract ItemStackHandler rsi$foodInventory();

    @Override
    @Accessor("scrollHandler")
    public abstract ItemStackHandler rsi$scrollInventory();

    @Override
    public void rsi$setManagedCooking(boolean managed) {
        rsi$managedCooking = managed;
    }

    @WrapMethod(method = {"moveMealToOutput", "useStoredContainersOnMeal"})
    private void rsi$finishMealTransfer(Operation<Void> original) {
        if (!rsi$managedCooking) {
            original.call();
            return;
        }
        ItemStackHandler inventory = rsi$foodInventory();
        int previousMeal = inventory.getStackInSlot(6).getCount();
        boolean emptyOutput = inventory.getStackInSlot(8).isEmpty();
        original.call();
        if (previousMeal > 0 && emptyOutput) FarmersSpellPotAccess.finishTransfer(inventory, previousMeal);
    }
}
