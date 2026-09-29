package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.network.SmithingJeiTransferHandler;
import com.refinedmods.refinedstorage.container.GridContainerMenu;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 阻止 RS 通用 JEI 转移处理器把锻造配方写入 3x3 合成矩阵。 */
@Mixin(targets = "com.refinedmods.refinedstorage.integration.jei.GridRecipeTransferHandler", remap = false)
public abstract class GridRecipeTransferSmithingMixin {
    @Inject(method = "transferRecipe", at = @At("HEAD"), cancellable = true, remap = false)
    private void rsi$handleSmithingRecipe(GridContainerMenu container, Object recipe,
                                          IRecipeSlotsView recipeSlots, Player player,
                                          boolean maxTransfer, boolean doTransfer,
                                          CallbackInfoReturnable<IRecipeTransferError> cir) {
        if (!(recipe instanceof SmithingRecipe) && !(recipe instanceof StonecutterRecipe)) return;
        cir.setReturnValue(SmithingJeiTransferHandler.intercept(container, recipe,
                recipeSlots, player, maxTransfer, doTransfer));
    }
}
