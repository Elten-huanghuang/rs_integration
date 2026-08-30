package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.mods.goety.GoetySoulTotemCrafting;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public class GenericBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private ItemStack pendingResult;
    private final List<ItemStack> pendingSecondary = new ArrayList<>();
    private boolean craftDone;
    private int preparedGraphExecutions = 1;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.myLevel = level;
        this.myDim = level.dimension();
        this.myPos = pos;
        this.player = player;

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.pendingResult = ItemStack.EMPTY;
        this.pendingSecondary.clear();
        this.craftDone = false;
        this.preparedGraphExecutions = 1;

        if (!validateExecutionContext(player)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.execution_context", recipeId));
            return false;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Generic] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        if (recipe == null) return false;
        ModRecipeHandler handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler == null) {
            return !recipe.getClass().getName().startsWith("snownee.lychee.item_inside.");
        }
        return handler.isAvailableForPlanning(recipe, player);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        if (!validateExecutionContext(player)) return false;
        this.player = player;
        this.ledger = new ExtractionLedger();
        // The chain may have already selected a BD endpoint.  Only resolve the
        // legacy RS handle when no backend-neutral endpoint was injected.
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network != null) {
                this.ledger.setStorageEndpoint(CraftStorageEndpoints.fromLegacyNetwork(this.network));
            }
        } else {
            this.ledger.setStorageEndpoint(storageEndpoint());
        }
        this.craftDone = false;
        this.pendingSecondary.clear();

        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Generic] No ingredients for recipe: {}", recipe.getId());
            return false;
        }

        // Phase 1: compute result first (zero side effects).  If we cannot
        // determine the result there is no point extracting materials.
        this.pendingResult = computeResult(player);
        if (this.pendingResult.isEmpty()) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Generic] Cannot determine result for recipe {}", recipe.getId());
            return false;
        }

        // Non-crafting secondaries do not depend on a crafting grid. Crafting
        // remainders are captured after the actual NBT-bearing inputs exist.
        if (!(recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe)) {
            this.pendingSecondary.addAll(
                    ModRecipeHandlers.tryGetSecondaryOutputs(
                            recipe, player.serverLevel().registryAccess()));
        }

        // Phase 2: reserve all ingredients via ledger
        List<ItemStack> templates = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) {
                templates.add(ItemStack.EMPTY);
                continue;
            }
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos,
                    spec.ingredient(), spec.count(), ledger);
            if (stack.isEmpty()) {
                this.pendingResult = ItemStack.EMPTY;
                return false; // ledger not committed — nothing lost
            }
            templates.add(stack);
        }

        // Phase 3: commit all extractions atomically
        if (!validateExecutionContext(player) || !ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Generic] Ledger commit failed");
            this.pendingResult = ItemStack.EMPTY;
            return false; // ledger not committed — nothing lost
        }

        // Recompute result via assemble() now that we have the actual consumed
        // items.  getResultItem() (used in the Phase-1 pre-check above) returns
        // a bare template — any NBT from inputs (backpack contents, blade stats,
        // enchantments) would be silently discarded.
        if (recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe smithing) {
            // SmithingTransformRecipe is not a CraftingRecipe, but its output
            // carries the base tool's NBT (durability, enchantments, custom
            // data).  The indexed result is only a bare template, so assemble
            // from the actual extracted stacks before discarding them.
            ItemStack assembled = SmithingRecipeHandler.assembleTransform(
                    smithing, templates, player.serverLevel().registryAccess());
            if (assembled.isEmpty()) {
                RSIntegrationMod.LOGGER.error(
                        "[RSI-Batch-Generic] Smithing assembly failed for recipe {}", recipe.getId());
                this.pendingResult = ItemStack.EMPTY;
                return false;
            }
            this.pendingResult = assembled;
        } else if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe cr) {
            captureActualCraftingOutputs(cr, templates, player);
        }

        // Extracted items have been consumed — discard templates
        templates.clear();

        // Result is collected by BatchCraftTask.tick() via collectResult() →
        // insertIntoRS(). Do NOT insert here to avoid double-inserting.
        this.craftDone = true;
        return true;
    }

    private ItemStack computeResult(ServerPlayer player) {
        ItemStack indexed = RecipeIndex
                .tryGetResultItem(recipe, player.serverLevel().registryAccess()).copy();
        if (!indexed.isEmpty()) return indexed;
        // Vanilla smithing recipes expose a stable registry-aware result even
        // when a third-party result cache has recorded a transient empty value.
        if (recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe smithing) {
            return smithing.getResultItem(player.serverLevel().registryAccess()).copy();
        }
        if (recipe instanceof net.minecraft.world.item.crafting.SmithingTrimRecipe smithing) {
            return smithing.getResultItem(player.serverLevel().registryAccess()).copy();
        }
        return ItemStack.EMPTY;
    }

    // ── shared-ledger path for AsyncCraftChain ───────────────────────

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        return GoetySoulTotemCrafting.requireBatchCharge(
                CraftPacketUtils.extractIngredientSpecs(recipe), preparedGraphExecutions);
    }

    @Override
    public void prepareGraphBatch(int executions) {
        this.preparedGraphExecutions = Math.max(1, executions);
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player,
                                         List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (!validateExecutionContext(player)) return false;
        this.player = player;
        this.ledger = sharedLedger;
        // Shared-ledger graph dispatch owns the selected storage session. Do
        // not replace a BD endpoint with a fresh RS lookup here.
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        }
        this.craftDone = false;
        this.pendingSecondary.clear();

        this.pendingResult = computeResult(player);
        if (this.pendingResult.isEmpty()) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Generic] Cannot determine result for recipe {}",
                    recipe != null ? recipe.getId() : "null");
            return false;
        }

        // Materials are in exact spec order, including empty shaped slots.
        // Use them for NBT-dependent assembly and durability/reuse remainders.
        if (player != null) {
            if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe cr) {
                if (!captureRepeatedCraftingOutputs(cr, materials, player)) return false;
            } else if (recipe instanceof net.minecraft.world.item.crafting.SmithingTransformRecipe smithing) {
                List<ItemStack> operationMaterials = new ArrayList<>();
                List<IngredientSpec> specs = getRequiredMaterials();
                for (int i = 0; i < specs.size() && i < materials.size(); i++) {
                    IngredientSpec spec = specs.get(i);
                    operationMaterials.add(spec.isEmpty() || materials.get(i) == null
                            ? ItemStack.EMPTY : materials.get(i).copyWithCount(spec.count()));
                }
                ItemStack assembled = SmithingRecipeHandler.assembleTransform(
                        smithing, operationMaterials, player.serverLevel().registryAccess());
                if (assembled.isEmpty()) return false;
                int executions = materialExecutions(
                        getRequiredMaterials(), getMaterialReservationScopes(), materials,
                        preparedGraphExecutions);
                if (executions <= 0) return false;
                assembled.setCount(Math.multiplyExact(assembled.getCount(), executions));
                pendingResult = assembled;
                this.pendingSecondary.addAll(ModRecipeHandlers.tryGetSecondaryOutputs(
                        recipe, player.serverLevel().registryAccess()));
            } else {
                int executions = materialExecutions(
                        getRequiredMaterials(), getMaterialReservationScopes(), materials,
                        preparedGraphExecutions);
                if (executions <= 0) return false;
                pendingResult.setCount(Math.multiplyExact(pendingResult.getCount(), executions));
                this.pendingSecondary.addAll(
                        ModRecipeHandlers.tryGetSecondaryOutputs(
                                recipe, player.serverLevel().registryAccess()));
                if (executions > 1) {
                    for (ItemStack secondary : pendingSecondary) {
                        secondary.setCount(Math.multiplyExact(secondary.getCount(), executions));
                    }
                }
            }
        }

        // Materials were already reserved and committed by the chain's
        // preReserveStepMaterials flow — just mark as done so the chain
        // collects the result via collectResult().
        this.craftDone = true;
        return true;
    }

    /**
     * Graph nodes that cannot be parallelized receive one material list whose
     * per-slot counts are scaled by the node execution count. Execute the
     * crafting recipe once per represented batch instead of assembling only
     * the first result from the aggregated grid.
     */
    private boolean captureRepeatedCraftingOutputs(
            net.minecraft.world.item.crafting.CraftingRecipe craftingRecipe,
            List<ItemStack> materials, ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        return captureRepeatedCraftingOutputs(
                craftingRecipe, specs, getMaterialReservationScopes(), materials,
                preparedGraphExecutions, player.serverLevel().registryAccess());
    }

    boolean captureRepeatedCraftingOutputs(
            net.minecraft.world.item.crafting.CraftingRecipe craftingRecipe,
            List<IngredientSpec> specs,
            List<MaterialReservationScope> scopes,
            List<ItemStack> materials,
            int expectedExecutions,
            RegistryAccess registryAccess) {
        int executions = materialExecutions(
                specs, scopes, materials, expectedExecutions);
        if (executions <= 0) return false;

        RepeatedCraftingOutputAccumulator outputs = new RepeatedCraftingOutputAccumulator();
        List<ItemStack> reusableState = materials.stream()
                .map(stack -> stack == null ? ItemStack.EMPTY : stack.copy())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        for (int operation = 0; operation < executions; operation++) {
            List<ItemStack> operationMaterials = new ArrayList<>(materials.size());
            for (int i = 0; i < specs.size(); i++) {
                IngredientSpec spec = specs.get(i);
                boolean reusable = i < scopes.size()
                        && scopes.get(i) == MaterialReservationScope.PER_WORKER_REUSABLE;
                ItemStack material = reusable ? reusableState.get(i) : materials.get(i);
                operationMaterials.add(spec.isEmpty() || material == null || material.isEmpty()
                        ? ItemStack.EMPTY : material.copyWithCount(spec.count()));
            }
            // Require a real assemble() output each iteration. If assemble()
            // returns empty, pendingResult would still hold the computeResult
            // template (or the previous iteration's output) — accumulating that
            // ×N would fabricate items the recipe never produced.
            ItemStack[] consumed = operationMaterials.stream()
                    .map(stack -> stack == null ? ItemStack.EMPTY : stack.copy())
                    .toArray(ItemStack[]::new);
            ItemStack assembled = CraftPacketUtils.assembleCraftingOutput(
                    craftingRecipe, consumed, registryAccess);
            if (assembled.isEmpty()) return false;
            pendingResult = assembled;
            if (!outputs.add(pendingResult)) return false;

            List<ItemStack> remainders = CraftPacketUtils.getRecipeRemaindersBySpec(
                    craftingRecipe, consumed);
            for (int i = 0; i < specs.size(); i++) {
                ItemStack remainder = i < remainders.size()
                        ? remainders.get(i) : ItemStack.EMPTY;
                boolean reusable = i < scopes.size()
                        && scopes.get(i) == MaterialReservationScope.PER_WORKER_REUSABLE;
                if (reusable) {
                    if (remainder.isEmpty()) {
                        boolean exhaustedGoetyTotem = operation + 1 == executions
                                && GoetySoulTotemCrafting.isSoulTotem(consumed[i]);
                        if (!exhaustedGoetyTotem) return false;
                        reusableState.set(i, ItemStack.EMPTY);
                        continue;
                    }
                    if (operation + 1 < executions) {
                        Ingredient continuationIngredient = i < craftingRecipe.getIngredients().size()
                                ? craftingRecipe.getIngredients().get(i)
                                : specs.get(i).ingredient();
                        if (!IngredientMatcher.test(continuationIngredient, remainder)) return false;
                    }
                    reusableState.set(i, remainder.copy());
                } else if (!remainder.isEmpty()) {
                    pendingSecondary.add(remainder.copy());
                }
            }
        }
        for (int i = 0; i < specs.size(); i++) {
            boolean reusable = i < scopes.size()
                    && scopes.get(i) == MaterialReservationScope.PER_WORKER_REUSABLE;
            if (reusable && !reusableState.get(i).isEmpty()) {
                pendingSecondary.add(reusableState.get(i).copy());
            }
        }
        pendingResult = outputs.result();
        return true;
    }

    static int materialExecutions(
            List<IngredientSpec> specs,
            List<MaterialReservationScope> scopes,
            List<ItemStack> materials,
            int expectedExecutions) {
        if (specs == null || specs.size() != materials.size()) return -1;
        int executions = Math.max(1, expectedExecutions);
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            if (spec.isEmpty()) continue;
            ItemStack material = materials.get(i);
            if (material == null || material.isEmpty() || spec.count() <= 0) return -1;
            boolean reusable = i < scopes.size()
                    && scopes.get(i) == MaterialReservationScope.PER_WORKER_REUSABLE;
            int required = reusable
                    ? spec.count()
                    : Math.multiplyExact(spec.count(), executions);
            if (material.getCount() != required) return -1;
        }
        return executions;
    }

    /**
     * Assemble the actual NBT-bearing output for one crafting operation and
     * capture its remainders. Returns {@code true} if assemble() produced a
     * non-empty result (and {@code pendingResult} was updated), {@code false}
     * if assemble() returned empty — in which case {@code pendingResult} is
     * left untouched. Callers that repeat operations must treat {@code false}
     * as a failure rather than reusing the stale {@code pendingResult}.
     */
    private boolean captureActualCraftingOutputs(
            net.minecraft.world.item.crafting.CraftingRecipe craftingRecipe,
            List<ItemStack> materials, ServerPlayer player) {
        return captureActualCraftingOutputs(
                craftingRecipe, materials, getRequiredMaterials(), true,
                player.serverLevel().registryAccess());
    }

    private boolean captureActualCraftingOutputs(
            net.minecraft.world.item.crafting.CraftingRecipe craftingRecipe,
            List<ItemStack> materials,
            List<IngredientSpec> specs,
            boolean captureReusableRemainders,
            RegistryAccess registryAccess) {
        ItemStack[] consumed = materials.stream()
                .map(stack -> stack == null ? ItemStack.EMPTY : stack.copy())
                .toArray(ItemStack[]::new);
        ItemStack assembled = CraftPacketUtils.assembleCraftingOutput(
                craftingRecipe, consumed, registryAccess);
        if (!assembled.isEmpty()) pendingResult = assembled;
        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(
                craftingRecipe, consumed)) {
            if (remainder != null && !remainder.isEmpty()) {
                boolean reusable = specs != null
                        && CraftPacketUtils.remainderExecutions(remainder, specs, 2) == 1;
                if (captureReusableRemainders || !reusable) {
                    pendingSecondary.add(remainder.copy());
                }
            }
        }
        return !assembled.isEmpty();
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return craftDone;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        ItemStack r = pendingResult.copy();
        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        return r;
    }

    @Override
    public List<ItemStack> collectAllResults(ServerPlayer player) {
        // A non-CraftingRecipe (notably SmithingTransformRecipe) is resolved
        // from its declared result during dispatch rather than assembled from
        // a grid.  If a graph retry/cleanup cleared the transient result
        // before collection, recover it from the authoritative recipe instead
        // of publishing an empty output after materials were committed.
        if (pendingResult.isEmpty() && recipe != null && player != null) {
            ItemStack recovered = computeResult(player);
            if (!recovered.isEmpty()) {
                pendingResult = recovered;
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-Generic] Recovered missing collected output recipe={} output={}x{}",
                        recipe.getId(), com.huanghuang.rsintegration.util.ItemStackUtils.registryId(recovered), recovered.getCount());
            }
        }
        List<ItemStack> results = new ArrayList<>(pendingSecondary.size() + 1);
        if (!pendingResult.isEmpty()) results.add(pendingResult.copy());
        for (ItemStack secondary : pendingSecondary) {
            if (secondary != null && !secondary.isEmpty()) results.add(secondary.copy());
        }
        pendingResult = ItemStack.EMPTY;
        pendingSecondary.clear();
        craftDone = false;
        return List.copyOf(results);
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return true;
    }

    /** Legacy compatibility for callers that collect only the primary result. */
    public List<ItemStack> getPendingSecondary() {
        List<ItemStack> copy = new ArrayList<>(pendingSecondary);
        pendingSecondary.clear();
        return copy;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        refundAll();
        pendingResult = ItemStack.EMPTY;
        pendingSecondary.clear();
        craftDone = false;
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        pendingResult = ItemStack.EMPTY;
        pendingSecondary.clear();
        craftDone = false;
        ledger = null;
        network = null;
    }

    @Override
    @Nullable
    public BlockPos getMachinePos() {
        // Generic recipes execute logically and have no physical machine to observe.
        return null;
    }

    private void refundAll() {
        if (ledger != null && ledger.isCommitted()) {
            // Materials were already extracted and committed.  If we computed
            // the result (Phase 1 succeeds before Phase 3), insert the result
            // into RS so the materials are not simply lost.
            if (craftDone && !pendingResult.isEmpty() && network != null) {
                var leftover = ledgerStorageInsert(pendingResult.copy());
                if (!leftover.isEmpty() && player != null && !player.hasDisconnected() && !player.isRemoved()) {
                    ItemHandlerHelper.giveItemToPlayer(player, leftover);
                }
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Generic] Recovery: inserted result {}x{} after commit failure",
                        pendingResult.getCount(), com.huanghuang.rsintegration.util.ItemStackUtils.registryId(pendingResult));
            } else {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Generic] Batch failed after commit. "
                        + "{} items may have been lost for recipe {}.",
                        ledger.size(), recipe != null ? recipe.getId() : "unknown");
            }
        }
    }

    private ItemStack ledgerStorageInsert(ItemStack stack) {
        return insertIntoStorage(player, stack, false);
    }
}
