package com.huanghuang.rsintegration.mods.vanilla;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.network.RSIntegration;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

public final class VanillaMachineBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private ItemStack pendingResult;
    private boolean craftDone;

    // FURNACE path state
    private AbstractFurnaceBlockEntity furnaceBE;
    private MachineKind kind;
    private List<ItemStack> fuelStacks; // track fuel for refund decisions

    private enum MachineKind {
        FURNACE,
        VIRTUAL
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        this.player = player;
        this.myPos = pos;
        this.pendingResult = ItemStack.EMPTY;
        this.craftDone = false;
        this.furnaceBE = null;
        this.fuelStacks = null;

        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        com.huanghuang.rsintegration.util.ChunkUtils.loadChunk(level, pos);

        this.myLevel = level;
        this.myDim = level.dimension();

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            // Some vanilla blocks have no BlockEntity (e.g., Smithing Table).
            // Verify the block itself is a known vanilla machine, then use VIRTUAL path.
            String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(level.getBlockState(pos).getBlock()).toString();
            if (blockId.contains("smithing_table") || blockId.contains("campfire")) {
                this.kind = MachineKind.VIRTUAL;
            } else {
                player.sendSystemMessage(Component.translatable("rsi.vanilla.error.machine_not_found"));
                return false;
            }
        } else if (be instanceof AbstractFurnaceBlockEntity fbe) {
            // Validate furnace type matches recipe type
            String beClassName = be.getClass().getName().toLowerCase();
            if (recipe instanceof BlastingRecipe && !beClassName.contains("blast")) {
                player.sendSystemMessage(Component.translatable("rsi.vanilla.error.wrong_furnace_type"));
                return false;
            }
            if (recipe instanceof SmokingRecipe && !beClassName.contains("smoker")) {
                player.sendSystemMessage(Component.translatable("rsi.vanilla.error.wrong_furnace_type"));
                return false;
            }
            if (recipe instanceof SmeltingRecipe
                    && (beClassName.contains("blast") || beClassName.contains("smoker"))) {
                if (!beClassName.contains("furnace") || beClassName.contains("blast")) {
                    player.sendSystemMessage(Component.translatable("rsi.vanilla.error.wrong_furnace_type"));
                    return false;
                }
            }

            // Check furnace is idle
            ItemStack slot0 = fbe.getItem(0);
            if (!slot0.isEmpty()) {
                List<Ingredient> ingredients = recipe.getIngredients();
                boolean matches = false;
                for (Ingredient ing : ingredients) {
                    if (ing.test(slot0)) { matches = true; break; }
                }
                if (!matches) {
                    player.sendSystemMessage(Component.translatable("rsi.vanilla.error.furnace_occupied"));
                    return false;
                }
            }

            this.furnaceBE = fbe;
            this.kind = MachineKind.FURNACE;
        } else {
            // Other BE-backed machines: Campfire (has BE), Stonecutter (has BE)
            this.kind = MachineKind.VIRTUAL;
        }

        return true;
    }

    // ── Private ledger (direct path) ───────────────────────────────

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.usingSharedLedger = false;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) {
            this.network = RSIntegration.resolveNetworkFromPlayer(player);
        }
        this.craftDone = false;
        this.fuelStacks = null;

        if (kind == MachineKind.FURNACE) {
            return tryStartFurnace(player);
        } else {
            return tryStartVirtual(player);
        }
    }

    // ── Shared ledger (chain path, getRequiredMaterials returns null/empty) ─

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player, ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) {
            this.network = RSIntegration.resolveNetworkFromPlayer(player);
        }
        this.craftDone = false;
        this.fuelStacks = null;

        if (kind == MachineKind.FURNACE) {
            return tryStartFurnace(player);
        } else {
            return tryStartVirtual(player);
        }
    }

    // ── Pre-reserved materials (chain path, getRequiredMaterials returns specs) ─

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player,
                                         List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) {
            this.network = RSIntegration.resolveNetworkFromPlayer(player);
        }
        this.craftDone = false;
        this.fuelStacks = null;

        if (kind == MachineKind.FURNACE) {
            return tryStartFurnaceWithMaterials(materials);
        } else {
            // Virtual: materials already committed by chain, compute result directly
            this.pendingResult = computeResult();
            if (this.pendingResult.isEmpty()) return false;
            this.craftDone = true;
            return true;
        }
    }

    // ── FURNACE path ──────────────────────────────────────────────

    private boolean tryStartFurnace(ServerPlayer player) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return false;

        // Extract and place input ingredient
        Ingredient input = ingredients.get(0);
        if (!input.isEmpty()) {
            ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;
            ItemStack extracted = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, input, 1, activeLedger);
            if (extracted.isEmpty()) {
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.missing_materials",
                        CraftPacketUtils.describeIngredient(input)));
                return false;
            }
            furnaceBE.setItem(0, extracted.copy());
        }

        // Auto-supply fuel (extracts directly from RS, outside ledger)
        if (!ensureFuel(player)) {
            // Refund the input
            ItemStack refund = furnaceBE.getItem(0);
            if (!refund.isEmpty()) {
                furnaceBE.setItem(0, ItemStack.EMPTY);
                if (usingSharedLedger) {
                    // Chain will handle refund via refundCommitted; just clear slot
                } else if (ledger != null && !ledger.isCommitted()) {
                    ledger.rollback(player);
                }
            }
            player.sendSystemMessage(Component.translatable("rsi.vanilla.error.no_fuel"));
            return false;
        }

        // Commit private ledger
        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                // Refund everything
                ItemStack refund = furnaceBE.getItem(0);
                if (!refund.isEmpty()) {
                    furnaceBE.setItem(0, ItemStack.EMPTY);
                    ItemHandlerHelper.giveItemToPlayer(player, refund);
                }
                player.sendSystemMessage(Component.translatable(
                        "rsi.generic.error.craft_failed", "Extraction commit failed"));
                return false;
            }
        }

        furnaceBE.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        return true;
    }

    private boolean tryStartFurnaceWithMaterials(List<ItemStack> materials) {
        if (materials.isEmpty()) return false;

        // Place pre-reserved input (chain already committed the ledger)
        furnaceBE.setItem(0, materials.get(0).copy());

        // Auto-supply fuel (extracts directly from RS, outside ledger)
        if (!ensureFuel(player)) {
            ItemStack refund = furnaceBE.getItem(0);
            if (!refund.isEmpty()) {
                furnaceBE.setItem(0, ItemStack.EMPTY);
            }
            player.sendSystemMessage(Component.translatable("rsi.vanilla.error.no_fuel"));
            return false;
        }

        furnaceBE.setChanged();
        myLevel.sendBlockUpdated(myPos,
                myLevel.getBlockState(myPos), myLevel.getBlockState(myPos), 3);
        return true;
    }

    private boolean ensureFuel(ServerPlayer player) {
        // Check if furnace already has litTime > 0
        try {
            java.lang.reflect.Field litTimeField = AbstractFurnaceBlockEntity.class
                    .getDeclaredField("litTime");
            litTimeField.setAccessible(true);
            if (litTimeField.getInt(furnaceBE) > 0) return true;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Vanilla] litTime probe failed", e);
        }

        // Check existing fuel in slot 1
        ItemStack existingFuel = furnaceBE.getItem(1);
        if (!existingFuel.isEmpty()) {
            int burnTime = ForgeHooks.getBurnTime(existingFuel, recipe.getType());
            if (burnTime > 0) return true;
        }

        // Try to extract fuel from RS network
        if (network == null) return false;

        int cookingTime;
        if (recipe instanceof AbstractCookingRecipe acr) {
            cookingTime = acr.getCookingTime();
        } else {
            cookingTime = 200; // default
        }

        // Scan RS storage for burnable items
        this.fuelStacks = new ArrayList<>();
        var stacks = network.getItemStorageCache().getList().getStacks();
        for (var entry : stacks) {
            ItemStack candidate = entry.getStack();
            if (candidate.isEmpty()) continue;
            int singleBurnTime = ForgeHooks.getBurnTime(candidate, recipe.getType());
            if (singleBurnTime <= 0) continue;
            int needed = Math.max(1, (cookingTime + singleBurnTime - 1) / singleBurnTime);
            int available = Math.min(needed, candidate.getCount());

            ItemStack extracted = network.extractItem(
                    candidate.copyWithCount(1), available,
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!extracted.isEmpty()) {
                fuelStacks.add(extracted.copy());
                furnaceBE.setItem(1, extracted.copy());
                player.displayClientMessage(
                        Component.translatable("rsi.vanilla.info.fuel_supplied", extracted.getCount()), true);
                return true;
            }
        }

        return false;
    }

    // ── VIRTUAL path ──────────────────────────────────────────────

    private boolean tryStartVirtual(ServerPlayer player) {
        List<IngredientSpec> specs = CraftPacketUtils.extractIngredientSpecs(recipe);
        if (specs == null || specs.isEmpty()) return false;

        this.pendingResult = computeResult();
        if (this.pendingResult.isEmpty()) return false;

        ExtractionLedger activeLedger = usingSharedLedger ? sharedLedger : ledger;

        // Reserve all ingredients
        List<ItemStack> extracted = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, spec.ingredient(), spec.count(), activeLedger);
            if (stack.isEmpty()) {
                if (!usingSharedLedger) {
                    ledger.rollback(player);
                }
                return false;
            }
            extracted.add(stack);
        }

        // Commit private ledger
        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            if (!ledger.commit(network, player)) {
                return false;
            }
        }

        this.craftDone = true;
        return true;
    }

    private ItemStack computeResult() {
        try {
            return recipe.getResultItem(myLevel.registryAccess()).copy();
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Vanilla] computeResult failed for {}",
                    recipe.getId(), e);
        }
        return ItemStack.EMPTY;
    }

    // ── polling / collection ──────────────────────────────────────

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        if (kind == MachineKind.VIRTUAL) return craftDone;

        if (furnaceBE == null) return true;

        // Check result slot has output
        ItemStack result = furnaceBE.getItem(2);
        return !result.isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (kind == MachineKind.VIRTUAL) {
            ItemStack r = pendingResult.copy();
            pendingResult = ItemStack.EMPTY;
            craftDone = false;
            return r;
        }

        if (furnaceBE == null) return ItemStack.EMPTY;

        ItemStack result = furnaceBE.getItem(2).copy();
        if (!result.isEmpty()) {
            furnaceBE.setItem(2, ItemStack.EMPTY);
            // Clear input slot too (it was consumed)
            furnaceBE.setItem(0, ItemStack.EMPTY);
            furnaceBE.setChanged();
        }
        return result;
    }

    // ── lifecycle ─────────────────────────────────────────────────

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        if (kind == MachineKind.FURNACE && furnaceBE != null) {
            // Refund unprocessed input from slot 0
            ItemStack slot0 = furnaceBE.getItem(0);
            if (!slot0.isEmpty()) {
                furnaceBE.setItem(0, ItemStack.EMPTY);
                if (usingSharedLedger) {
                    // Chain refunds via refundCommitted; just clear machine
                } else {
                    refundToRSNetwork(slot0);
                }
            }
            // Refund any result from slot 2
            ItemStack slot2 = furnaceBE.getItem(2);
            if (!slot2.isEmpty()) {
                furnaceBE.setItem(2, ItemStack.EMPTY);
                if (!usingSharedLedger) {
                    refundToRSNetwork(slot2);
                }
            }
            // Do NOT refund fuel from slot 1 (already partially consumed)
            furnaceBE.setChanged();
        }

        // Rollback uncommitted private ledger
        if (!usingSharedLedger && ledger != null && !ledger.isCommitted()) {
            ledger.rollback(player);
        }

        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        fuelStacks = null;
        resetState();
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        pendingResult = ItemStack.EMPTY;
        craftDone = false;
        fuelStacks = null;
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    // ── helpers ───────────────────────────────────────────────────

    private void refundToRSNetwork(ItemStack stack) {
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty() && player != null) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }
}
