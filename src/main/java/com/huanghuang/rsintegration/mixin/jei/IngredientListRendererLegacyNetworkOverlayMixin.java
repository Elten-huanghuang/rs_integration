package com.huanghuang.rsintegration.mixin.jei;

import com.huanghuang.rsintegration.client.JeiNetworkOverlayRenderer;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.gui.overlay.IngredientListRenderer;
import mezz.jei.gui.overlay.IngredientListSlot;
import mezz.jei.gui.overlay.elements.IElement;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/** JEI 15.20 renderer package; JEI 15.49 uses the sibling non-legacy mixin. */
@Mixin(value = IngredientListRenderer.class, remap = false)
public abstract class IngredientListRendererLegacyNetworkOverlayMixin {
    @Shadow @Final private List<IngredientListSlot> slots;

    @Inject(method = "render", at = @At("TAIL"))
    private void rsi$renderNetworkAmount(GuiGraphics graphics, CallbackInfo ci) {
        if (!JeiNetworkOverlayRenderer.shouldRender()) return;
        for (IngredientListSlot slot : slots) {
            if (slot.isBlocked() || slot.getOptionalElement().isEmpty()) continue;
            IElement<?> element = slot.getOptionalElement().get();
            ITypedIngredient<?> typed = element.getTypedIngredient();
            if (typed.getType() != VanillaTypes.ITEM_STACK) continue;
            ItemStack stack = (ItemStack) typed.getIngredient();
            var area = slot.getArea();
            JeiNetworkOverlayRenderer.render(graphics, stack,
                    area.getX() + slot.getPadding(), area.getY() + slot.getPadding());
        }
    }
}
