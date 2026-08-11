package com.huanghuang.rsintegration.mixin.refinedstorage;

import com.huanghuang.rsintegration.mods.rs.GridItemRenderContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.refinedmods.refinedstorage.screen.BaseScreen;
import com.refinedmods.refinedstorage.screen.grid.stack.ItemGridStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ItemGridStack.class, remap = false)
public abstract class ItemGridStackRenderMixin {
    @WrapOperation(
            method = "draw",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/refinedmods/refinedstorage/screen/BaseScreen;renderItem(Lnet/minecraft/client/gui/GuiGraphics;IILnet/minecraft/world/item/ItemStack;ZLjava/lang/String;I)V"))
    private void rsi$trackDenseGridItemRender(
            BaseScreen<?> screen, GuiGraphics graphics, int x, int y,
            ItemStack stack, boolean decorations, String quantity, int color,
            Operation<Void> original) {
        GridItemRenderContext.begin(stack);
        try {
            original.call(screen, graphics, x, y, stack, decorations, quantity, color);
        } finally {
            GridItemRenderContext.end();
        }
    }
}
