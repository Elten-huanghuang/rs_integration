package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
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

public final class GenericBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private ItemStack pendingResult;
    private final List<ItemStack> pendingSecondary = new ArrayList<>();
    private boolean craftDone;

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
        this.craftDone = false;

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Generic] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
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
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Generic] Ledger commit failed");
            this.pendingResult = ItemStack.EMPTY;
            return false; // ledger not committed — nothing lost
        }

        // Recompute result via assemble() now that we have the actual consumed
        // items.  getResultItem() (used in the Phase-1 pre-check above) returns
        // a bare template — any NBT from inputs (backpack contents, blade stats,
        // enchantments) would be silently discarded.
        if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe cr) {
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
        return RecipeIndex
                .tryGetResultItem(recipe, player.serverLevel().registryAccess()).copy();
    }

    // ── shared-ledger path for AsyncCraftChain ───────────────────────

    @Override
    @Nullable
    public List<IngredientSpec> getRequiredMaterials() {
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player,
                                         List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.ledger = sharedLedger;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
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
            } else {
                this.pendingSecondary.addAll(
                        ModRecipeHandlers.tryGetSecondaryOutputs(
                                recipe, player.serverLevel().registryAccess()));
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
        if (specs == null || specs.size() != materials.size()) return false;

        int executions = -1;
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            if (spec.isEmpty()) continue;
            ItemStack material = materials.get(i);
            if (material == null || material.isEmpty() || spec.count() <= 0
                    || material.getCount() % spec.count() != 0) return false;
            int slotExecutions = material.getCount() / spec.count();
            if (executions < 0) executions = slotExecutions;
            else if (executions != slotExecutions) return false;
        }
        if (executions <= 0) return false;

        ItemStack combinedResult = ItemStack.EMPTY;
        for (int operation = 0; operation < executions; operation++) {
            List<ItemStack> operationMaterials = new ArrayList<>(materials.size());
            for (int i = 0; i < specs.size(); i++) {
                IngredientSpec spec = specs.get(i);
                ItemStack material = materials.get(i);
                operationMaterials.add(spec.isEmpty() || material == null || material.isEmpty()
                        ? ItemStack.EMPTY : material.copyWithCount(spec.count()));
            }
            // Require a real assemble() output each iteration. If assemble()
            // returns empty, pendingResult would still hold the computeResult
            // template (or the previous iteration's output) — accumulating that
            // ×N would fabricate items the recipe never produced.
            if (!captureActualCraftingOutputs(craftingRecipe, operationMaterials, player)) {
                return false;
            }
            ItemStack operationResult = pendingResult.copy();
            if (operationResult.isEmpty()) return false;
            if (combinedResult.isEmpty()) {
                combinedResult = operationResult;
            } else if (ItemStack.isSameItemSameTags(combinedResult, operationResult)) {
                combinedResult.grow(operationResult.getCount());
            } else {
                return false;
            }
        }
        pendingResult = combinedResult;
        return true;
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
        ItemStack[] consumed = materials.stream()
                .map(stack -> stack == null ? ItemStack.EMPTY : stack.copy())
                .toArray(ItemStack[]::new);
        ItemStack assembled = CraftPacketUtils.assembleCraftingOutput(
                craftingRecipe, consumed, player);
        if (!assembled.isEmpty()) pendingResult = assembled;
        for (ItemStack remainder : CraftPacketUtils.getRecipeRemainders(
                craftingRecipe, consumed)) {
            if (remainder != null && !remainder.isEmpty()) {
                pendingSecondary.add(remainder.copy());
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

    /** Secondary outputs (remainders, extra products) that the chain should
     *  add to virtual inventory.  Call after {@link #collectResult}. */
    public List<ItemStack> getPendingSecondary() {
        List<ItemStack> copy = new ArrayList<>(pendingSecondary);
        pendingSecondary.clear();
        return copy;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        refundAll();
        pendingResult = ItemStack.EMPTY;
        craftDone = false;
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        pendingResult = ItemStack.EMPTY;
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
                var leftover = network.insertItem(pendingResult.copy(),
                        pendingResult.getCount(), com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                if (!leftover.isEmpty() && player != null && !player.hasDisconnected() && !player.isRemoved()) {
                    ItemHandlerHelper.giveItemToPlayer(player, leftover);
                }
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Generic] Recovery: inserted result {}x{} after commit failure",
                        pendingResult.getCount(), pendingResult.getHoverName().getString());
            } else {
                RSIntegrationMod.LOGGER.error("[RSI-Batch-Generic] Batch failed after commit. "
                        + "{} items may have been lost for recipe {}.",
                        ledger.size(), recipe != null ? recipe.getId() : "unknown");
            }
        }
    }
}
