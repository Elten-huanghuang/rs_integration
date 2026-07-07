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
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Real batch delegate for Avaritia crafting tables (Compressed, Double,
 * Nether, End, Sculk, Extreme). Inserts materials into the table's
 * inventory grid via IItemHandler, calls recipe.matches() and
 * recipe.assemble() to produce the result (instant craft).
 */
public final class CraftingTableBatchDelegate extends AbstractBatchDelegate {

    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private ItemStack cachedResult = ItemStack.EMPTY;
    private boolean craftDone;
    private int gridSlots;

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
        craftDone = false;

        // Determine expected grid size from the BE
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;
        IItemHandler handler = getHandler(be);
        if (handler != null) {
            gridSlots = handler.getSlots();
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CT] validateAndInit OK: recipe={} pos={}", recipeId, pos);
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
        ExtractionLedger ledger = new ExtractionLedger();
        var network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (network == null) return false;

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
            if (reserved.isEmpty()) {
                ledger.rollback(player);
                return false;
            }
            materials.add(reserved.copy());
        }

        if (!ledger.commit(network, player)) return false;
        return tryStartWithMaterials(player, materials, ledger);
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        myLevel.getChunk(myPos);

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return false;

        String beClassName = be.getClass().getName();
        if (!beClassName.equals("committee.nova.mods.avaritia.common.tile.TierCraftTile")) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CT] Not a TierCraftTile at {}", myPos);
            return false;
        }

        IItemHandler handler = getHandler(be);
        if (handler == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CT] No IItemHandler at {}", myPos);
            return false;
        }

        forceChunkLoad(true);

        // Clear existing grid contents (defensive)
        for (int i = 0; i < handler.getSlots(); i++) {
            handler.extractItem(i, 64, false);
        }

        // Insert materials into grid slots
        int slot = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            if (slot >= handler.getSlots()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CT] Grid overflow at {}: need > {} slots", myPos, handler.getSlots());
                return false;
            }
            ItemStack remainder = handler.insertItem(slot, mat.copy(), false);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CT] Failed to insert into slot {} at {}", slot, myPos);
                clearGrid(handler);
                return false;
            }
            slot++;
        }

        // Verify recipe matches and assemble
        try {
            Method matchMethod = recipe.getClass().getMethod("matches", IItemHandler.class);
            boolean matched = (boolean) matchMethod.invoke(recipe, handler);
            if (!matched) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CT] Recipe mismatch at {}", myPos);
                clearGrid(handler);
                return false;
            }

            Method assembleMethod = recipe.getClass().getMethod("assemble", IItemHandler.class);
            cachedResult = ((ItemStack) assembleMethod.invoke(recipe, handler)).copy();

            // Handle remaining items (e.g. buckets from milk recipes)
            Method remainMethod = null;
            try {
                remainMethod = recipe.getClass().getMethod("getRemainingItems", IItemHandler.class);
            } catch (NoSuchMethodException e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-CT] getRemainingItems not found", e); }
            if (remainMethod != null) {
                @SuppressWarnings("unchecked")
                List<ItemStack> remains = (List<ItemStack>) remainMethod.invoke(recipe, handler);
                if (remains != null) {
                    var network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
                    if (network != null) {
                        for (ItemStack rem : remains) {
                            if (!rem.isEmpty()) {
                                network.insertItem(rem.copy(), rem.getCount(),
                                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-CT] Assemble failed at {}", myPos, e);
            clearGrid(handler);
            return false;
        }

        clearGrid(handler);
        craftDone = true;
        be.setChanged();
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CT] Craft complete at {}, result={}", myPos, cachedResult.getDisplayName().getString());
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return craftDone;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        craftDone = false;
        ItemStack r = cachedResult.copy();
        cachedResult = ItemStack.EMPTY;
        return r;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        forceChunkLoad(false);
        cachedResult = ItemStack.EMPTY;
        craftDone = false;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        forceChunkLoad(false);
        cachedResult = ItemStack.EMPTY;
        craftDone = false;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // ── helpers ──

    private static IItemHandler getHandler(BlockEntity be) {
        LazyOptional<IItemHandler> cap = be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null);
        return cap.resolve().orElse(null);
    }

    private void clearGrid(IItemHandler handler) {
        for (int i = 0; i < handler.getSlots(); i++) {
            handler.extractItem(i, 64, false);
        }
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-CT] Chunk load failed", e);
        }
    }
}
