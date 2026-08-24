package com.huanghuang.rsintegration.mods.avaritia;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
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
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;

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
    private int machineTier;
    private int recipeTier;

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

        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
        machineTier = machineTier(blockId);
        recipeTier = recipeTier(found);
        if (machineTier > 0 && recipeTier > 0 && machineTier != recipeTier) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Batch-CT] Tier mismatch: recipe={} requires tier {} but {} at {} is tier {}",
                    recipeId, recipeTier, blockId, pos, machineTier);
            return false;
        }

        // Determine expected grid size from the BE
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;
        IItemHandler handler = getHandler(be);
        if (handler != null) {
            gridSlots = handler.getSlots();
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CT] validateAndInit OK: recipe={} pos={} block={} tier={}/{} gridSlots={}",
                recipeId, pos, blockId, recipeTier, machineTier, gridSlots);
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe != null && recipe.getClass().getName().endsWith("ShapedTableCraftingRecipe")) {
            // Avaritia's shaped matcher reads the complete square handler and
            // preserves empty cells. Keep those cells in the material contract
            // so a pattern is inserted at its original coordinates.
            List<IngredientSpec> layout = new ArrayList<>();
            for (var ingredient : recipe.getIngredients()) {
                layout.add(new IngredientSpec(ingredient, ingredient.isEmpty() ? 0 : 1));
            }
            return layout;
        }
        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null) {
            return handler.getIngredients(recipe);
        }
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.delegateResult();
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            var network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (network == null && !hasStorageAccess()) return false;
            ledger.setStorageEndpoint(storageEndpoint());

            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) {
                    materials.add(ItemStack.EMPTY);
                    continue;
                }
                ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                        player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                if (reserved.isEmpty()) {
                    return false;
                }
                materials.add(reserved.copy());
            }

            if (!ledger.commit(network, player)) return false;
            return tryStartWithMaterials(player, materials, ledger);
        }
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (!myLevel.hasChunkAt(myPos)) return false;

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

        // Clear existing grid contents — refund to RS to prevent item loss
        refundGrid(handler, player);

        // Insert materials into grid slots
        for (int slot = 0; slot < materials.size(); slot++) {
            ItemStack mat = materials.get(slot);
            if (slot >= handler.getSlots()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CT] Grid overflow at {}: need > {} slots", myPos, handler.getSlots());
                return false;
            }
            if (mat == null || mat.isEmpty()) continue;
            ItemStack remainder = handler.insertItem(slot, mat.copy(), false);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CT] Failed to insert into slot {} at {}", slot, myPos);
                refundGrid(handler, player);
                return false;
            }
        }

        // Verify recipe matches and assemble
        try {
            Method matchMethod = recipe.getClass().getMethod("matches", IItemHandler.class);
            boolean matched = (boolean) matchMethod.invoke(recipe, handler);
            if (!matched) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CT] Recipe mismatch at {}", myPos);
                refundGrid(handler, player);
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
                    if (hasStorageAccess()) {
                        for (ItemStack rem : remains) {
                            if (!rem.isEmpty()) {
                                insertIntoStorage(player, rem.copy(), false);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.error("[RSI-Batch-CT] Assemble failed at {}", myPos, e);
            refundGrid(handler, player);
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
    public void onBatchFinished(@NotNull ServerPlayer player) {
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

    /**
     * Failure-path grid clear: empties every slot and returns the contents to
     * the RS network (falling back to the player if the network cannot accept
     * the remainder), so materials extracted from RS are never destroyed.
     */
    private void refundGrid(IItemHandler handler, ServerPlayer player) {
        // Shared ledger handles refund — do not double-insert from grid
        if (usingSharedLedger) {
            clearGrid(handler);
            return;
        }
        var net = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack extracted = handler.extractItem(i, 64, false);
            if (extracted.isEmpty()) continue;
            ItemStack remainder = extracted;
            if (net != null) {
                remainder = net.insertItem(extracted, extracted.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            }
            if (remainder != null && !remainder.isEmpty()
                    && player != null && !player.hasDisconnected()) {
                ItemHandlerHelper.giveItemToPlayer(player, remainder);
            }
        }
    }

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }

    /** Avaritia's four tiered tables use one block entity class, so the block ID
     * is the authoritative machine identity for recipe routing. */
    public static int machineTier(@Nullable ResourceLocation blockId) {
        if (blockId == null || !"avaritia".equals(blockId.getNamespace())) return 0;
        return switch (blockId.getPath()) {
            case "sculk_crafting_table" -> 1;
            case "nether_crafting_table" -> 2;
            case "end_crafting_table" -> 3;
            case "extreme_crafting_table" -> 4;
            default -> 0;
        };
    }

    /** Re-Avaritia exposes the required tier on both shaped and shapeless table recipes. */
    public static int recipeTier(@Nullable Recipe<?> recipe) {
        if (recipe == null) return 0;
        try {
            Method method = recipe.getClass().getMethod("getTier");
            Object value = method.invoke(recipe);
            return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return 0;
        }
    }
}
