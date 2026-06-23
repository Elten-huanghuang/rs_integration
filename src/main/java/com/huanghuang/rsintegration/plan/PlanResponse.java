package com.huanghuang.rsintegration.plan;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;

/**
 * Full crafting plan sent from server to client.
 */
public record PlanResponse(
        boolean success,
        String targetName,
        ItemStack targetResult,
        List<PlanStep> steps,
        Map<Item, Availability> materials,
        List<String> missing,
        String recipeId,  // for confirm execution
        @Nullable String executionModTypeId,  // non-null → route to mod-specific handler on confirm
        @Nullable String executionDim,        // machine dimension for mod recipes (ResourceLocation string)
        int executionPosX,
        int executionPosY,
        int executionPosZ
) {
    public record Availability(int needed, int available) {
        public boolean isEnough() { return available >= needed; }
        public boolean isPartial() { return available > 0 && available < needed; }
    }

    /** Backward-compat: no execution routing info (vanilla/generic path). */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<Item, Availability> materials,
                        List<String> missing, String recipeId) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                null, null, 0, 0, 0);
    }
}
