package com.huanghuang.rsintegration.mods.immortalersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.recipe.EnchantalCoolerRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.ImmersalsDelightReflection;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class EnchantalCoolerBatchDelegate implements com.huanghuang.rsintegration.crafting.batch.IBatchDelegate {

    // Slot layout (matching EnchantalCoolerBlockEntity)
    private static final int INPUT_SLOTS = 4;  // 0..3
    private static final int CONTAINER_SLOT = 4;
    private static final int OUTPUT_SLOT = 5;
    private static final int FUEL_SLOT = 6;

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private INetwork network;
    private boolean craftDone;
    private boolean usingSharedLedger;

    // Cached reflection
    private static volatile Field inventoryField;
    private static volatile Field residualDyeField;
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

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        return true;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        ExtractionLedger ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) return false;

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
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.usingSharedLedger = true;
        this.craftDone = false;

        myLevel.getChunk(myPos);

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] BlockEntity missing at {}", myPos);
            return false;
        }
        if (!ImmersalsDelightReflection.enchantalCoolerBEClass.isInstance(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Wrong BE type: {}", be.getClass().getName());
            return false;
        }

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null || itemHandler.getSlots() < 7) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Cannot access item handler");
            return false;
        }

        forceChunkLoad(true);

        // Phase 1: Insert ingredients into input slots 0..3
        int slot = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            if (slot >= INPUT_SLOTS) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Too many ingredients for 4-slot cooler");
                break;
            }
            ItemStack single = mat.copyWithCount(1);
            ItemStack remainder = itemHandler.insertItem(slot, single, false);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Failed to insert into slot {}: {}",
                        slot, remainder.getHoverName().getString());
                for (int back = 0; back < slot; back++) {
                    ItemStack refund = itemHandler.extractItem(back, 64, false);
                    if (!refund.isEmpty() && !usingSharedLedger && network != null)
                        network.insertItem(refund, refund.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                }
                be.setChanged();
                return false;
            }
            slot++;
        }
        be.setChanged();

        // Phase 2: Ensure fuel (lapis lazuli) in slot 6, top up to a full stack
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        ItemStack fuelSlot = itemHandler.getStackInSlot(FUEL_SLOT);
        int existingFuel = fuelSlot.is(Items.LAPIS_LAZULI) ? fuelSlot.getCount() : 0;
        int needed = 64 - existingFuel;
        if (needed > 0 && network != null) {
            int inserted = tryInsertFuelFromRS(itemHandler, needed);
            if (inserted > 0) {
                be.setChanged();
            }
            if (inserted == 0 && !hasResidualDye(be)) {
                for (int back = 0; back < INPUT_SLOTS; back++) {
                    ItemStack refund = itemHandler.extractItem(back, 64, false);
                    if (!refund.isEmpty() && !usingSharedLedger)
                        network.insertItem(refund, refund.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                }
                player.sendSystemMessage(Component.translatable("rsi.cooler.no_fuel"));
                return false;
            }
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Cooler] Materials inserted, cooling should start next tick");
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        BlockEntity be = level.getBlockEntity(myPos);
        if (be == null) return false;
        if (!ImmersalsDelightReflection.enchantalCoolerBEClass.isInstance(be)) return false;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return false;

        ItemStack output = itemHandler.getStackInSlot(OUTPUT_SLOT);
        return !output.isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        ItemStack result = itemHandler.extractItem(OUTPUT_SLOT, 64, false);
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        clearMachineSlotsAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearMachineSlotsAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    private void clearMachineSlotsAndRefund() {
        myLevel.getChunk(myPos);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return;
        if (!ImmersalsDelightReflection.enchantalCoolerBEClass.isInstance(be)) return;

        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < 7) return;

        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack s = handler.extractItem(slot, 64, false);
            if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
        }
        ItemStack out = handler.extractItem(OUTPUT_SLOT, 64, false);
        if (!out.isEmpty() && !usingSharedLedger) refundToRSNetwork(out);
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

    // ── plan helpers ──

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {
        if (!"immortalers_delight".equals(recipeModTypeId)) return;
        int fuelNeeded = Math.max(1, repeatCount / 4);
        neededCounts.merge(Items.LAPIS_LAZULI, fuelNeeded, Integer::sum);
        // Include lapis blocks as an acceptable source — RS recursive
        // resolution will show the decomposition in the plan tree
        itemSource.putIfAbsent(Items.LAPIS_LAZULI,
                Ingredient.of(Items.LAPIS_LAZULI, Items.LAPIS_BLOCK));
    }

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.cooler.fuel_warning").getString());
        return warnings;
    }

    // ── fuel extraction ──

    /**
     * Try to insert up to {@code needed} lapis lazuli into the fuel slot,
     * extracting from RS.  Tries lapis lazuli first, then lapis blocks
     * (1 block = 9 lapis lazuli).  Returns the amount actually inserted.
     */
    private int tryInsertFuelFromRS(IItemHandler handler, int needed) {
        int inserted = 0;

        // 1) Try lapis lazuli directly
        ItemStack lapis = network.extractItem(new ItemStack(Items.LAPIS_LAZULI), needed,
                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        if (!lapis.isEmpty()) {
            ItemStack remainder = handler.insertItem(FUEL_SLOT, lapis, false);
            if (!remainder.isEmpty()) {
                network.insertItem(remainder, remainder.getCount(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            }
            inserted = lapis.getCount() - remainder.getCount();
            if (inserted >= needed) return inserted;
        }

        // 2) Not enough — try lapis blocks
        int stillNeeded = needed - inserted;
        int blocksNeeded = (int) Math.ceil(stillNeeded / 9.0);
        ItemStack blocks = network.extractItem(new ItemStack(Items.LAPIS_BLOCK), blocksNeeded,
                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        if (!blocks.isEmpty()) {
            int totalLapis = blocks.getCount() * 9;
            int toInsert = Math.min(totalLapis, stillNeeded);
            if (inserted > 0) {
                // Fuel slot already has some lapis from step 1 — check what fits
                ItemStack existing = handler.getStackInSlot(FUEL_SLOT);
                int space = 64 - (existing.is(Items.LAPIS_LAZULI) ? existing.getCount() : 0);
                toInsert = Math.min(toInsert, space);
            }
            if (toInsert > 0) {
                ItemStack lapisStack = new ItemStack(Items.LAPIS_LAZULI, toInsert);
                ItemStack remainder = handler.insertItem(FUEL_SLOT, lapisStack, false);
                if (!remainder.isEmpty()) {
                    network.insertItem(remainder, remainder.getCount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                }
                inserted += toInsert - remainder.getCount();
            }
            // Return excess lapis lazuli (from overshoot on block conversion)
            int excess = totalLapis - toInsert;
            if (excess > 0) {
                network.insertItem(new ItemStack(Items.LAPIS_LAZULI, excess), excess,
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            }
        }

        return inserted;
    }

    // ── reflection ──

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            inventoryField = ImmersalsDelightReflection.enchantalCoolerBEClass.getDeclaredField("inventory");
            inventoryField.setAccessible(true);
            residualDyeField = ImmersalsDelightReflection.enchantalCoolerBEClass.getDeclaredField("residualDye");
            residualDyeField.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Reflection probe failed: {}", e.toString());
        }
    }

    @Nullable
    private static IItemHandler getInventory(BlockEntity be) {
        probeReflection();
        if (inventoryField != null) {
            try {
                return (IItemHandler) inventoryField.get(be);
            } catch (Exception ignored) {}
        }
        return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }

    private static boolean hasResidualDye(BlockEntity be) {
        probeReflection();
        if (residualDyeField == null) return false;
        try {
            return residualDyeField.getInt(be) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Cooler] Chunk load failed: {}", e.toString());
        }
    }
}
