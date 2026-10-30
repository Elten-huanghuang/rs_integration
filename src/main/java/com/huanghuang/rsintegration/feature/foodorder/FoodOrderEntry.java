package com.huanghuang.rsintegration.feature.foodorder;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

/** 列表中的一行：目标食物、状态、可提交的配方，以及详情文本。 */
public record FoodOrderEntry(
        ItemStack stack,
        FoodOrderKind kind,
        @Nullable ResourceLocation recipeId,
        Component detail
) {

    public FoodOrderEntry {
        stack = stack == null ? ItemStack.EMPTY : stack.copy();
        kind = kind == null ? FoodOrderKind.WAITING : kind;
        detail = detail == null ? Component.empty() : detail;
    }

    public boolean orderable() {
        return kind.orderable() && recipeId != null;
    }
}
