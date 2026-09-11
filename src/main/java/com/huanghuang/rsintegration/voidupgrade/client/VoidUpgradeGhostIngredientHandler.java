package com.huanghuang.rsintegration.voidupgrade.client;

import mezz.jei.api.gui.handlers.IGhostIngredientHandler;
import mezz.jei.api.ingredients.ITypedIngredient;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class VoidUpgradeGhostIngredientHandler
        implements IGhostIngredientHandler<VoidUpgradeScreen> {
    @Override
    @SuppressWarnings("unchecked")
    public <I> List<Target<I>> getTargetsTyped(VoidUpgradeScreen screen,
                                                ITypedIngredient<I> ingredient,
                                                boolean doStart) {
        if (!(ingredient.getIngredient() instanceof ItemStack stack) || stack.isEmpty()) return List.of();
        Rect2i area = screen.getGhostIngredientArea();
        return List.of(new Target<>() {
            @Override
            public Rect2i getArea() {
                return area;
            }

            @Override
            public void accept(I value) {
                if (value instanceof ItemStack dropped) screen.acceptGhostIngredient(dropped.copy());
            }
        });
    }

    @Override
    public void onComplete() {}
}
