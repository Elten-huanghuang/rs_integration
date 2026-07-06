package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class GenericBatchDelegate implements IBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private ExtractionLedger ledger;
    private INetwork network;
    private ItemStack pendingResult;
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

        // Phase 2: reserve all ingredients via ledger
        List<ItemStack> templates = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
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

        this.pendingResult = computeResult(player);
        if (this.pendingResult.isEmpty()) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Generic] Cannot determine result for recipe {}",
                    recipe != null ? recipe.getId() : "null");
            return false;
        }

        // Materials were already reserved and committed by the chain's
        // preReserveStepMaterials flow — just mark as done so the chain
        // collects the result via collectResult().
        this.craftDone = true;
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
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
    public void onBatchFailed(ServerPlayer player, String reason) {
        refundAll();
        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        ledger = null;
        network = null;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        ledger = null;
        network = null;
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
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
