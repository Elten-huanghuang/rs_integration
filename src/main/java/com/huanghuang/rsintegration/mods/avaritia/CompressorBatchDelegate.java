package com.huanghuang.rsintegration.mods.avaritia;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Real batch delegate for Avaritia Neutron Compressor (all 4 tiers).
 * Inserts materials into slot 1 (input), lets the machine's tick()
 * process them, then collects from slot 0 (output).
 */
public final class CompressorBatchDelegate extends AbstractBatchDelegate {

    private static final String BE_CLASS = "committee.nova.mods.avaritia.common.tile.NeutronCompressorTile";

    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        myLevel = level;
        myDim = level.dimension();
        myPos = pos;

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        recipe = found;

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Comp] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null) {
            return handler.getIngredients(recipe);
        }
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            var network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (network == null) return false;

            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                        player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                if (reserved.isEmpty()) {
                    return false;
                }
                materials.add(reserved.copy());
            }

            if (!ledger.commit(network, player)) return false;

            this.usingSharedLedger = false;
            if (!tryStartWithMaterials(player, materials, ledger)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        network.insertItem(mat.copy(), mat.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        myLevel.getChunk(myPos);

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return false;

        if (!be.getClass().getName().equals(BE_CLASS)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Comp] Not a NeutronCompressorTile at {}", myPos);
            return false;
        }

        IItemHandler handler = getHandler(be);
        if (handler == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Comp] No IItemHandler at {}", myPos);
            return false;
        }
        if (!handler.getStackInSlot(0).isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Comp] Output slot occupied at {}", myPos);
            return false;
        }

        forceChunkLoad(true);

        // Insert into slot 1 (input). Materials may be N items of the same ingredient.
        // CompressorRecipe has getInputCount() → total count needed
        if (!materials.isEmpty() && !materials.get(0).isEmpty()) {
            // Combine all material stacks into one for insertion
            ItemStack combined = materials.get(0).copy();
            for (int i = 1; i < materials.size(); i++) {
                if (!materials.get(i).isEmpty()) {
                    combined.grow(materials.get(i).getCount());
                }
            }
            ItemStack remainder = handler.insertItem(1, combined, false);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Comp] Input slot cannot accept all materials at {}", myPos);
                return false;
            }
        }

        be.setChanged();
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Comp] Materials inserted at {}, waiting for compression", myPos);
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        IItemHandler handler = getHandler(be);
        if (handler == null) return false;
        return !handler.getStackInSlot(0).isEmpty()
                || handler.getStackInSlot(1).isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;
        IItemHandler handler = getHandler(be);
        if (handler == null) return ItemStack.EMPTY;

        ItemStack result = handler.extractItem(0, 64, false);
        be.setChanged();
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        // Recover items left in input slot — only on private-ledger path
        if (!usingSharedLedger && be != null && network != null) {
            IItemHandler handler = getHandler(be);
            if (handler != null) {
                ItemStack input = handler.extractItem(1, 64, false);
                if (!input.isEmpty()) {
                    network.insertItem(input.copy(), input.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                }
            }
        }
        forceChunkLoad(false);
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        forceChunkLoad(false);
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack result = recipe == null || myLevel == null ? ItemStack.EMPTY
                : ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
        return result.isEmpty() ? null : new ExpectedProduction(result, result.getCount());
    }

    // ── helpers ──

    private static IItemHandler getHandler(BlockEntity be) {
        LazyOptional<IItemHandler> cap = be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null);
        return cap.resolve().orElse(null);
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Comp] Chunk load failed", e);
        }
    }
}
