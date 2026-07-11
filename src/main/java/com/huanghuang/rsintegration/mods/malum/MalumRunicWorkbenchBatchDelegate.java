package com.huanghuang.rsintegration.mods.malum;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.reflection.probes.MalumReflection;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Batch delegate for Malum Runic Workbench (RuneWorking recipe). */
public final class MalumRunicWorkbenchBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private IItemHandler itemHandler;
    private ItemStack expectedOutput = ItemStack.EMPTY;
    private boolean craftDone;

    // ── IBatchDelegate ──────────────────────────────────────────────

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        if (!MalumReflection.isAvailable()) {
            player.sendSystemMessage(Component.translatable("rsi.batch.error.mod_missing", "Malum"));
            return false;
        }

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
        this.craftDone = false;
        this.expectedOutput = ItemStack.EMPTY;

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !MalumReflection.runicWorkbenchBEClass.isInstance(be)) {
            player.sendSystemMessage(Component.translatable("rsi.malum.error.not_runic_workbench"));
            return false;
        }

        this.itemHandler = resolveHandler(be);
        if (itemHandler == null) {
            player.sendSystemMessage(Component.translatable("rsi.malum.error.inventory_error"));
            return false;
        }

        // Recover stray items left in the workbench
        ItemStack existing = itemHandler.getStackInSlot(0);
        if (!existing.isEmpty()) {
            returnStrayItem(existing);
            setSlot(0, ItemStack.EMPTY);
            be.setChanged();
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Runic] validateAndInit OK: recipe={}", recipeId);
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        var handler = ModRecipeHandlers.handlerFor(recipe);
        if (handler != null) {
            return handler.getIngredients(recipe);
        }
        return CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        this.player = player;
        this.ledger = new ExtractionLedger();
        this.usingSharedLedger = false;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.craftDone = false;

        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;
        // specs order: [primaryInput, secondaryInput]
        if (specs.size() < 2) return false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !MalumReflection.runicWorkbenchBEClass.isInstance(be)) return false;
        this.itemHandler = resolveHandler(be);
        if (itemHandler == null) return false;

        if (!itemHandler.getStackInSlot(0).isEmpty()) return false;

        // Compute expected output before extraction
        this.expectedOutput = computeResult();
        if (expectedOutput.isEmpty()) return false;

        // Phase 1: reserve both inputs
        List<ItemStack> reserved = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
            if (stack.isEmpty()) return false; // ledger not committed — nothing lost
            reserved.add(stack);
        }

        // Phase 2: commit
        if (!ledger.commit(network, player)) return false;

        // Phase 3: execute craft
        // Place primary on workbench
        IngredientSpec primary = specs.get(0);
        ItemStack primaryStack = reserved.get(0);
        if (primary.count() > primaryStack.getCount()) {
            // Need more items for the required count
            primaryStack.setCount(primary.count());
        }
        setSlot(0, primaryStack);
        be.setChanged();

        // Craft is instant — consume primary from slot, produce output
        ItemStack slotItem = itemHandler.getStackInSlot(0);
        if (slotItem.getCount() >= primary.count()) {
            slotItem.shrink(primary.count());
            if (slotItem.isEmpty()) {
                setSlot(0, ItemStack.EMPTY);
            } else {
                setSlot(0, slotItem);
            }
        }
        // Place output in slot
        setSlot(0, expectedOutput.copy());
        be.setChanged();

        // Refund secondary input — extracted from RS but the runic workbench
        // doesn't consume it via the workbench slot.
        if (reserved.size() > 1 && !reserved.get(1).isEmpty()) {
            refundItem(reserved.get(1).copy());
        }

        this.craftDone = true;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Runic] Craft started (instant): recipe={}", recipe.getId());
        return true;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.ledger = sharedLedger;
        this.usingSharedLedger = true;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        this.craftDone = false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !MalumReflection.runicWorkbenchBEClass.isInstance(be)) return false;
        this.itemHandler = resolveHandler(be);
        if (itemHandler == null) return false;

        if (!itemHandler.getStackInSlot(0).isEmpty()) return false;

        this.expectedOutput = computeResult();
        if (expectedOutput.isEmpty()) return false;

        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.size() < 2) return false;

        // Materials order: [primaryInput, secondaryInput]
        if (materials.size() < 1) return false;
        IngredientSpec primary = specs.get(0);
        ItemStack primaryStack = materials.get(0).copy();
        if (primaryStack.getCount() < primary.count()) {
            primaryStack.setCount(primary.count());
        }
        setSlot(0, primaryStack);
        be.setChanged();

        // Execute craft: consume primary, place output
        ItemStack slotItem = itemHandler.getStackInSlot(0);
        if (slotItem.getCount() >= primary.count()) {
            slotItem.shrink(primary.count());
            setSlot(0, slotItem.isEmpty() ? ItemStack.EMPTY : slotItem);
        }
        setSlot(0, expectedOutput.copy());
        be.setChanged();

        // Secondary input (materials[1]) was committed from RS by the caller but
        // the runic workbench only consumes the primary — refund it so it is not lost.
        if (materials.size() > 1 && !materials.get(1).isEmpty()) {
            refundItem(materials.get(1).copy());
        }

        this.craftDone = true;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Runic] Craft started with materials: recipe={}", recipe.getId());
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return craftDone;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        ItemStack result = ItemStack.EMPTY;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be != null && MalumReflection.runicWorkbenchBEClass.isInstance(be)) {
            IItemHandler handler = resolveHandler(be);
            if (handler != null) {
                result = handler.getStackInSlot(0).copy();
                if (!result.isEmpty()) {
                    setSlot(0, ItemStack.EMPTY);
                    be.setChanged();
                }
            }
        }
        if (result.isEmpty()) {
            // Fallback: scan for ItemEntity near workbench
            if (!expectedOutput.isEmpty() && myLevel.isLoaded(myPos)) {
                List<ItemEntity> entities = myLevel.getEntitiesOfClass(ItemEntity.class,
                        new AABB(myPos).inflate(3),
                        e -> ItemStack.isSameItemSameTags(e.getItem(), expectedOutput)
                                || ItemStack.isSameItem(e.getItem(), expectedOutput));
                if (!entities.isEmpty()) {
                    ItemEntity entity = entities.get(0);
                    result = entity.getItem().copy();
                    entity.getItem().shrink(result.getCount());
                    entity.setItem(entity.getItem().copy());
                    if (entity.getItem().isEmpty()) entity.discard();
                }
            }
        }
        craftDone = false;
        expectedOutput = ItemStack.EMPTY;
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        craftDone = false;
        if (MalumReflection.runicWorkbenchBEClass.isInstance(be) && itemHandler != null) {
            ItemStack slot = itemHandler.getStackInSlot(0);
            if (!slot.isEmpty()) {
                refundItem(slot);
                setSlot(0, ItemStack.EMPTY);
            }
            be.setChanged();
        }
        reset();
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        // Clear any leftover from slot 0
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be != null && !be.isRemoved()) {
            IItemHandler handler = resolveHandler(be);
            if (handler != null && !handler.getStackInSlot(0).isEmpty()) {
                setSlot(0, ItemStack.EMPTY);
                be.setChanged();
            }
        }
        reset();
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // ── helpers ─────────────────────────────────────────────────────

    private static IItemHandler resolveHandler(BlockEntity be) {
        LazyOptional<IItemHandler> cap = be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null);
        if (cap.isPresent()) return cap.orElse(null);

        // Fallback: reflection on inventory field
        try {
            java.lang.reflect.Field f = be.getClass().getSuperclass().getDeclaredField("inventory");
            f.setAccessible(true);
            Object inv = f.get(be);
            if (inv instanceof IItemHandler handler) return handler;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Runic] Inventory fallback reflection failed", e);
        }
        return null;
    }

    private void setSlot(int slot, ItemStack stack) {
        if (itemHandler == null) return;
        try {
            itemHandler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class)
                    .invoke(itemHandler, slot, stack);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Runic] setSlot failed", e);
        }
    }

    private ItemStack computeResult() {
        return RecipeIndex.tryGetResultItem(recipe, myLevel.registryAccess()).copy();
    }

    private void returnStrayItem(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (network == null) {
            network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        }
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty() && player != null && !player.hasDisconnected()) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null && !player.hasDisconnected()) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    private void refundItem(ItemStack stack) {
        if (stack.isEmpty()) return;
        if (ledger != null && ledger.isCommitted() && network != null) {
            network.insertItem(stack.copy(), stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        } else if (player != null && !player.hasDisconnected()) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    private void reset() {
        expectedOutput = ItemStack.EMPTY;
        craftDone = false;
        network = null;
        itemHandler = null;
    }
}
