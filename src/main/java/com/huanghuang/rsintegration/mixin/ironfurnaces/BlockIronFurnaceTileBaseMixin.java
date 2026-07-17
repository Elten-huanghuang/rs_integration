package com.huanghuang.rsintegration.mixin.ironfurnaces;

import com.huanghuang.rsintegration.mods.ironfurnaces.IronFurnaceBindingUpdater;
import ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BlockIronFurnaceTileBase.class, remap = false)
public abstract class BlockIronFurnaceTileBaseMixin {

    @Unique
    private RecipeType<? extends AbstractCookingRecipe> rsi$previousRecipeType;

    @Inject(method = "checkRecipeType", at = @At("HEAD"))
    private void rsi$captureRecipeType(CallbackInfo ci) {
        rsi$previousRecipeType = ((BlockIronFurnaceTileBase) (Object) this).recipeType;
    }

    @Inject(method = "checkRecipeType", at = @At("RETURN"))
    private void rsi$updateBindingMode(CallbackInfo ci) {
        BlockIronFurnaceTileBase furnace = (BlockIronFurnaceTileBase) (Object) this;
        IronFurnaceBindingUpdater.onRecipeTypeChanged(
                furnace, rsi$previousRecipeType, furnace.recipeType);
    }
}
