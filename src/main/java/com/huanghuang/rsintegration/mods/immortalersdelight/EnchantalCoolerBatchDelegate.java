package com.huanghuang.rsintegration.mods.immortalersdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.common.IdleInventoryEvacuator;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
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
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import org.jetbrains.annotations.NotNull;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Batch delegate for Immortal's Delight Enchantal Cooler. */
public final class EnchantalCoolerBatchDelegate extends AbstractBatchDelegate {

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.machineSlot();
    }

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
    private boolean inventoryLease;
    private boolean craftObservedWorking;
    private final ItemStack[] baselineSlots = new ItemStack[7];
    private final ItemStack[] suppliedSlotTypes = new ItemStack[7];
    private final int[] suppliedSlotCounts = new int[7];
    private int plannedOperations = 1;
    private int activeOperations = 1;

    // Cached reflection
    private static volatile Field inventoryField;
    private static volatile Field residualDyeField;
    private static volatile Field cookingTotalTimeField;
    private static volatile Method isFuelMethod;
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
        this.plannedOperations = 1;
        this.activeOperations = 1;
        resetInventoryLease();

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null || !ImmersalsDelightReflection.enchantalCoolerBEClass.isInstance(be)) {
            return false;
        }
        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < baselineSlots.length) return false;
        return getCookingProgress(be) <= 0;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            CraftStorageEndpoint endpoint = storageEndpoint();
            if (endpoint == null) return false;
            ledger.setStorageEndpoint(endpoint);

            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                ItemStack reserved = ledger.reserve(
                        spec.ingredient(), spec.count(), endpoint, player, myDim, myPos);
                if (reserved.isEmpty()) {
                    return false;
                }
                materials.add(reserved.copy());
            }

            if (!ledger.commit(network, player)) return false;

            this.usingSharedLedger = false;
            if (!tryStartWithMaterialsImpl(player, materials, false)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        insertIntoStorage(player, mat.copy(), false);
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
    public int prepareFlatBatch(int remainingOperations) {
        if (!supportsInputBuffer()) return Math.max(0, Math.min(1, remainingOperations));
        plannedOperations = inputBufferPlan(remainingOperations).operations();
        return plannedOperations;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        plannedOperations = Math.max(1, executions);
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        int capacity = supportsInputBuffer()
                ? inputBufferPlan(Math.max(1, totalOperations)).operations() : 1;
        return ParallelBatchSizing.boundedEvenShare(totalOperations, workerCount,
                Math.max(1, capacity));
    }

    @Override
    public int flatBatchOperationLimit(int configuredLimit) {
        return supportsInputBuffer()
                ? Math.max(Math.max(1, configuredLimit), coolerInputBufferLimit())
                : Math.max(1, configuredLimit);
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return supportsInputBuffer();
    }

    @Override
    public boolean supportsInputBuffer() {
        if (!coolerInputBufferEnabled() || recipe == null || myLevel == null || myPos == null
                || !myLevel.hasChunkAt(myPos)) return false;
        List<IngredientSpec> inputSpecs = EnchantalCoolerRecipeHandler.getInputSpecs(recipe);
        List<IngredientSpec> allSpecs = getRequiredMaterials();
        ItemStack result = resultItem();
        if (inputSpecs == null || inputSpecs.isEmpty() || inputSpecs.size() > INPUT_SLOTS
                || allSpecs == null || result.isEmpty()) return false;
        int expectedSpecs = inputSpecs.size()
                + (EnchantalCoolerRecipeHandler.getContainerItem(recipe).isEmpty() ? 0 : 1);
        if (allSpecs.size() != expectedSpecs) return false;
        return allSpecs.stream().allMatch(spec -> spec != null && !spec.isEmpty()
                && spec.count() == 1 && !ingredientPrototype(spec).isEmpty());
    }

    @Override
    public InputBufferContract inputBufferContract() {
        if (!supportsInputBuffer()) return InputBufferContract.none();
        BlockEntity be = myLevel.getBlockEntity(myPos);
        IItemHandler handler = be == null ? null : getInventory(be);
        if (handler == null || handler.getSlots() < 7) return InputBufferContract.none();
        List<IngredientSpec> inputSpecs = EnchantalCoolerRecipeHandler.getInputSpecs(recipe);
        List<InputBufferContract.InputSlot> inputs = new ArrayList<>();
        int materialIndex = 0;
        for (int slot = 0; slot < inputSpecs.size(); slot++) {
            IngredientSpec spec = inputSpecs.get(slot);
            ItemStack prototype = ingredientPrototype(spec);
            int capacity = Math.min(handler.getSlotLimit(slot), prototype.getMaxStackSize());
            inputs.add(new InputBufferContract.InputSlot("legacy:material:" + materialIndex++,
                    slot, prototype, spec.count(), false, capacity));
        }
        ItemStack container = EnchantalCoolerRecipeHandler.getContainerItem(recipe);
        if (!container.isEmpty()) {
            int capacity = Math.min(handler.getSlotLimit(CONTAINER_SLOT),
                    container.getMaxStackSize());
            inputs.add(new InputBufferContract.InputSlot("legacy:material:" + materialIndex,
                    CONTAINER_SLOT, container, 1, false, capacity));
        }
        ItemStack output = resultItem();
        int outputCapacity = Math.min(handler.getSlotLimit(OUTPUT_SLOT), output.getMaxStackSize());
        int operationLimit = coolerInputBufferLimit();
        for (InputBufferContract.InputSlot input : inputs) {
            operationLimit = Math.min(operationLimit, input.capacity() / input.perOperation());
        }
        operationLimit = Math.min(operationLimit, outputCapacity / output.getCount());
        if (operationLimit <= 0) return InputBufferContract.none();
        return new InputBufferContract(operationLimit, inputs,
                List.of(new OutputContract.Port("immortalers_delight:cooler:output",
                        OUTPUT_SLOT, output, output.getCount(),
                        InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.SLOT)));
    }

    @Override
    public InputBufferPlan inputBufferPlan(int requestedOperations) {
        return inputBufferContract().plan(requestedOperations);
    }

    @Override
    public boolean tryStartWithInputBuffer(@NotNull ServerPlayer player,
                                           @NotNull InputBufferPlan plan,
                                           @NotNull ExtractionLedger sharedLedger) {
        if (!supportsInputBuffer() || plan == null || !plan.enabled()) return false;
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || plan.inputs().size() != specs.size()) return false;
        List<ItemStack> ordered = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            String id = "legacy:material:" + i;
            InputBufferPlan.InputSlot input = plan.inputs().stream()
                    .filter(candidate -> id.equals(candidate.entryId()))
                    .findFirst().orElse(null);
            long required = (long) specs.get(i).count() * plan.operations();
            if (input == null || input.reusable() || input.perOperation() != specs.get(i).count()
                    || required > Integer.MAX_VALUE
                    || input.stack().getCount() != (int) required) return false;
            ordered.add(input.stack().copy());
        }
        InputBufferPlan expected = inputBufferPlan(plan.operations());
        if (!expected.enabled() || expected.operations() != plan.operations()) return false;
        plannedOperations = plan.operations();
        return tryStartWithMaterialsImpl(player, ordered, true);
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
        this.craftDone = false;
        this.craftObservedWorking = false;

        if (!myLevel.hasChunkAt(myPos)) return false;

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
        List<IngredientSpec> inputSpecs = EnchantalCoolerRecipeHandler.getInputSpecs(recipe);
        ItemStack requiredContainer = EnchantalCoolerRecipeHandler.getContainerItem(recipe);
        PreparedMaterials prepared = splitPreparedMaterials(
                materials, inputSpecs != null ? inputSpecs.size() : 0, requiredContainer);
        if (prepared == null) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-Cooler] Planned materials do not match recipe {} inputs/container",
                    recipe.getId());
            return false;
        }
        List<ItemStack> inputMaterials = prepared.inputs();
        ItemStack containerMaterial = prepared.container();
        int operations = supportsInputBuffer() ? Math.max(1, plannedOperations) : 1;
        if (inputMaterials.stream().anyMatch(stack -> stack.getCount() != operations)
                || (!requiredContainer.isEmpty()
                && containerMaterial.getCount() != operations)) {
            return false;
        }
        activeOperations = operations;
        long materialCount = inputMaterials.stream().filter(s -> !s.isEmpty()).count();
        if (materialCount > INPUT_SLOTS) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Recipe {} has {} ingredients but only {} input slots",
                    recipe.getId(), materialCount, INPUT_SLOTS);
            return false;
        }

        if (storageEndpoint() == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.network_unavailable"));
            return false;
        }

        int evacuated = evacuateIdleProcessingSlots(itemHandler, getCookingProgress(be),
                this::refundToStorage);
        if (evacuated > 0) be.setChanged();
        if (evacuated < 0
                || !acquireIdleInventory(itemHandler, be)) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-Cooler] Refusing recipe {} because cooler at {} is already busy",
                    recipe.getId(), myPos);
            return false;
        }

        // Validate every input insertion before mutating fuel, containers, or recipe slots.
        int simulatedSlot = 0;
        for (ItemStack mat : inputMaterials) {
            if (mat.isEmpty()) continue;
            ItemStack batch = mat.copy();
            if (!itemHandler.insertItem(simulatedSlot, batch, true).isEmpty()) {
                resetInventoryLease();
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-Cooler] Input slot {} rejected {} during preflight",
                        simulatedSlot, batch);
                return false;
            }
            simulatedSlot++;
        }
        if (!requiredContainer.isEmpty()
                && (!itemHandler.getStackInSlot(CONTAINER_SLOT).isEmpty()
                || !itemHandler.insertItem(CONTAINER_SLOT, containerMaterial, true).isEmpty())) {
            resetInventoryLease();
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-Cooler] Container slot rejected planned {} during preflight",
                    containerMaterial);
            return false;
        }

        forceChunkLoad(true);

        // Phase 1: Insert ingredients into input slots 0..3
        int slot = 0;
        for (ItemStack mat : inputMaterials) {
            if (mat.isEmpty()) continue;
            ItemStack batch = mat.copy();
            ItemStack remainder = itemHandler.insertItem(slot, batch, false);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-Cooler] Failed to insert into slot {}: {}",
                        slot, remainder);
                return rollbackRejectedStart(itemHandler, be);
            }
            recordSlotSupply(slot, batch, batch.getCount());
            slot++;
        }
        be.setChanged();

        // Phase 2: Ensure fuel (lapis lazuli) in slot 6, top up to a full stack
        ItemStack fuelSlot = itemHandler.getStackInSlot(FUEL_SLOT);
        int existingFuel = fuelSlot.is(Items.LAPIS_LAZULI) ? fuelSlot.getCount() : 0;
        int needed = 64 - existingFuel;
        if (needed > 0) {
            int inserted = tryInsertFuelFromStorage(itemHandler, needed);
            if (inserted > 0) {
                recordSlotSupply(FUEL_SLOT, new ItemStack(Items.LAPIS_LAZULI), inserted);
                be.setChanged();
            }
            if (!hasResidualDye(be) && !hasUsableFuel(be, itemHandler.getStackInSlot(FUEL_SLOT))) {
                player.sendSystemMessage(Component.translatable("rsi.cooler.no_fuel"));
                return rollbackRejectedStart(itemHandler, be);
            }
        }

        // Phase 3: Insert container (e.g. bowl/cup) into CONTAINER_SLOT. Like FD's
        // cooking pot, the cooler only moves the finished meal to the output slot
        // when the meal's container is satisfied. Bowl/cup foods (maggot_9 etc.)
        // declare no explicit recipe container, so derive it from the result
        // item's crafting remainder — without it the meal stays stuck internally
        // and is never recovered into RS.
        if (!requiredContainer.isEmpty()) {
            ItemStack existing = itemHandler.getStackInSlot(CONTAINER_SLOT);
            if (!existing.isEmpty()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-Cooler] Recipe {} container slot became occupied by {}",
                        recipe.getId(), existing);
                return rollbackRejectedStart(itemHandler, be);
            }
            ItemStack remainder = itemHandler.insertItem(
                    CONTAINER_SLOT, containerMaterial.copy(), false);
            if (!remainder.isEmpty()) {
                return rollbackRejectedStart(itemHandler, be);
            }
            recordSlotSupply(CONTAINER_SLOT, containerMaterial, containerMaterial.getCount());
            be.setChanged();
        }

        if (!matchesExpectedRecipe(itemHandler)) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-Cooler] Inserted inputs resolved to a different or invalid recipe at {}",
                    myPos);
            return rollbackRejectedStart(itemHandler, be);
        }

        markCraftStarted();
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-Cooler] Materials inserted, cooling should start next tick");
        return true;
    }

    @Override
    @NotNull
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level,
                                                    @NotNull BlockEntity be) {
        if (!ImmersalsDelightReflection.enchantalCoolerBEClass.isInstance(be)) {
            return failObservation("enchantal cooler block entity changed");
        }
        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < 7) {
            return failObservation("enchantal cooler inventory unavailable");
        }

        ItemStack output = handler.getStackInSlot(OUTPUT_SLOT);
        if (!output.isEmpty()) {
            if (!isExpectedOutput(output)) {
                return failObservation("enchantal cooler output slot was occupied by another item");
            }
            ExpectedProduction expected = getExpectedProduction();
            if (expected != null && output.getCount() >= expected.count()) return doneObservation();
        }

        int progress = getCookingProgress(be);
        if (progress > 0) {
            craftObservedWorking = true;
            return workingObservation();
        }
        if (craftObservedWorking && areInputsEmpty(handler)) return doneObservation();
        if (!areOwnedInputsPresent(handler)) {
            return failObservation("enchantal cooler inputs changed before the recipe started");
        }
        return new CraftObservation(phase);
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!ImmersalsDelightReflection.enchantalCoolerBEClass.isInstance(be)) return false;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return false;

        ItemStack output = itemHandler.getStackInSlot(OUTPUT_SLOT);
        ExpectedProduction expected = getExpectedProduction();
        return (expected != null && isExpectedOutput(output)
                && output.getCount() >= expected.count())
                || (craftObservedWorking && areInputsEmpty(itemHandler));
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        ItemStack visible = itemHandler.getStackInSlot(OUTPUT_SLOT);
        if (!isExpectedOutput(visible)) return ItemStack.EMPTY;
        ExpectedProduction expected = getExpectedProduction();
        int amount = expected == null ? visible.getCount()
                : Math.min(visible.getCount(), expected.count());
        ItemStack result = itemHandler.extractItem(OUTPUT_SLOT, amount, false);
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    public List<OutputAccounting.CollectedOutput> collectStructuredResults(ServerPlayer player) {
        ItemStack result = collectResult(player);
        return result.isEmpty() ? List.of() : List.of(new OutputAccounting.CollectedOutput(
                "immortalers_delight:cooler:output", OutputContract.Source.SLOT, result));
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        IItemHandler handler = getInventory(be);
        if (handler != null && handler.getSlots() >= 7) {
            recordFailureRecoveredInputs(cleanupOwnedSlots(handler, !usingSharedLedger));
            be.setChanged();
        } else {
            recordFailureRecoveredInputs(List.of());
            resetInventoryLease();
        }
        craftDone = false;
        craftObservedWorking = false;
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        if (myLevel.hasChunkAt(myPos)) {
            BlockEntity be = myLevel.getBlockEntity(myPos);
            if (be != null && ImmersalsDelightReflection.enchantalCoolerBEClass.isInstance(be)) {
                IItemHandler handler = getInventory(be);
                if (handler != null && handler.getSlots() >= 7) {
                    cleanupOwnedSlots(handler, !usingSharedLedger);
                    be.setChanged();
                }
            }
        }
        resetInventoryLease();
        craftDone = false;
        craftObservedWorking = false;
        resetState();
    }

    private List<ItemStack> cleanupOwnedSlots(IItemHandler handler, boolean refundInputs) {
        List<ItemStack> recoveredInputs = new ArrayList<>();
        if (!inventoryLease) return recoveredInputs;
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack removed = extractOwnedSlotDelta(handler, slot);
            if (!removed.isEmpty()) recoveredInputs.add(removed.copy());
            if (!removed.isEmpty() && refundInputs) refundToStorage(removed);
        }
        // Inputs and container share the same ledger. During shared-graph cleanup,
        // remove physical leftovers and let the ledger perform the only refund.
        ItemStack container = extractOwnedSlotDelta(handler, CONTAINER_SLOT);
        if (!container.isEmpty()) recoveredInputs.add(container.copy());
        if (!container.isEmpty() && refundInputs) refundToStorage(container);
        // Fuel is out-of-band (not in shared ledger) — refund unconditionally
        ItemStack fuel = extractOwnedSlotDelta(handler, FUEL_SLOT);
        if (!fuel.isEmpty()) refundToStorage(fuel);
        resetInventoryLease();
        return recoveredInputs;
    }

    private void resetInventoryLease() {
        inventoryLease = false;
        Arrays.fill(baselineSlots, ItemStack.EMPTY);
        Arrays.fill(suppliedSlotTypes, ItemStack.EMPTY);
        Arrays.fill(suppliedSlotCounts, 0);
    }

    private boolean acquireIdleInventory(IItemHandler handler, BlockEntity be) {
        if (inventoryLease) return false;
        if (!handler.getStackInSlot(CONTAINER_SLOT).isEmpty()) return false;
        List<ItemStack> inputs = new ArrayList<>(INPUT_SLOTS);
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            inputs.add(handler.getStackInSlot(slot).copy());
        }
        if (!EnchantalCoolerInventoryPolicy.isIdle(
                inputs, handler.getStackInSlot(OUTPUT_SLOT), getCookingProgress(be))) {
            return false;
        }

        for (int slot = 0; slot < baselineSlots.length; slot++) {
            baselineSlots[slot] = handler.getStackInSlot(slot).copy();
            suppliedSlotTypes[slot] = ItemStack.EMPTY;
            suppliedSlotCounts[slot] = 0;
        }
        inventoryLease = true;
        return true;
    }

    static int evacuateIdleProcessingSlots(IItemHandler handler, int cookingProgress,
                                           Consumer<ItemStack> returnItem) {
        if (handler == null || handler.getSlots() < 7 || cookingProgress > 0) return -1;
        IdleInventoryEvacuator.Result result = IdleInventoryEvacuator.evacuate(
                handler, true,
                slot -> slot <= OUTPUT_SLOT
                        ? IdleInventoryEvacuator.SlotPolicy.RETURN
                        : IdleInventoryEvacuator.SlotPolicy.PRESERVE,
                returnItem);
        return result.cleared() ? result.returnedCount() : -1;
    }

    private void recordSlotSupply(int slot, ItemStack supplied, int count) {
        if (!inventoryLease || slot < 0 || slot >= suppliedSlotCounts.length
                || supplied.isEmpty() || count <= 0) {
            return;
        }
        ItemStack existingType = suppliedSlotTypes[slot];
        if (!existingType.isEmpty() && !ItemStack.isSameItemSameTags(existingType, supplied)) {
            throw new IllegalStateException("Enchantal Cooler slot ownership type changed");
        }
        suppliedSlotTypes[slot] = supplied.copyWithCount(1);
        suppliedSlotCounts[slot] += count;
    }

    private ItemStack extractOwnedSlotDelta(IItemHandler handler, int slot) {
        if (!inventoryLease || slot < 0 || slot >= suppliedSlotCounts.length) {
            return ItemStack.EMPTY;
        }
        int removable = EnchantalCoolerInventoryPolicy.removableAddedCount(
                baselineSlots[slot], suppliedSlotTypes[slot], suppliedSlotCounts[slot],
                handler.getStackInSlot(slot));
        if (removable <= 0) return ItemStack.EMPTY;

        ItemStack removed = handler.extractItem(slot, removable, false);
        if (!removed.isEmpty()) {
            suppliedSlotCounts[slot] = Math.max(0,
                    suppliedSlotCounts[slot] - removed.getCount());
            if (suppliedSlotCounts[slot] == 0) suppliedSlotTypes[slot] = ItemStack.EMPTY;
        }
        return removed;
    }

    private boolean matchesExpectedRecipe(IItemHandler handler) {
        if (recipe == null || myLevel == null) return false;
        SimpleContainer inputs = createRecipeInput(handler);
        try {
            @SuppressWarnings("rawtypes")
            Recipe rawRecipe = recipe;
            @SuppressWarnings("unchecked")
            boolean matches = rawRecipe.matches(inputs, myLevel);
            return matches;
        } catch (RuntimeException e) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-Cooler] Recipe {} rejected its prepared input inventory",
                    recipe.getId(), e);
            return false;
        }
    }

    static SimpleContainer createRecipeInput(IItemHandler handler) {
        SimpleContainer inputs = new SimpleContainer(CONTAINER_SLOT + 1);
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            inputs.setItem(slot, handler.getStackInSlot(slot).copy());
        }
        inputs.setItem(CONTAINER_SLOT, handler.getStackInSlot(CONTAINER_SLOT).copy());
        return inputs;
    }

    private boolean isExpectedOutput(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ExpectedProduction expected = getExpectedProduction();
        return expected != null && matchesExpectedOutput(stack, expected.item());
    }

    private ItemStack resultItem() {
        return recipe == null || myLevel == null ? ItemStack.EMPTY
                : ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
    }

    private static ItemStack ingredientPrototype(IngredientSpec spec) {
        return Arrays.stream(spec.ingredient().getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    static boolean matchesExpectedOutput(ItemStack actual, ItemStack declared) {
        return IBatchDelegate.matchesProducedItem(actual, declared);
    }

    private static boolean areInputsEmpty(IItemHandler handler) {
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            if (!handler.getStackInSlot(slot).isEmpty()) return false;
        }
        return true;
    }

    private boolean areOwnedInputsPresent(IItemHandler handler) {
        if (!inventoryLease) return false;
        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            int suppliedCount = suppliedSlotCounts[slot];
            if (suppliedCount <= 0) continue;
            ItemStack current = handler.getStackInSlot(slot);
            if (current.isEmpty()
                    || !ItemStack.isSameItemSameTags(suppliedSlotTypes[slot], current)) {
                return false;
            }
        }
        return true;
    }

    private boolean rollbackRejectedStart(IItemHandler handler, BlockEntity be) {
        // The committed local/shared ledger refunds inputs and the container.
        // Fuel remains out-of-band and is returned by ownership-aware cleanup.
        cleanupOwnedSlots(handler, false);
        be.setChanged();
        forceChunkLoad(false);
        return false;
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        resetInventoryLease();
        craftDone = false;
        craftObservedWorking = false;
        resetState();
    }

    private void refundToStorage(ItemStack stack) {
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (!leftover.isEmpty() && player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
        }
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack result = resultItem();
        if (result.isEmpty()) return null;
        long count = (long) result.getCount() * Math.max(1, activeOperations);
        return count > Integer.MAX_VALUE ? null : new ExpectedProduction(result, (int) count);
    }

    @Override
    public OutputContract outputContract() {
        if (!supportsInputBuffer()) return OutputContract.none();
        ItemStack result = resultItem();
        return result.isEmpty() ? OutputContract.none() : new OutputContract(List.of(
                new OutputContract.Port("immortalers_delight:cooler:output", OUTPUT_SLOT,
                        result, result.getCount(), InputBufferPlan.OutputPort.Kind.PRIMARY,
                        OutputContract.Source.SLOT)));
    }

    private static boolean coolerInputBufferEnabled() {
        try {
            return RSIntegrationConfig.ENABLE_ENCHANTAL_COOLER_INPUT_BUFFER.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return true;
        }
    }

    private static int coolerInputBufferLimit() {
        try {
            return RSIntegrationConfig.ENCHANTAL_COOLER_INPUT_BUFFER_LIMIT.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return 64;
        }
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

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.cooler.fuel_warning"));
        return warnings;
    }

    // ── fuel extraction ──

    /**
     * Try to insert up to {@code needed} lapis lazuli into the fuel slot,
     * extracting from the selected storage backend. Tries lapis lazuli first, then lapis blocks
     * (1 block = 9 lapis lazuli).  Returns the amount actually inserted.
     */
    private int tryInsertFuelFromStorage(IItemHandler handler, int needed) {
        int inserted = 0;

        // 1) Try lapis lazuli directly
        ItemStack lapis = extractExactFromStorage(player, new ItemStack(Items.LAPIS_LAZULI), needed, false);
        if (!lapis.isEmpty()) {
            ItemStack remainder = handler.insertItem(FUEL_SLOT, lapis, false);
            if (!remainder.isEmpty()) {
                insertIntoStorage(player, remainder, false);
            }
            inserted = lapis.getCount() - remainder.getCount();
            if (inserted >= needed) return inserted;
        }

        // 2) Not enough — try lapis blocks
        int stillNeeded = needed - inserted;
        int blocksNeeded = (int) Math.ceil(stillNeeded / 9.0);
        ItemStack blocks = extractExactFromStorage(player, new ItemStack(Items.LAPIS_BLOCK), blocksNeeded, false);
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
                    insertIntoStorage(player, remainder, false);
                }
                inserted += toInsert - remainder.getCount();
            }
            // Return excess lapis lazuli (from overshoot on block conversion)
            int excess = totalLapis - toInsert;
            if (excess > 0) {
                insertIntoStorage(player, new ItemStack(Items.LAPIS_LAZULI, excess), false);
            }
        }

        return inserted;
    }

    // ── reflection ──

    private static void probeReflection() {
        if (reflectionProbed) return;
        synchronized (EnchantalCoolerBatchDelegate.class) {
            if (reflectionProbed) return;
            Class<?> coolerClass = ImmersalsDelightReflection.enchantalCoolerBEClass;
            try {
                inventoryField = coolerClass.getDeclaredField("inventory");
                inventoryField.setAccessible(true);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-Cooler] Inventory reflection probe failed", e);
            }
            try {
                residualDyeField = coolerClass.getDeclaredField("residualDye");
                residualDyeField.setAccessible(true);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-Cooler] Residual dye reflection probe failed", e);
            }
            try {
                cookingTotalTimeField = coolerClass.getDeclaredField("cookingTotalTime");
                cookingTotalTimeField.setAccessible(true);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-Cooler] Cooking progress reflection probe failed", e);
            }
            try {
                isFuelMethod = coolerClass.getMethod("isFuel", ItemStack.class);
                isFuelMethod.setAccessible(true);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-Cooler] Fuel validation reflection probe failed", e);
            }
            reflectionProbed = true;
        }
    }

    private static IItemHandler getInventory(BlockEntity be) {
        probeReflection();
        if (inventoryField != null) {
            try {
                return (IItemHandler) inventoryField.get(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-Cooler] field access failed", e); }
        }
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER)
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

    private static int getCookingProgress(BlockEntity be) {
        probeReflection();
        if (cookingTotalTimeField == null) return Integer.MAX_VALUE;
        try {
            return Math.max(0, cookingTotalTimeField.getInt(be));
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Batch-Cooler] Cooking progress access failed", e);
            return Integer.MAX_VALUE;
        }
    }

    private static boolean hasUsableFuel(BlockEntity be, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        probeReflection();
        if (isFuelMethod == null) return false;
        try {
            return Boolean.TRUE.equals(isFuelMethod.invoke(be, stack));
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Batch-Cooler] Fuel validation failed", e);
            return false;
        }
    }

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }

    record PreparedMaterials(List<ItemStack> inputs, ItemStack container) {
        PreparedMaterials {
            inputs = List.copyOf(inputs);
            container = container == null ? ItemStack.EMPTY : container.copy();
        }
    }

    @Nullable
    static PreparedMaterials splitPreparedMaterials(List<ItemStack> materials,
                                                     int inputCount,
                                                     ItemStack requiredContainer) {
        if (materials == null || inputCount < 0) return null;
        boolean hasContainer = requiredContainer != null && !requiredContainer.isEmpty();
        int expectedSize = inputCount + (hasContainer ? 1 : 0);
        if (materials.size() != expectedSize) return null;

        List<ItemStack> inputs = new ArrayList<>(inputCount);
        for (int i = 0; i < inputCount; i++) {
            ItemStack material = materials.get(i);
            if (material == null || material.isEmpty()) return null;
            inputs.add(material);
        }
        if (!hasContainer) return new PreparedMaterials(inputs, ItemStack.EMPTY);

        ItemStack container = materials.get(inputCount);
        if (container == null || container.isEmpty()
                || !ItemStack.isSameItem(container, requiredContainer)) {
            return null;
        }
        return new PreparedMaterials(inputs, container.copy());
    }
}
