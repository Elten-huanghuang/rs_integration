package com.huanghuang.rsintegration.mixin.jei;

import mezz.jei.api.gui.builder.IRecipeSlotBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Iron 3.15 的部分炼金锅展示配方会传入容量 0。
 *
 * <p>JEI 要求渲染容量必须大于 0。这里只修正布局参数，
 * FluidStack 的实际数量仍由 Iron 的 recipe 数据决定。</p>
 */
@Mixin(
        targets = "io.redspace.ironsspellbooks.jei.AlchemistCauldronRecipeCategory",
        remap = false
)
public abstract class AlchemistCauldronRecipeCategoryMixin {
    @Redirect(
            method = "setRecipe",
            at = @At(
                    value = "INVOKE",
                    target = "Lmezz/jei/api/gui/builder/IRecipeSlotBuilder;"
                            + "setFluidRenderer(JZII)"
                            + "Lmezz/jei/api/gui/builder/IRecipeSlotBuilder;"
            ),
            remap = false
    )
    private IRecipeSlotBuilder rsi$safeFluidCapacity(
            IRecipeSlotBuilder slot,
            long capacity,
            boolean showCapacity,
            int width,
            int height
    ) {
        return slot.setFluidRenderer(
                Math.max(1L, capacity),
                showCapacity,
                width,
                height
        );
    }
}
