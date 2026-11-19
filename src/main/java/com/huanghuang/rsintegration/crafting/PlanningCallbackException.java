package com.huanghuang.rsintegration.crafting;

import net.minecraft.resources.ResourceLocation;

/** 服务端规划探针未能给出答案，当前试探分支只能标记为未知。 */
public final class PlanningCallbackException extends RuntimeException {
    private final ResourceLocation recipe;

    PlanningCallbackException(ResourceLocation recipe, Throwable cause) {
        super(recipe.toString(), cause);
        this.recipe = recipe;
    }

    public ResourceLocation recipe() { return recipe; }
}
