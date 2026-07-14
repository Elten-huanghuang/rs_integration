package com.huanghuang.rsintegration.mods.crabbersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.reflection.probes.CrabbersDelightReflection;
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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Batch delegate for Crabber's Delight Crab Traps. */
public final class CrabTrapBatchDelegate extends AbstractBatchDelegate {

    private static final int BAIT_SLOT = 0;
    private static final int OUTPUT_START = 1;
    private static final int OUTPUT_END = 28;
    private static final int TOTAL_SLOTS = 28;

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private CrabTrapLootWrapper wrapper;
    private boolean craftDone;

    private static volatile Field inventoryField;
    private static volatile boolean reflectionProbed;

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

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !CrabbersDelightReflection.crabTrapBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-CrabTrap] Not a CrabTrapBlockEntity at {}", pos);
            player.sendSystemMessage(Component.translatable("rsi.crabtrap.error.not_crab_trap"));
            return false;
        }

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found instanceof CrabTrapLootWrapper cbw) {
            this.wrapper = cbw;
        } else {
            // Try resolver directly
            found = CrabTrapRecipeResolver.resolveRecipe(level, recipeId);
            if (found instanceof CrabTrapLootWrapper cbw2) {
                this.wrapper = cbw2;
            } else {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found",
                        recipeId.toString()));
                return false;
            }
        }

        this.craftDone = false;
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network == null) return false;

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

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (wrapper == null) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        specs.add(new IngredientSpec(Ingredient.of(wrapper.baitItem()), 1));
        return specs;
    }

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.craftDone = false;

        if (materials.isEmpty() || wrapper == null) return false;

        myLevel.getChunk(myPos);

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !CrabbersDelightReflection.crabTrapBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-CrabTrap] BlockEntity missing at {}", myPos);
            return false;
        }

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null || itemHandler.getSlots() < TOTAL_SLOTS) {
            RSIntegrationMod.LOGGER.warn("[RSI-CrabTrap] Cannot access item handler");
            return false;
        }

        forceChunkLoad(true);

        // Insert bait into slot 0
        ItemStack baitStack = materials.get(0).copyWithCount(1);
        ItemStack remainder = itemHandler.insertItem(BAIT_SLOT, baitStack, false);
        if (!remainder.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-CrabTrap] Failed to insert bait: {}",
                    remainder.getHoverName().getString());
            player.sendSystemMessage(Component.translatable("rsi.crabtrap.error.bait_insert_failed"));
            forceChunkLoad(false);
            return false;
        }
        be.setChanged();

        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        RSIntegrationMod.LOGGER.debug("[RSI-CrabTrap] Bait inserted, waiting for loot");
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (craftDone) return true;
        if (!CrabbersDelightReflection.crabTrapBEClass.isInstance(be)) return false;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return false;

        // Bait consumed = slot 0 empty
        ItemStack baitSlot = itemHandler.getStackInSlot(BAIT_SLOT);
        if (!baitSlot.isEmpty()) {
            // Still waiting — bait not consumed yet
            return false;
        }

        // Check if any output slots have items
        for (int slot = OUTPUT_START; slot < OUTPUT_END; slot++) {
            if (!itemHandler.getStackInSlot(slot).isEmpty()) {
                return true;
            }
        }

        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        // Collect all output items
        List<ItemStack> outputs = new ArrayList<>();
        for (int slot = OUTPUT_START; slot < OUTPUT_END; slot++) {
            ItemStack s = itemHandler.extractItem(slot, 64, false);
            if (!s.isEmpty()) {
                outputs.add(s);
            }
        }
        be.setChanged();
        craftDone = true;

        if (outputs.isEmpty()) return ItemStack.EMPTY;

        // Return first item as the "result", deposit rest to RS
        ItemStack primary = outputs.get(0);
        if (network != null) {
            for (int i = 1; i < outputs.size(); i++) {
                network.insertItem(outputs.get(i), outputs.get(i).getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            }
        }
        return primary;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (!CrabbersDelightReflection.crabTrapBEClass.isInstance(be)) return;

        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < TOTAL_SLOTS) return;

        // Refund unconsumed bait
        ItemStack bait = handler.extractItem(BAIT_SLOT, 64, false);
        if (!bait.isEmpty() && !usingSharedLedger) refundToRSNetwork(bait);

        // Refund uncollected output
        for (int slot = OUTPUT_START; slot < OUTPUT_END; slot++) {
            ItemStack s = handler.extractItem(slot, 64, false);
            if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
        }
        be.setChanged();
        forceChunkLoad(false);
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        clearMachineSlotsAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    private void clearMachineSlotsAndRefund() {
        myLevel.getChunk(myPos);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return;
        if (!CrabbersDelightReflection.crabTrapBEClass.isInstance(be)) return;

        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < TOTAL_SLOTS) return;

        // Refund unconsumed bait
        ItemStack bait = handler.extractItem(BAIT_SLOT, 64, false);
        if (!bait.isEmpty() && !usingSharedLedger) refundToRSNetwork(bait);

        // Refund uncollected output
        for (int slot = OUTPUT_START; slot < OUTPUT_END; slot++) {
            ItemStack s = handler.extractItem(slot, 64, false);
            if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
        }
        be.setChanged();
    }

    private void refundToRSNetwork(ItemStack stack) {
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!leftover.isEmpty() && player != null) {
                net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
        } else if (player != null) {
            net.minecraftforge.items.ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            inventoryField = CrabbersDelightReflection.crabTrapBEClass.getDeclaredField("inventory");
            inventoryField.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-CrabTrap] Reflection probe failed", e);
        }
    }

    private static IItemHandler getInventory(BlockEntity be) {
        probeReflection();
        if (inventoryField != null) {
            try {
                return (IItemHandler) inventoryField.get(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-CrabTrap] field access failed", e); }
        }
        return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CrabTrap] Chunk load failed", e);
        }
    }
}
