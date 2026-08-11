package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.mods.rs.GridItemRenderContext;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.rendering.BatchRenderElement;
import mezz.jei.gui.overlay.IngredientListRenderer;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

@Mixin(value = IngredientListRenderer.class, remap = false)
public abstract class IngredientListRendererGridMixin {
    @WrapOperation(
            method = "renderBatch",
            at = @At(
                    value = "INVOKE",
                    target = "Lmezz/jei/common/util/SafeIngredientUtil;renderBatch(Lnet/minecraft/client/gui/GuiGraphics;Lmezz/jei/api/ingredients/IIngredientType;Lmezz/jei/api/ingredients/IIngredientRenderer;Ljava/util/List;)V"))
    private <T> void rsi$markDenseIngredientList(
            GuiGraphics graphics, IIngredientType<T> type,
            IIngredientRenderer<T> renderer, List<BatchRenderElement<T>> elements,
            Operation<Void> original) {
        GridItemRenderContext.beginDenseList();
        try {
            original.call(graphics, type, renderer, elements);
        } finally {
            GridItemRenderContext.endDenseList();
        }
    }
}
