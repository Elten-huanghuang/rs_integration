package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.client.recipe.JeiNetworkOverlayRenderer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.ingredients.IngredientListRenderer;
import mezz.jei.gui.overlay.ingredients.IngredientListSlot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(value = IngredientListRenderer.class, remap = false)
public abstract class IngredientListRendererNetworkOverlayMixin {
    @Shadow @Final private List<IngredientListSlot> slots;

    // JEI 15.74 在原方法中复用了 this 所在的局部变量槽，TAIL 回调会触发 VerifyError。
    // 包装原方法可保留接收者和参数，并保证数量仍在 JEI 原生绘制之后叠加。
    @WrapMethod(method = "render")
    private void rsi$renderNetworkAmount(GuiGraphics graphics, Operation<Void> original) {
        original.call(graphics);
        if (!JeiNetworkOverlayRenderer.shouldRender()) return;
        for (IngredientListSlot slot : slots) {
            if (slot.isBlocked() || slot.getOptionalElement().isEmpty()) continue;
            IElement<?> element = slot.getOptionalElement().get();
            ITypedIngredient<?> typed = element.getTypedIngredient();
            if (typed.getType() != VanillaTypes.ITEM_STACK) continue;
            ItemStack stack = (ItemStack) typed.getIngredient();
            var area = slot.getArea();
            int x = area.getX() + slot.getPadding();
            int y = area.getY() + slot.getPadding();
            JeiNetworkOverlayRenderer.render(graphics, stack, x, y);
        }
    }
}
