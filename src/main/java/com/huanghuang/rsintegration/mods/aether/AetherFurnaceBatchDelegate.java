package com.huanghuang.rsintegration.mods.aether;

import com.huanghuang.rsintegration.network.RSIntegrationNetwork;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.MachineSlotOwnershipPolicy;
import com.huanghuang.rsintegration.mixin.minecraft.AbstractFurnaceAccessor;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
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
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Batch delegate for Aether furnace-type machines (Freezer, Incubator, Altar).
 * <p>
 * Freezer and Altar extend {@link AbstractFurnaceBlockEntity} with slots
 * 0=INPUT / 1=FUEL / 2=OUTPUT.  Incubator extends {@code BaseContainerBlockEntity}
 * with slots 0=INPUT / 1=FUEL and <b>no</b> output slot (it produces entities).
 * <p>
 * All three override their fuel system: the fuel slot accepts items from the
 * machine-specific processing map (freezing / enchanting / incubating), NOT
 * vanilla furnace fuel.  {@link ForgeHooks#getBurnTime} will return 0 for all
 * valid Aether fuel items, so we must use the machine's own
 * {@code getBurnDuration()} (furnace) or {@code getIncubatingMap()} (incubator).
 */
public final class AetherFurnaceBatchDelegate extends AbstractBatchDelegate {

    // Cached incubator fuel map — resolved once via reflection (no SRG names needed
    // because this is a public static method, not a vanilla method).
    @Nullable
    private static volatile Map<Item, Integer> cachedIncubatorMap;
    private static volatile boolean incubatorMapProbed;

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean isIncubator;
    private boolean inventoryLease;
    private ItemStack baselineFuel = ItemStack.EMPTY;
    private ItemStack suppliedInput = ItemStack.EMPTY;
    private int suppliedInputCount;
    private ItemStack suppliedFuel = ItemStack.EMPTY;
    private int suppliedFuelCount;

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
        resetInventoryOwnership();
        BlockEntity be = level.getBlockEntity(pos);
        this.isIncubator = be != null && be.getClass().getName().endsWith(".IncubatorBlockEntity");

        // Validate recipe-machine type match
        if (be != null && !validateMachineForRecipe(be, found)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.aether.error.machine_type_mismatch",
                    found.getClass().getSimpleName(),
                    be.getClass().getSimpleName()));
            return false;
        }
        if (be == null) return false;
        IItemHandler inventory = getInventory(be);
        if (inventory == null || inventory.getSlots() < (isIncubator ? 2 : 3)) return false;
        if (!inventory.getStackInSlot(0).isEmpty()) return false;

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Aether] validateAndInit OK: recipe={} isIncubator={}", recipeId, isIncubator);
        return true;
    }

    /** Ensure the recipe type matches the machine BlockEntity. */
    private static boolean validateMachineForRecipe(BlockEntity be, Recipe<?> recipe) {
        String recipeName = recipe.getClass().getSimpleName().toLowerCase();
        String beName = be.getClass().getSimpleName().toLowerCase();
        if (recipeName.contains("freezable") && !beName.contains("freezer")) return false;
        if (recipeName.contains("incubat") && !beName.contains("incubator")) return false;
        if (recipeName.contains("enchant") && !beName.contains("altar")) return false;
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
            if (!tryStartWithMaterialsImpl(player, materials, false)) {
                // Craft couldn't start — refund materials back to RS.
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
        return tryStartWithMaterialsImpl(player, materials, true);
    }

    private boolean tryStartWithMaterialsImpl(ServerPlayer player, List<ItemStack> materials,
                                              boolean shared) {
        this.player = player;
        this.usingSharedLedger = shared;
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);

        if (!myLevel.hasChunkAt(myPos)) return false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Aether] No BE at {}", myPos);
            return false;
        }

        IItemHandler handler = getInventory(be);
        if (handler == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Aether] No IItemHandler at {}", myPos);
            return false;
        }
        if (!acquireInventory(be, handler)) return false;

        forceChunkLoad(true);

        // ── Phase 1: Insert into input slot (slot 0 for all three machines) ──
        if (!materials.isEmpty() && !materials.get(0).isEmpty()) {
            ItemStack toInsert = materials.get(0).copyWithCount(1);
            if (!handler.insertItem(0, toInsert, true).isEmpty()) {
                resetInventoryOwnership();
                forceChunkLoad(false);
                return false;
            }
            ItemStack remainder = handler.insertItem(0, toInsert, false);
            if (!remainder.isEmpty()) {
                resetInventoryOwnership();
                forceChunkLoad(false);
                return false;
            }
            suppliedInput = toInsert.copyWithCount(1);
            suppliedInputCount = toInsert.getCount();
        }

        // ── Phase 2: Ensure fuel (slot 1 for all three machines) ──
        // Fill the fuel slot rather than inserting a single item: Aether machines
        // consume fuel lazily over the recipe's processing time, and one unit (an
        // Icestone, Ambrosium, etc.) rarely lasts a whole craft. Any unconsumed
        // remainder is refunded to RS when the batch finishes.
        if (network != null) {
            ItemStack insertedFuel = fillFuelSlot(be, handler, network);
            if (!insertedFuel.isEmpty()) {
                suppliedFuel = insertedFuel.copyWithCount(1);
                suppliedFuelCount += insertedFuel.getCount();
            }
        }

        be.setChanged();
        markCraftStarted();
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Aether] Materials inserted at {}, waiting for cooking", myPos);
        return true;
    }

    @Override
    @NotNull
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level,
                                                    @NotNull BlockEntity be) {
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            ItemStack output = furnace.getItem(2);
            if (!output.isEmpty() && !matchesExpectedOutput(output)) {
                return failObservation("Aether furnace output slot contains another item");
            }
        }
        return super.observeMachineCraft(level, be);
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            // Output may already have been extracted after the input was consumed.
            return matchesExpectedOutput(furnace.getItem(2)) || furnace.getItem(0).isEmpty();
        }

        // Incubator: no output slot; completion is detected by cooking progress
        // returning to 0 after having been > 0 (batch runner polls, so we rely on
        // the tick-based auto-eject in the incubator's serverTick or recipe completion)
        // Since incubation produces entities, the RS result is always EMPTY.
        IItemHandler handler = be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
                .orElse(null);
        if (handler == null) return true; // can't check, assume done

        ItemStack input = handler.getStackInSlot(0);
        return input.isEmpty(); // input consumed → craft done
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        if (!myLevel.hasChunkAt(myPos)) return ItemStack.EMPTY;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            ItemStack visible = furnace.getItem(2);
            if (!matchesExpectedOutput(visible)) return ItemStack.EMPTY;
            ExpectedProduction expected = getExpectedProduction();
            int amount = expected == null ? visible.getCount()
                    : Math.min(visible.getCount(), expected.count());
            ItemStack result = visible.copyWithCount(amount);
            ItemStack retained = visible.copy();
            retained.shrink(amount);
            furnace.setItem(2, retained.isEmpty() ? ItemStack.EMPTY : retained);
            furnace.setChanged();
            return result;
        }

        // Incubator: no item output
        return ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        // Clear machine slots to prevent item duplication.
        // In the shared-ledger (chain) path the chain already refunded materials
        // via refundCommitted(), so we just void the machine slots.
        // In the private-ledger (direct) path we need to refund back to RS.
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            removeOwnedInput(furnace, !usingSharedLedger);
            ItemStack slot2 = furnace.getItem(2);
            if (!slot2.isEmpty() && matchesExpectedOutput(slot2)
                    && phase != CraftPhase.WAITING_FOR_START) {
                furnace.setItem(2, ItemStack.EMPTY);
            }
            furnace.setChanged();
        } else {
            IItemHandler handler = getInventory(be);
            if (handler != null) {
                removeOwnedInput(handler, !usingSharedLedger);
            }
        }
        refundLeftoverFuel(be);
        resetInventoryOwnership();
        forceChunkLoad(false);
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        resetInventoryOwnership();
        resetState();
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
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        forceChunkLoad(false);
        if (!myLevel.hasChunkAt(myPos)) {
            resetInventoryOwnership();
            resetState();
            return;
        }
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be != null) refundLeftoverFuel(be);
        resetInventoryOwnership();
        resetState();
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        if (isIncubator || recipe == null || myLevel == null) return null;
        ItemStack result = ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
        return result.isEmpty() ? null : new ExpectedProduction(result, result.getCount());
    }

    // ── plan warnings ──

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        if (recipe.getClass().getSimpleName().equals("IncubationRecipe")) {
            warnings.add(Component.translatable("rsi.aether.incubation_warning"));
        }
        warnings.add(Component.translatable("rsi.aether.fuel_warning"));
        return warnings;
    }

    // ── fuel helpers ──

    /**
     * Check whether {@code stack} is valid fuel for the specific Aether machine.
     * Freezer/Altar override {@code getBurnDuration()} to consult their item→time
     * maps; Incubator uses its own static {@code getIncubatingMap()}.
     */
    private static boolean isValidFuelForMachine(BlockEntity be, ItemStack stack) {
        if (stack.isEmpty()) return false;

        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            try {
                return ((AbstractFurnaceAccessor) furnace).rsi$callGetBurnDuration(stack) > 0;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-Aether] getBurnDuration probe failed, falling back to ForgeHooks", e);
                return ForgeHooks.getBurnTime(stack, null) > 0;
            }
        }

        // Incubator — cached map lookup
        Map<Item, Integer> map = getIncubatorFuelMap();
        return map != null && map.containsKey(stack.getItem());
    }

    /** Resolve the incubator fuel map once, caching the result. */
    private static Map<Item, Integer> getIncubatorFuelMap() {
        if (incubatorMapProbed) return cachedIncubatorMap;
        incubatorMapProbed = true;
        try {
            Class<?> clz = Class.forName("com.aetherteam.aether.blockentity.IncubatorBlockEntity");
            @SuppressWarnings("unchecked")
            Map<Item, Integer> map = (Map<Item, Integer>) clz.getMethod("getIncubatingMap").invoke(null);
            cachedIncubatorMap = map;
            return map;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-Aether] Incubator fuel probe failed", e);
            return null;
        }
    }

    /**
     * Fill fuel slot 1 to a full stack of a single valid fuel type, drawn from RS.
     * Aether machines consume fuel lazily over the recipe's processing time, so a
     * single unit (an Icestone, Ambrosium, etc.) rarely lasts a whole craft. We top
     * the slot up to its stack limit; any unconsumed remainder is refunded to RS when
     * the batch finishes via {@link #refundLeftoverFuel}.
     */
    private static ItemStack fillFuelSlot(BlockEntity be, IItemHandler handler, INetwork network) {
        ItemStack fuelSlot = handler.getStackInSlot(1);

        // Slot occupied by a non-fuel item — leave it alone.
        if (!fuelSlot.isEmpty() && !isValidFuelForMachine(be, fuelSlot)) return ItemStack.EMPTY;

        // Match the existing fuel type, else pick any valid fuel present in RS.
        ItemStack fuelType = fuelSlot.isEmpty() ? findFuelInNetwork(be, network) : fuelSlot;
        if (fuelType.isEmpty()) return ItemStack.EMPTY;

        int slotLimit = Math.min(handler.getSlotLimit(1), fuelType.getMaxStackSize());
        int room = slotLimit - fuelSlot.getCount();
        if (room <= 0) return ItemStack.EMPTY;

        ItemStack extracted = network.extractItem(fuelType.copyWithCount(1), room,
                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        if (extracted.isEmpty()) return ItemStack.EMPTY;

        ItemStack remainder = handler.insertItem(1, extracted, false);
        if (!remainder.isEmpty()) {
            network.insertItem(remainder, remainder.getCount(),
                    com.refinedmods.refinedstorage.api.util.Action.PERFORM);
        }
        int inserted = extracted.getCount() - remainder.getCount();
        return inserted > 0 ? extracted.copyWithCount(inserted) : ItemStack.EMPTY;
    }

    /** Find the first valid fuel type for this machine present in the RS network. */
    private static ItemStack findFuelInNetwork(BlockEntity be, INetwork network) {
        for (var entry : new java.util.ArrayList<>(network.getItemStorageCache().getList().getStacks())) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            if (isValidFuelForMachine(be, stack)) return stack;
        }
        return ItemStack.EMPTY;
    }

    @Nullable
    private static IItemHandler getInventory(BlockEntity be) {
        return be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null)
                .resolve().orElse(null);
    }

    private boolean acquireInventory(BlockEntity be, IItemHandler handler) {
        if (inventoryLease || !handler.getStackInSlot(0).isEmpty()) return false;
        if (!isIncubator) {
            ItemStack priorOutput = handler.getStackInSlot(2);
            if (!priorOutput.isEmpty()) {
                ItemStack removed = handler.extractItem(2, priorOutput.getCount(), false);
                if (removed.isEmpty()) return false;
                refundToRSNetwork(removed);
                be.setChanged();
            }
            if (!handler.getStackInSlot(2).isEmpty()) return false;
        }
        baselineFuel = handler.getStackInSlot(1).copy();
        suppliedInput = ItemStack.EMPTY;
        suppliedInputCount = 0;
        suppliedFuel = ItemStack.EMPTY;
        suppliedFuelCount = 0;
        inventoryLease = true;
        return true;
    }

    private void removeOwnedInput(AbstractFurnaceBlockEntity furnace, boolean refund) {
        if (!inventoryLease) return;
        ItemStack current = furnace.getItem(0);
        int removable = MachineSlotOwnershipPolicy.removableAddedCount(
                ItemStack.EMPTY, suppliedInput, suppliedInputCount, current);
        if (removable <= 0) return;
        ItemStack removed = current.copyWithCount(removable);
        ItemStack retained = current.copy();
        retained.shrink(removable);
        furnace.setItem(0, retained.isEmpty() ? ItemStack.EMPTY : retained);
        if (refund) refundToRSNetwork(removed);
    }

    private void removeOwnedInput(IItemHandler handler, boolean refund) {
        if (!inventoryLease) return;
        ItemStack current = handler.getStackInSlot(0);
        int removable = MachineSlotOwnershipPolicy.removableAddedCount(
                ItemStack.EMPTY, suppliedInput, suppliedInputCount, current);
        if (removable <= 0) return;
        ItemStack removed = handler.extractItem(0, removable, false);
        if (!removed.isEmpty() && refund) refundToRSNetwork(removed);
    }

    private boolean matchesExpectedOutput(ItemStack output) {
        ExpectedProduction expected = getExpectedProduction();
        return expected != null && IBatchDelegate.matchesProducedItem(output, expected.item());
    }

    private void resetInventoryOwnership() {
        inventoryLease = false;
        baselineFuel = ItemStack.EMPTY;
        suppliedInput = ItemStack.EMPTY;
        suppliedInputCount = 0;
        suppliedFuel = ItemStack.EMPTY;
        suppliedFuelCount = 0;
    }

    /** Refund any unconsumed fuel left in slot 1 back to RS (or the player). */
    private void refundLeftoverFuel(BlockEntity be) {
        if (be == null || !inventoryLease) return;
        IItemHandler handler = getInventory(be);
        if (handler == null) return;
        ItemStack fuel = handler.getStackInSlot(1);
        if (fuel.isEmpty() || !isValidFuelForMachine(be, fuel)) return;
        int refundable = MachineSlotOwnershipPolicy.removableAddedCount(
                baselineFuel, suppliedFuel, suppliedFuelCount, fuel);
        if (refundable <= 0) return;
        ItemStack extracted = handler.extractItem(1, refundable, false);
        if (!extracted.isEmpty()) refundToRSNetwork(extracted);
    }

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }
}
