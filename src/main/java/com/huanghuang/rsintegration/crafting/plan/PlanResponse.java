package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        int executionPosZ,
        List<String> modWarnings,  // mod-specific validation warnings (Goety research/structure, FA essences)
        int repeatCount,
        // ── Embers Alchemy pedestal layout (null/missing when not applicable) ──
        @Nullable int[] embersCode,           // code[i] = aspect index for pedestal i
        @Nullable String[] embersAspectNames,  // translated aspect item names (per code index)
        @Nullable String[] embersInputNames,   // translated input item names (per pedestal)
        long embersSeed,                      // world seed used for calculation (0 = not set)
        boolean embersCanInfer,               // true when a tablet is bound and Mode 1 is available
        boolean embersCodeFromCache,          // true when embersCode was loaded from KnownCodeSavedData (previously inferred)
        boolean executionMachineSupportsGui,  // true when the bound execution machine supports remote GUI
        @Nullable ItemStack baseItem,         // JEI-provided base item for FA ApplyModifierRecipe prefill
        Set<String> boundMachineTypes,        // v3.4 availability passport: modType ids of machines the player has bound
        Map<Item, Integer> leftovers          // overproduction from integer batch rounding: item → surplus count
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
                null, null, 0, 0, 0, Collections.emptyList(), 1,
                null, null, null, 0, false, false, false, null, Collections.emptySet(),
                Collections.emptyMap());
    }

    /** Backward-compat: no mod warnings. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<Item, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId,
                        @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                Collections.emptyList(), 1,
                null, null, null, 0, false, false, false, null, Collections.emptySet(),
                Collections.emptyMap());
    }

    /** Backward-compat: no embers data. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<Item, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId,
                        @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<String> modWarnings, int repeatCount) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount,
                null, null, null, 0, false, false, false, null, Collections.emptySet(),
                Collections.emptyMap());
    }
}
