package com.huanghuang.rsintegration.compat.emi;

import com.huanghuang.rsintegration.ModType;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

record EmiCraftButtonSpec(
        ResourceLocation recipeId,
        @Nullable ModType modType,
        String tooltipKey,
        Runnable craftAction,
        @Nullable Runnable machineAction
) {}
