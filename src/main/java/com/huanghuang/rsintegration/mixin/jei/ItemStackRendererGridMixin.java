package com.huanghuang.rsintegration.mixin.jei;
import java.lang.reflect.Method;

import com.huanghuang.rsintegration.mods.rs.GridItemRenderContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mezz.jei.library.render.ItemStackRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ItemStackRenderer.class, remap = false)
public abstract class ItemStackRendererGridMixin {
    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/world/item/ItemStack;II)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/GuiGraphics;m_280203_(Lnet/minecraft/world/item/ItemStack;II)V"))
    private void rsi$trackDenseIngredientItem(
            GuiGraphics graphics, ItemStack stack, int x, int y,
            Operation<Void> original) {
        boolean tracked = GridItemRenderContext.beginDenseListItem(stack);
        try {
            original.call(graphics, stack, x, y);
        } finally {
            if (tracked) GridItemRenderContext.end();
        }
    }
}
