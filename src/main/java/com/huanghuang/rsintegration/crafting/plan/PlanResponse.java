package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.huanghuang.rsintegration.storage.StorageNetworkDescriptor;
import com.huanghuang.rsintegration.storage.StorageReference;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        Map<IngredientKey, Availability> materials,
        List<String> missing,
        String recipeId,  // for confirm execution
        @Nullable String executionModTypeId,  // non-null → route to mod-specific handler on confirm
        @Nullable String executionDim,        // machine dimension for mod recipes (ResourceLocation string)
        int executionPosX,
        int executionPosY,
        int executionPosZ,
        // Mod-specific validation warnings (Goety research/structure, FA essences).
        // MUST stay unresolved Components: a dedicated server never loads
        // assets/rs_integration/lang/*.json, so rendering these to String
        // server-side yields raw translation keys on the client.
        List<Component> modWarnings,
        int repeatCount,
        // ── Embers Alchemy pedestal layout (null/missing when not applicable) ──
        @Nullable int[] embersCode,           // code[i] = aspect index for pedestal i
        @Nullable Component[] embersAspectNames, // aspect item names (per code index), resolved client-side
        @Nullable Component[] embersInputNames,  // input item names (per pedestal), resolved client-side
        long embersSeed,                      // world seed used for calculation (0 = not set)
        boolean embersCanInfer,               // true when a tablet is bound and Mode 1 is available
        boolean embersCodeFromCache,          // true when embersCode was loaded from KnownCodeSavedData (previously inferred)
        boolean executionMachineSupportsGui,  // true when the bound execution machine supports remote GUI
        @Nullable ItemStack baseItem,         // JEI-provided base item for FA ApplyModifierRecipe prefill
        Set<String> boundMachineTypes,        // v3.4 availability passport: modType ids of machines the player has bound
        Map<IngredientKey, Integer> leftovers, // overproduction keyed by exact item+NBT
        @Nullable ItemStack clickedOutput,    // JEI ghost-output the player clicked (NBT-variant target, e.g. WR leveled book)
        @Nullable PlanGraphView graph,         // server-authored DAG view; null on legacy/fallback plans
        boolean executionBlocked,              // hard prerequisite failure, independent of material availability
        List<MachineCandidateView> machineCandidates,
        Map<ResourceLocation, StepIssue> stepIssues,
        @Nullable StorageReference storageReference,
        List<StorageNetworkDescriptor> storageNetworks
) {
    public PlanResponse {
        machineCandidates = machineCandidates == null ? List.of() : List.copyOf(machineCandidates);
        stepIssues = stepIssues == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(stepIssues));
        storageNetworks = storageNetworks == null ? List.of() : List.copyOf(storageNetworks);
    }

    public record StepIssue(List<Component> warnings, boolean blocked) {
        public StepIssue {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** Backward-compat: plans without per-step prerequisite diagnostics. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId, @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount,
                        @Nullable int[] embersCode, @Nullable Component[] embersAspectNames,
                        @Nullable Component[] embersInputNames, long embersSeed,
                        boolean embersCanInfer, boolean embersCodeFromCache,
                        boolean executionMachineSupportsGui, @Nullable ItemStack baseItem,
                        Set<String> boundMachineTypes, Map<IngredientKey, Integer> leftovers,
                        @Nullable ItemStack clickedOutput, @Nullable PlanGraphView graph,
                        boolean executionBlocked, List<MachineCandidateView> machineCandidates) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount, embersCode, embersAspectNames, embersInputNames,
                embersSeed, embersCanInfer, embersCodeFromCache, executionMachineSupportsGui,
                baseItem, boundMachineTypes, leftovers, clickedOutput, graph, executionBlocked,
                machineCandidates, Map.of(), null, List.of());
    }

    /** Backward-compatible constructor for callers that already provide step issues. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId, @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount,
                        @Nullable int[] embersCode, @Nullable Component[] embersAspectNames,
                        @Nullable Component[] embersInputNames, long embersSeed,
                        boolean embersCanInfer, boolean embersCodeFromCache,
                        boolean executionMachineSupportsGui, @Nullable ItemStack baseItem,
                        Set<String> boundMachineTypes, Map<IngredientKey, Integer> leftovers,
                        @Nullable ItemStack clickedOutput, @Nullable PlanGraphView graph,
                        boolean executionBlocked, List<MachineCandidateView> machineCandidates,
                        Map<ResourceLocation, StepIssue> stepIssues) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount, embersCode, embersAspectNames, embersInputNames,
                embersSeed, embersCanInfer, embersCodeFromCache, executionMachineSupportsGui,
                baseItem, boundMachineTypes, leftovers, clickedOutput, graph, executionBlocked,
                machineCandidates, stepIssues, null, List.of());
    }

    public Availability availability(ItemStack stack) {
        Availability exact = materials.get(IngredientKey.of(stack));
        if (exact != null || !stack.hasTag()) return exact;
        // Non-strict ingredients are represented by a tagless material card even
        // when the tree keeps a real stored variant for display.  Preserve the
        // lookup for that tree node without weakening strict-NBT cards.
        return materials.get(IngredientKey.of(new ItemStack(stack.getItem())));
    }

    public Availability availability(IngredientKey key) {
        Availability exact = materials.get(key);
        if (exact != null || !key.stack(1).hasTag()) return exact;
        return materials.get(IngredientKey.of(new ItemStack(key.item())));
    }

    public record Availability(int needed, int available) {
        public boolean isEnough() { return available >= needed; }
        public boolean isPartial() { return available > 0 && available < needed; }
    }

    /** Backward-compat: plans without an explicit hard prerequisite gate. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId, @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount,
                        @Nullable int[] embersCode, @Nullable Component[] embersAspectNames,
                        @Nullable Component[] embersInputNames, long embersSeed,
                        boolean embersCanInfer, boolean embersCodeFromCache,
                        boolean executionMachineSupportsGui, @Nullable ItemStack baseItem,
                        Set<String> boundMachineTypes, Map<IngredientKey, Integer> leftovers,
                        @Nullable ItemStack clickedOutput, @Nullable PlanGraphView graph) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount, embersCode, embersAspectNames, embersInputNames,
                embersSeed, embersCanInfer, embersCodeFromCache, executionMachineSupportsGui,
                baseItem, boundMachineTypes, leftovers, clickedOutput, graph, false, List.of(), Map.of(), null, List.of());
    }

    /** Backward-compat: plans without target-machine candidate snapshots. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId, @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount,
                        @Nullable int[] embersCode, @Nullable Component[] embersAspectNames,
                        @Nullable Component[] embersInputNames, long embersSeed,
                        boolean embersCanInfer, boolean embersCodeFromCache,
                        boolean executionMachineSupportsGui, @Nullable ItemStack baseItem,
                        Set<String> boundMachineTypes, Map<IngredientKey, Integer> leftovers,
                        @Nullable ItemStack clickedOutput, @Nullable PlanGraphView graph,
                        boolean executionBlocked) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount, embersCode, embersAspectNames, embersInputNames,
                embersSeed, embersCanInfer, embersCodeFromCache, executionMachineSupportsGui,
                baseItem, boundMachineTypes, leftovers, clickedOutput, graph, executionBlocked,
                List.of(), Map.of(), null, List.of());
    }

    /** Backward-compat: no execution routing info (vanilla/generic path). */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                null, null, 0, 0, 0, Collections.emptyList(), 1,
                null, null, null, 0, false, false, false, null, Collections.emptySet(),
                Collections.emptyMap(), null, null, false, List.of(), Map.of(), null, List.of());
    }

    /** Backward-compat: no mod warnings. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId,
                        @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                Collections.emptyList(), 1,
                null, null, null, 0, false, false, false, null, Collections.emptySet(),
                Collections.emptyMap(), null, null, false, List.of(), Map.of(), null, List.of());
    }

    /** Backward-compat: no embers data. */
    public PlanResponse(boolean success, String targetName, ItemStack targetResult,
                        List<PlanStep> steps, Map<IngredientKey, Availability> materials,
                        List<String> missing, String recipeId,
                        @Nullable String executionModTypeId,
                        @Nullable String executionDim,
                        int executionPosX, int executionPosY, int executionPosZ,
                        List<Component> modWarnings, int repeatCount) {
        this(success, targetName, targetResult, steps, materials, missing, recipeId,
                executionModTypeId, executionDim, executionPosX, executionPosY, executionPosZ,
                modWarnings, repeatCount,
                null, null, null, 0, false, false, false, null, Collections.emptySet(),
                Collections.emptyMap(), null, null, false, List.of(), Map.of(), null, List.of());
    }
}
