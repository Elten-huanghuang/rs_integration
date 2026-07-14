package com.huanghuang.rsintegration.mods.immortalersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
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

/** Batch delegate for Immortal's Delight Enchantal Cooler. */
public final class EnchantalCoolerBatchDelegate extends AbstractBatchDelegate {

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
    private boolean craftDone;

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
        if (!itemHandler.getStackInSlot(OUTPUT_SLOT).isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Output slot occupied at {}", myPos);
            return false;
        }

        forceChunkLoad(true);

        long materialCount = materials.stream().filter(s -> !s.isEmpty()).count();
        if (materialCount > INPUT_SLOTS) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Recipe {} has {} ingredients but only {} input slots",
                    recipe.getId(), materialCount, INPUT_SLOTS);
            forceChunkLoad(false);
            return false;
        }

        // Phase 1: Insert ingredients into input slots 0..3
        int slot = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
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

        // Phase 3: Insert container (e.g. bowl/cup) into CONTAINER_SLOT. Like FD's
        // cooking pot, the cooler only moves the finished meal to the output slot
        // when the meal's container is satisfied. Bowl/cup foods (maggot_9 etc.)
        // declare no explicit recipe container, so derive it from the result
        // item's crafting remainder — without it the meal stays stuck internally
        // and is never recovered into RS.
        ItemStack container = getContainerItem(recipe);
        if (!container.isEmpty()) {
            ItemStack existing = itemHandler.getStackInSlot(CONTAINER_SLOT);
            if (!existing.isEmpty() && !ItemStack.isSameItemSameTags(existing, container)) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Recipe {} needs container {}, but slot contains {}",
                        recipe.getId(), container, existing);
                rollbackInputs(itemHandler, slot);
                be.setChanged();
                return false;
            }
            if (existing.isEmpty()) {
                if (network == null) {
                    rollbackInputs(itemHandler, slot);
                    be.setChanged();
                    return false;
                }
                ItemStack extracted = network.extractItem(container.copyWithCount(1), 1,
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                if (extracted.isEmpty()) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Required container unavailable for recipe {}: {}",
                            recipe.getId(), container);
                    rollbackInputs(itemHandler, slot);
                    be.setChanged();
                    return false;
                }
                ItemStack simulated = itemHandler.insertItem(CONTAINER_SLOT, extracted, true);
                if (!simulated.isEmpty()) {
                    refundToRSNetwork(extracted);
                    rollbackInputs(itemHandler, slot);
                    be.setChanged();
                    return false;
                }
                ItemStack remainder = itemHandler.insertItem(CONTAINER_SLOT, extracted, false);
                if (!remainder.isEmpty()) {
                    refundToRSNetwork(remainder);
                    rollbackInputs(itemHandler, slot);
                    be.setChanged();
                    return false;
                }
                be.setChanged();
            }
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Cooler] Materials inserted, cooling should start next tick");
        return true;
    }

    /** The container explicitly declared by this recipe. EMPTY means the
     *  cooler does not consume a container for the craft. */
    private static ItemStack getContainerItem(Recipe<?> recipe) {
        if (recipe == null) return ItemStack.EMPTY;
        try {
            java.lang.reflect.Method m = recipe.getClass().getMethod("getContainer");
            Object r = m.invoke(recipe);
            if (r instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Cooler] getContainer failed", e); }
        try {
            java.lang.reflect.Field f = recipe.getClass().getDeclaredField("container");
            f.setAccessible(true);
            Object v = f.get(recipe);
            if (v instanceof ItemStack s && !s.isEmpty()) return s;
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Cooler] container field failed", e); }
        return ItemStack.EMPTY;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!ImmersalsDelightReflection.enchantalCoolerBEClass.isInstance(be)) return false;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return false;

        ItemStack output = itemHandler.getStackInSlot(OUTPUT_SLOT);
        boolean inputsEmpty = true;
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            inputsEmpty &= itemHandler.getStackInSlot(slot).isEmpty();
        }
        return !output.isEmpty() || inputsEmpty;
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
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
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
        // Container is out-of-band (not in shared ledger) — refund unconditionally
        ItemStack container = handler.extractItem(CONTAINER_SLOT, 64, false);
        if (!container.isEmpty()) refundToRSNetwork(container);
        ItemStack out = handler.extractItem(OUTPUT_SLOT, 64, false);
        // Output is not part of the shared input ledger; do not discard it during cleanup.
        if (!out.isEmpty()) refundToRSNetwork(out);
        // Fuel is out-of-band (not in shared ledger) — refund unconditionally
        ItemStack fuel = handler.extractItem(FUEL_SLOT, 64, false);
        if (!fuel.isEmpty()) refundToRSNetwork(fuel);
        be.setChanged();
    }

    private void rollbackInputs(IItemHandler handler, int insertedSlots) {
        for (int back = 0; back < insertedSlots; back++) {
            ItemStack refund = handler.extractItem(back, 64, false);
            if (!refund.isEmpty() && !usingSharedLedger) refundToRSNetwork(refund);
        }
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

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack result = recipe == null || myLevel == null ? ItemStack.EMPTY
                : ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
        return result.isEmpty() ? null : new ExpectedProduction(result, result.getCount());
    }

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
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Reflection probe failed", e);
        }
    }

    private static IItemHandler getInventory(BlockEntity be) {
        probeReflection();
        if (inventoryField != null) {
            try {
                return (IItemHandler) inventoryField.get(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Cooler] field access failed", e); }
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
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Cooler] Chunk load failed", e);
        }
    }
}
