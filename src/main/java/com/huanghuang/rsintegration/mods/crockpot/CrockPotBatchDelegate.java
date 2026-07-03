package com.huanghuang.rsintegration.mods.crockpot;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.network.RSIntegration;
import com.huanghuang.rsintegration.recipe.CrockPotRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CrockPotBatchDelegate implements com.huanghuang.rsintegration.crafting.batch.IBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private INetwork network;
    private boolean craftDone;
    private boolean usingSharedLedger;
    private int potLevel;

    // Cached reflection handles
    private static volatile Field itemHandlerField;
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
        this.potLevel = getPotLevel(found);

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] validateAndInit OK: recipe={} potLevel={}", recipeId, potLevel);
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

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] BlockEntity missing at {}", myPos);
            return false;
        }
        if (!be.getClass().getName().equals("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity")) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Wrong BE type: {}", be.getClass().getName());
            return false;
        }

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null || itemHandler.getSlots() < 6) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Cannot access item handler");
            return false;
        }

        // Force chunk load so serverTick keeps running
        forceChunkLoad(true);

        // ── Phase 1: Insert materials into input slots (0..potLevel-1) ──
        // Each material's count controls how many consecutive slots it fills.
        // Real ingredients typically have count=1 (one slot each); filler items
        // have count equal to the number of remaining pot slots to pad.
        int slot = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            int count = mat.getCount();
            for (int i = 0; i < count; i++) {
                if (slot >= potLevel) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Too many items for potLevel={}", potLevel);
                    break;
                }
                ItemStack single = mat.copyWithCount(1);
                ItemStack remainder = itemHandler.insertItem(slot, single, false);
                if (!remainder.isEmpty()) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Failed to insert into slot {}: {}", slot,
                            remainder.getHoverName().getString());
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
        }
        be.setChanged();

        // ── Phase 2: Ensure fuel ──
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        ItemStack fuelSlot = itemHandler.getStackInSlot(4);
        if (fuelSlot.isEmpty() || !isFuel(fuelSlot)) {
            if (network != null) {
                ItemStack fuel = extractFuel(network, player);
                if (!fuel.isEmpty()) {
                    ItemStack fuelRemainder = itemHandler.insertItem(4, fuel, false);
                    if (!fuelRemainder.isEmpty() && network != null) {
                        network.insertItem(fuelRemainder, fuelRemainder.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    }
                    be.setChanged();
                }
            }
        }

        // If still no fuel and not already burning, try one more time
        if (!isBurning(be)) {
            ItemStack fuelNow = itemHandler.getStackInSlot(4);
            if (!isFuel(fuelNow) && network != null) {
                ItemStack fuel = extractFuel(network, player);
                if (!fuel.isEmpty()) {
                    ItemStack fuelRemainder = itemHandler.insertItem(4, fuel, false);
                    if (!fuelRemainder.isEmpty() && network != null) {
                        network.insertItem(fuelRemainder, fuelRemainder.getCount(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    }
                    be.setChanged();
                } else {
                    // No fuel available — refund materials already inserted.
                    for (int back = 0; back < potLevel; back++) {
                        ItemStack refund = itemHandler.extractItem(back, 64, false);
                        if (!refund.isEmpty() && !usingSharedLedger && network != null)
                            network.insertItem(refund, refund.getCount(),
                                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    }
                    player.sendSystemMessage(Component.translatable("rsi.crockpot.no_fuel"));
                    return false;
                }
            }
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] Materials inserted, cooking should start next tick");
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        BlockEntity be = level.getBlockEntity(myPos);
        if (be == null) return false;
        if (!be.getClass().getName().equals("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity"))
            return false;

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null) return false;

        ItemStack output = itemHandler.getStackInSlot(5);
        return !output.isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getItemHandler(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        ItemStack result = itemHandler.extractItem(5, 64, false);
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
        // Defense in depth: if the machine still has leftover items (e.g. craft
        // was interrupted), refund them to avoid item loss or duplication.
        clearMachineSlotsAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    private void clearMachineSlotsAndRefund() {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return;
        if (!be.getClass().getName().equals("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity"))
            return;

        IItemHandler handler = getItemHandler(be);
        if (handler == null || handler.getSlots() < 6) return;

        // Clear input slots (0 .. potLevel-1)
        for (int slot = 0; slot < potLevel; slot++) {
            ItemStack s = handler.extractItem(slot, 64, false);
            if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
        }
        // Clear output slot 5
        ItemStack out = handler.extractItem(5, 64, false);
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

    // ── plan warnings (called from GenericCraftPacket.tryBuildPlan) ──

    /**
     * Add fuel items to the plan material requirements for CrockPot recipes.
     * No-op for non-CrockPot recipe types.
     */
    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {
        if (!"crockpot".equals(recipeModTypeId)) return;
        CraftPacketUtils.addFuelToMaterials(
                itemAvailable, itemSource, neededCounts, repeatCount);
    }

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        int potLevel = getPotLevel(recipe);
        int slotReqs = CrockPotRecipeHandler.countSlotRequirements(recipe);
        int fillerCount = potLevel - slotReqs;

        if (fillerCount > 0) {
            String fillerId = RSIntegrationConfig.CROCKPOT_FILLER_ITEM.get();
            warnings.add(Component.translatable("rsi.crockpot.filler_needed",
                    fillerCount, fillerId).getString());
        }

        if (CrockPotRecipeHandler.hasCategoryConstraints(recipe)) {
            warnings.add(Component.translatable("rsi.crockpot.category_warning").getString());
        }

        warnings.add(Component.translatable("rsi.crockpot.fuel_warning").getString());

        return warnings;
    }

    // ── reflection helpers ──

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            Class<?> beClass = Class.forName("com.sihenzhang.crockpot.block.entity.CrockPotBlockEntity");
            itemHandlerField = beClass.getDeclaredField("itemHandler");
            itemHandlerField.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CrockPot] Reflection probe failed: {}", e.toString());
        }
    }

    @Nullable
    private static IItemHandler getItemHandler(BlockEntity be) {
        probeReflection();
        if (itemHandlerField == null) return null;
        try {
            return (IItemHandler) itemHandlerField.get(be);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isBurning(BlockEntity be) {
        try {
            Method m = be.getClass().getMethod("isBurning");
            return (boolean) m.invoke(be);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isFuel(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.getBurnTime(null) > 0;
    }

    private static ItemStack extractFuel(INetwork network, ServerPlayer player) {
        // Pick the most abundant burnable item (consistent with plan's
        // addFuelToMaterials).  Prefer vanilla coal over mod fuels when
        // counts are close to avoid grabbing Aether/Embers fuel blocks.
        ItemStack best = ItemStack.EMPTY;
        int bestScore = 0;
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            int burnTime = stack.getBurnTime(null);
            if (burnTime <= 0) continue;
            // Score = count, with a bonus for vanilla coal/charcoal so they
            // beat exotic mod fuels unless the mod fuel is overwhelmingly abundant.
            int score = stack.getCount();
            net.minecraft.resources.ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (rl != null && "minecraft".equals(rl.getNamespace())) score += 64;
            if (score > bestScore) {
                bestScore = score;
                best = stack;
            }
        }
        if (!best.isEmpty()) {
            var extracted = network.extractItem(best.copyWithCount(1), 1,
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            if (!extracted.isEmpty()) return extracted;
        }
        return ItemStack.EMPTY;
    }

    private static int getPotLevel(Recipe<?> recipe) {
        try {
            Method m = recipe.getClass().getMethod("getPotLevel");
            int level = (int) m.invoke(recipe);
            return level > 0 ? level : 4; // 0 means "any level" → use 4 (basic pot)
        } catch (Exception e) {
            return 4;
        }
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-CrockPot] Chunk load failed: {}", e.toString());
        }
    }
}
