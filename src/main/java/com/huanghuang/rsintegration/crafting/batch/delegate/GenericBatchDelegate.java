package com.huanghuang.rsintegration.crafting.batch.delegate;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.RSIntegration;
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

        // Phase 1: reserve all ingredients via ledger
        List<ItemStack> templates = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
            if (stack.isEmpty()) return false;
            templates.add(stack);
        }

        // Phase 2: commit all extractions atomically
        if (!ledger.commit(network, player)) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-Generic] Ledger commit failed");
            return false;
        }

        // Phase 3: compute result
        try {
            Object result = recipe.getClass().getMethod("getResultItem",
                    net.minecraft.core.RegistryAccess.class)
                    .invoke(recipe, player.serverLevel().registryAccess());
            if (result instanceof ItemStack stack && !stack.isEmpty()) {
                this.pendingResult = stack.copy();
            }
        } catch (Exception e) {
            try {
                Object result = recipe.getClass().getMethod("getResultItem").invoke(recipe);
                if (result instanceof ItemStack stack && !stack.isEmpty()) {
                    this.pendingResult = stack.copy();
                }
            } catch (Exception ex) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Generic] Reflection probe failed", ex); }
        }

        // Extracted items have been consumed by the machine — discard templates
        templates.clear();

        // Result is collected by BatchCraftTask.tick() via collectResult() →
        // insertIntoRS(). Do NOT insert here to avoid double-inserting.
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
        if (ledger == null || !ledger.isCommitted()) return;
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs == null) return;
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack[] opts = spec.ingredient().getItems();
            if (opts.length > 0 && !opts[0].isEmpty()) {
                ItemStack refund = opts[0].copyWithCount(spec.count());
                if (network != null) {
                    network.insertItem(refund, spec.count(), com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                } else if (player != null) {
                    ItemHandlerHelper.giveItemToPlayer(player, refund);
                }
            }
        }
    }
}
