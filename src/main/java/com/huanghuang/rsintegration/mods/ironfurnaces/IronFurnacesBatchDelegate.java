package com.huanghuang.rsintegration.mods.ironfurnaces;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.network.RSIntegrationNetwork;
import com.huanghuang.rsintegration.mods.common.IdleInventoryEvacuator;
import com.huanghuang.rsintegration.mods.vanilla.VanillaFurnaceFuelPolicy;
import com.refinedmods.refinedstorage.api.util.Action;
import ironfurnaces.items.augments.ItemAugmentFuel;
import ironfurnaces.items.augments.ItemAugmentSpeed;
import ironfurnaces.tileentity.furnaces.BlockIronFurnaceTileBase;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Batch delegate for Iron Furnaces ordinary furnace mode. */
public final class IronFurnacesBatchDelegate extends AbstractBatchDelegate {

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // Furnace and factory lanes are leased per bound block, so outputs and
        // cleanup remain local to each worker.
        return BatchConcurrencyCapabilities.machineSlot();
    }

    @Override
    public boolean supportsConcurrentNodeExecution() {
        return true;
    }

    private static final int INPUT = 0;
    private static final int FUEL = 1;
    private static final int OUTPUT = 2;
    private static final int[] FACTORY_INPUT = {7, 8, 9, 10, 11, 12};
    private static final Map<String, boolean[]> FACTORY_LEASES = new ConcurrentHashMap<>();

    /** Server-lifecycle safety net after active craft delegates have been aborted. */
    public static void clearFactoryLeases() {
        FACTORY_LEASES.clear();
    }

    private ServerPlayer player;
    private ServerLevel level;
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private AbstractCookingRecipe recipe;
    private BlockIronFurnaceTileBase furnace;
    private boolean observedWorking;
    private int initialInputCount;
    private ItemStack initialFuel = ItemStack.EMPTY;
    private int suppliedFuelCount;
    private boolean inputPlaced;
    private int factorySlot = -1;
    private boolean factoryMode;
    private boolean rainbowMode;
    private int plannedOperations = 1;
    private int plannedFactoryLanes = 1;
    private ItemStack queuedMaterial = ItemStack.EMPTY;
    private int queuedOperations;
    private int activePhysicalOperations;
    private final List<ItemStack> completedBatchResults = new ArrayList<>();
    private String factoryLeaseKey;
    private final boolean[] ownedFactoryLanes = new boolean[FACTORY_INPUT.length];
    private final int[] initialFactoryInputCounts = new int[FACTORY_INPUT.length];
    private final int[] expectedFactoryOutputCounts = new int[FACTORY_INPUT.length];
    private final int[] capturedFactoryOutputCounts = new int[FACTORY_INPUT.length];

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        int available = Math.max(0, remainingOperations);
        int laneCapacity = rainbowMode ? rainbowLaneCapacity() : 1;
        int physicalBatch = plannedBatchSize(
                factoryMode, rainbowMode, available, laneCapacity);
        plannedOperations = available;
        plannedFactoryLanes = factoryMode
                ? requiredFactoryLanes(physicalBatch, laneCapacity) : 1;
        RSIntegrationMod.debug(
                "[RSI-IronFurnaces] batch plan factory={} rainbow={} requested={} physicalBatch={} lanes={} laneCapacity={}",
                factoryMode, rainbowMode, remainingOperations, physicalBatch,
                plannedFactoryLanes, laneCapacity);
        return plannedOperations;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        plannedOperations = Math.max(1, executions);
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return parallelWorkerBatchSize(totalOperations, workerCount, physicalBatchCapacity());
    }

    static int parallelWorkerBatchSize(int totalOperations, int workerCount, int capacity) {
        return ParallelBatchSizing.boundedEvenShare(totalOperations, workerCount, capacity);
    }

    static int plannedBatchSize(boolean factory, boolean rainbow,
                                int remainingOperations, int rainbowLaneCapacity) {
        int available = Math.max(0, remainingOperations);
        if (factory) {
            int perLane = rainbow ? Math.max(1, rainbowLaneCapacity) : 1;
            return Math.min(FACTORY_INPUT.length * perLane, available);
        }
        return rainbow ? Math.min(Math.max(1, rainbowLaneCapacity), available)
                : Math.min(1, available);
    }

    static List<Integer> physicalBatchSizes(int operations, int capacity) {
        if (operations <= 0 || capacity <= 0) return List.of();
        List<Integer> batches = new ArrayList<>((operations + capacity - 1) / capacity);
        int remaining = operations;
        while (remaining > 0) {
            int batch = Math.min(capacity, remaining);
            batches.add(batch);
            remaining -= batch;
        }
        return List.copyOf(batches);
    }

    @Override
    public PreparationResult prepare(@NotNull ServerPlayer player, @NotNull ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, @NotNull BlockPos pos) {
        ServerLevel target = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (target == null) return PreparationResult.fatal("machine dimension unavailable");
        if (!target.hasChunkAt(pos)) return PreparationResult.retry("machine chunk unloaded");

        Recipe<?> found = target.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof AbstractCookingRecipe cooking)) {
            return PreparationResult.fatal("recipe is not a cooking recipe");
        }
        BlockEntity be = target.getBlockEntity(pos);
        if (!(be instanceof BlockIronFurnaceTileBase ironFurnace)) {
            return PreparationResult.retry("Iron Furnaces block entity unavailable");
        }
        if (ironFurnace.isGenerator()
                || (!ironFurnace.isFactory() && !ironFurnace.isFurnace())) {
            return PreparationResult.fatal("Iron Furnace generator mode is not supported",
                    Component.translatable("rsi.ironfurnaces.error.machine_mode_unsupported"));
        }
        PreparationResult state = validateMachine(ironFurnace, cooking);
        if (state.state() != PreparationState.READY) return state;
        if (ironFurnace.isFactory() && ironFurnace.getEnergy() <= 0) {
            return PreparationResult.fatal("Iron Furnace factory has no energy",
                    Component.translatable("rsi.ironfurnaces.error.factory_no_energy"));
        }
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, target.dimension(), pos);
        } else {
            this.network = null;
        }
        this.factoryMode = ironFurnace.isFactory();
        this.rainbowMode = ironFurnace.isRainbowFurnace();
        this.plannedOperations = 1;
        this.plannedFactoryLanes = 1;
        clearInternalBatchState();
        if (factoryMode) {
            factoryLeaseKey = target.dimension().location() + ":" + pos.asLong();
            factorySlot = reserveFactorySlot(factoryLeaseKey, ironFurnace);
            if (factorySlot < 0) return PreparationResult.retry("Iron Furnace factory has no free slot");
        } else if (ironFurnace.cookTime > 0) {
            return PreparationResult.retry("Iron Furnace is actively cooking");
        }

        this.player = player;
        this.level = target;
        this.dimension = target.dimension();
        this.pos = pos;
        this.recipe = cooking;
        this.furnace = ironFurnace;
        this.machineDim = target.dimension().location();
        this.machineServer = player.server;
        this.observedWorking = false;
        this.initialInputCount = 0;
        this.initialFuel = ironFurnace.getItem(FUEL).copy();
        this.suppliedFuelCount = 0;
        this.inputPlaced = false;
        markCraftStarted();
        return PreparationResult.ready();
    }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        if (!refreshMachine() || recipe == null) return false;
        if (validateMachine(furnace, recipe).state() != PreparationState.READY) return false;
        if (phase == CraftPhase.DONE) return true;
        if (factoryMode) {
            return furnace.getEnergy() > 0 && ensureFactoryLeases(plannedFactoryLanes);
        }
        return furnace.cookTime <= 0;
    }

    @Override
    public boolean validateAndInit(@NotNull ServerPlayer player, @NotNull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @NotNull BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    private static PreparationResult validateMachine(BlockIronFurnaceTileBase furnace,
                                                       AbstractCookingRecipe recipe) {
        if (furnace.isGenerator() || (!furnace.isFactory() && !furnace.isFurnace())) {
            return PreparationResult.fatal("Iron Furnace mode is not supported");
        }
        RecipeType<?> expected = recipeType(recipe);
        if (expected == null || furnace.recipeType != expected) {
            return PreparationResult.fatal("Iron Furnace recipe mode does not match its augment");
        }
        return PreparationResult.ready();
    }

    @Nullable
    public static RecipeType<?> recipeType(Recipe<?> recipe) {
        if (recipe instanceof BlastingRecipe) return RecipeType.BLASTING;
        if (recipe instanceof SmokingRecipe) return RecipeType.SMOKING;
        if (recipe instanceof SmeltingRecipe) return RecipeType.SMELTING;
        return null;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        return recipe == null ? null : CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public boolean tryStartSingleCraft(@NotNull ServerPlayer player) {
        this.ledger = new ExtractionLedger();
        this.usingSharedLedger = false;
        return reserveAndStart(player, ledger, true);
    }

    @Override
    public boolean tryStartSingleCraft(@NotNull ServerPlayer player,
                                       @NotNull ExtractionLedger sharedLedger) {
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        return reserveAndStart(player, sharedLedger, false);
    }

    private boolean reserveAndStart(ServerPlayer player, ExtractionLedger activeLedger,
                                    boolean commit) {
        if (recipe == null || recipe.getIngredients().isEmpty()) return false;
        activeLedger.setStorageEndpoint(storageEndpoint());
        resolveNetwork(player);
        Ingredient input = recipe.getIngredients().get(0);
        ItemStack material = CraftPacketUtils.ensureMaterialAvailable(
                player, dimension, pos, input, 1, activeLedger);
        if (material.isEmpty()) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.missing_materials",
                    CraftPacketUtils.describeIngredient(input)));
            return false;
        }
        if (commit && !activeLedger.commit(network, player)) return false;
        return startQueuedMaterials(List.of(material));
    }

    @Override
    public boolean tryStartWithMaterials(@NotNull ServerPlayer player,
                                         @NotNull List<ItemStack> materials,
                                         @NotNull ExtractionLedger sharedLedger) {
        this.player = player;
        this.sharedLedger = sharedLedger;
        this.usingSharedLedger = true;
        resolveNetwork(player);
        return startQueuedMaterials(materials);
    }

    static List<ItemStack> splitFactoryMaterials(List<ItemStack> materials) {
        return splitFactoryMaterials(materials, 1);
    }

    static List<ItemStack> splitFactoryMaterials(List<ItemStack> materials, int perLaneLimit) {
        if (materials == null || materials.isEmpty() || perLaneLimit <= 0) return List.of();
        ItemStack template = ItemStack.EMPTY;
        int total = 0;
        for (ItemStack material : materials) {
            if (material == null || material.isEmpty()) return List.of();
            if (template.isEmpty()) template = material.copyWithCount(1);
            else if (!ItemStack.isSameItemSameTags(template, material)) return List.of();
            total += material.getCount();
        }
        int laneLimit = Math.min(perLaneLimit, template.getMaxStackSize());
        if (laneLimit <= 0 || total <= 0 || total > FACTORY_INPUT.length * laneLimit) {
            return List.of();
        }
        List<ItemStack> lanes = new ArrayList<>(FACTORY_INPUT.length);
        int remaining = total;
        while (remaining > 0) {
            int count = Math.min(laneLimit, remaining);
            lanes.add(template.copyWithCount(count));
            remaining -= count;
        }
        return lanes;
    }

    static int requiredFactoryLanes(int operations, int perLaneLimit) {
        if (operations <= 0 || perLaneLimit <= 0) return 0;
        return Math.min(FACTORY_INPUT.length,
                (operations + perLaneLimit - 1) / perLaneLimit);
    }

    static ItemStack mergeBatchMaterials(List<ItemStack> materials, int limit) {
        if (materials == null || materials.isEmpty() || limit <= 0) return ItemStack.EMPTY;
        ItemStack merged = ItemStack.EMPTY;
        int count = 0;
        for (ItemStack material : materials) {
            if (material == null || material.isEmpty()) return ItemStack.EMPTY;
            if (merged.isEmpty()) merged = material.copyWithCount(1);
            else if (!ItemStack.isSameItemSameTags(merged, material)) return ItemStack.EMPTY;
            count += material.getCount();
            if (count > limit || count > merged.getMaxStackSize()) return ItemStack.EMPTY;
        }
        return merged.copyWithCount(count);
    }

    private boolean startQueuedMaterials(List<ItemStack> materials) {
        if (materials == null || materials.isEmpty() || !refreshMachine()) return false;
        if (validateMachine(furnace, recipe).state() != PreparationState.READY) return false;
        if (factoryMode && furnace.getEnergy() <= 0) return false;

        ItemStack template = ItemStack.EMPTY;
        long total = 0;
        for (ItemStack material : materials) {
            if (material == null || material.isEmpty()) return false;
            if (template.isEmpty()) template = material.copyWithCount(1);
            else if (!ItemStack.isSameItemSameTags(template, material)) return false;
            total += material.getCount();
            if (total > Integer.MAX_VALUE) return false;
        }
        if (template.isEmpty() || total <= 0) return false;

        int operations = (int) total;
        int capacity = physicalBatchCapacity();
        int cycles = physicalCycleCount(operations, capacity);
        if (!factoryMode && !ensureFuel(cycles)) {
            if (player != null) {
                player.sendSystemMessage(Component.translatable("rsi.ironfurnaces.error.no_fuel"));
            }
            return false;
        }

        clearInternalBatchState();
        queuedMaterial = template;
        queuedOperations = operations;
        plannedOperations = operations;
        plannedFactoryLanes = factoryMode
                ? requiredFactoryLanes(Math.min(operations, capacity), rainbowMode ? rainbowLaneCapacity() : 1)
                : 1;
        if (startNextPhysicalBatch()) return true;

        if (!factoryMode) refundFuel(furnace);
        clearInternalBatchState();
        return false;
    }

    private boolean startNextPhysicalBatch() {
        if (queuedMaterial.isEmpty() || queuedOperations <= 0 || !refreshMachine()) return false;
        if (validateMachine(furnace, recipe).state() != PreparationState.READY) return false;
        if (factoryMode && furnace.getEnergy() <= 0) return false;

        int laneCapacity = rainbowMode ? rainbowLaneCapacity() : 1;
        int operations = Math.min(queuedOperations, physicalBatchCapacity());
        if (factoryMode) {
            List<ItemStack> laneInputs = splitFactoryMaterials(
                    List.of(queuedMaterial.copyWithCount(operations)), laneCapacity);
            if (laneInputs.isEmpty() || !ensureFactoryLeases(laneInputs.size())) return false;
            for (ItemStack laneInput : laneInputs) {
                int lane = findOwnedEmptyFactoryLane();
                if (lane < 0) {
                    rollbackActiveFactoryPlacement(furnace);
                    return false;
                }
                furnace.setItem(FACTORY_INPUT[lane], laneInput);
                initialFactoryInputCounts[lane] = laneInput.getCount();
                expectedFactoryOutputCounts[lane] = expectedOutputCount(laneInput.getCount());
                if (!inputPlaced) {
                    factorySlot = lane;
                    inputPlaced = true;
                    initialInputCount = laneInput.getCount();
                }
            }
            plannedFactoryLanes = laneInputs.size();
            RSIntegrationMod.debug(
                    "[RSI-IronFurnaces] physical factory batch pos={} rainbow={} operations={} remaining={} laneCounts={}",
                    pos, rainbowMode, operations, queuedOperations - operations,
                    laneInputs.stream().map(ItemStack::getCount).toList());
        } else {
            if (!evacuateIdleFurnaceSlots()) return false;
            ItemStack placed = queuedMaterial.copyWithCount(operations);
            furnace.setItem(INPUT, placed);
            inputPlaced = true;
            initialInputCount = operations;
        }

        queuedOperations -= operations;
        activePhysicalOperations = operations;
        furnace.setChanged();
        level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
        observedWorking = false;
        markCraftStarted();
        return true;
    }

    private boolean evacuateIdleFurnaceSlots() {
        IdleInventoryEvacuator.Result result = IdleInventoryEvacuator.evacuate(
                furnace, furnace != null && furnace.cookTime <= 0,
                slot -> slot == INPUT || slot == OUTPUT
                        ? IdleInventoryEvacuator.SlotPolicy.RETURN
                        : IdleInventoryEvacuator.SlotPolicy.PRESERVE,
                this::refund);
        if (result.cleared()) furnace.setChanged();
        return result.cleared();
    }

    private boolean refreshMachine() {
        if (level == null || pos == null) return false;
        if (!level.hasChunkAt(pos)) return false;
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof BlockIronFurnaceTileBase current)) return false;
        furnace = current;
        return true;
    }

    private void resolveNetwork(ServerPlayer player) {
        this.player = player;
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, dimension, pos);
            if (network == null) network = RSIntegrationNetwork.resolveNetworkFromPlayer(player);
        } else {
            this.network = null;
        }
    }

    private int physicalBatchCapacity() {
        int laneCapacity = rainbowMode ? rainbowLaneCapacity() : 1;
        return factoryMode ? FACTORY_INPUT.length * laneCapacity : laneCapacity;
    }

    static int physicalCycleCount(int operations, int capacity) {
        if (operations <= 0 || capacity <= 0) return 0;
        return (operations + capacity - 1) / capacity;
    }

    static int requiredFuelTicks(int cookTicks, int cycles, int currentBurnTime) {
        if (cookTicks <= 0 || cycles <= 0) return 0;
        long required = (long) cookTicks * cycles - Math.max(0, currentBurnTime);
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, required));
    }

    private boolean ensureFuel(int cycles) {
        int cookTicks = Math.max(1, furnace.getCookTime());
        int remaining = requiredFuelTicks(cookTicks, cycles, furnace.furnaceBurnTime);
        if (remaining == 0) return true;

        ItemStack existing = furnace.getItem(FUEL);
        if (!existing.isEmpty()) {
            int burn = effectiveFuelTicks(existing, cookTicks);
            if (burn <= 0) return false;
            int needed = VanillaFurnaceFuelPolicy.requiredAmount(remaining, burn);
            int limit = Math.min(existing.getMaxStackSize(), furnace.getMaxStackSize());
            if (needed > limit) return false;
            if (existing.getCount() >= needed) return true;
            ItemStack extra = extractExact(existing, needed - existing.getCount());
            if (extra.isEmpty()) return false;
            ItemStack merged = existing.copy();
            merged.grow(extra.getCount());
            furnace.setItem(FUEL, merged);
            suppliedFuelCount += extra.getCount();
            return true;
        }

        List<ItemStack> candidates = new ArrayList<>();
        if (storageEndpoint() != null) {
            var snapshot = storageEndpoint().snapshot(player).snapshot().orElse(null);
            if (snapshot == null) return false;
            for (var entry : snapshot.items()) candidates.add(entry.stack());
        } else {
            if (network == null) return false;
            for (var entry : network.getItemStorageCache().getList().getStacks()) candidates.add(entry.getStack());
        }
        VanillaFurnaceFuelPolicy.Selection selection = VanillaFurnaceFuelPolicy.select(
                candidates, RSIntegrationConfig.VANILLA_FURNACE_FUEL_PRIORITY.get(),
                remaining, stack -> effectiveFuelTicks(stack, cookTicks));
        if (selection == null || selection.partial()) return false;
        ItemStack extracted = extractExact(selection.fuel(), selection.amount());
        if (extracted.isEmpty()) return false;
        furnace.setItem(FUEL, extracted);
        suppliedFuelCount += extracted.getCount();
        player.displayClientMessage(Component.translatable(
                "rsi.ironfurnaces.info.fuel_supplied", extracted.getCount()), true);
        return true;
    }

    private ItemStack extractExact(ItemStack template, int amount) {
        ItemStack extracted = extractExactFromStorage(player, template.copyWithCount(1), amount, false);
        if (extracted.getCount() == amount) return extracted;
        if (!extracted.isEmpty()) insertIntoStorage(player, extracted, false);
        return ItemStack.EMPTY;
    }

    @NotNull
    @Override
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level,
                                                    @NotNull BlockEntity be) {
        if (!(be instanceof BlockIronFurnaceTileBase current)) {
            return failObservation("Iron Furnace block entity replaced");
        }
        if (validateMachine(current, recipe).state() != PreparationState.READY) {
            return failObservation("Iron Furnace mode changed during crafting");
        }
        furnace = current;
        if (factoryMode) {
            captureFactoryOutputs(current, completedBatchResults);
            boolean anyWorking = false;
            boolean allDone = true;
            int expectedTotal = expectedOutputCount(activePhysicalOperations);
            int capturedTotal = 0;
            for (int lane = 0; lane < FACTORY_INPUT.length; lane++) {
                if (!ownedFactoryLanes[lane] || expectedFactoryOutputCounts[lane] <= 0) continue;
                int inputSlot = FACTORY_INPUT[lane];
                ItemStack input = current.getItem(inputSlot);
                ItemStack output = current.getItem(inputSlot + 6);
                boolean consumed = input.getCount() < initialFactoryInputCounts[lane];
                anyWorking |= current.factoryCookTime[lane] > 0 || consumed || !output.isEmpty();
                // A consumed input with an empty output slot is not a
                // successful lane completion.  In factory/rainbow mode the
                // output may be produced on a later tick; treating this state
                // as done allowed the first completed lanes to finish the
                // worker while the remaining lanes were still cooking.
                allDone &= capturedFactoryOutputCounts[lane]
                        >= expectedFactoryOutputCounts[lane];
                capturedTotal += capturedFactoryOutputCounts[lane];
            }
            observedWorking |= anyWorking;
            // Iron Furnaces can move a finished lane's output on a later tick,
            // and factory/rainbow implementations are not consistent about
            // which lane is flushed first. The aggregate count is the actual
            // completion contract for this physical batch; the per-lane check
            // remains useful for diagnostics but must not strand a completed
            // batch when slot timing differs.
            boolean aggregateDone = expectedTotal > 0 && capturedTotal >= expectedTotal;
            RSIntegrationMod.debug(
                    "[RSI-IronFurnaces] factory observation pos={} expectedTotal={} capturedTotal={} allLanesDone={} working={} queued={}",
                    pos, expectedTotal, capturedTotal, allDone, observedWorking, queuedOperations);
            if (!observedWorking || (!allDone && !aggregateDone)) return workingObservation();
            if (queuedOperations <= 0) return doneObservation();
            drainActivePhysicalResults(current, completedBatchResults);
            clearActivePhysicalState();
            return startNextPhysicalBatch()
                    ? workingObservation()
                    : failObservation("Iron Furnace could not start the next physical batch");
        }
        int inSlot = INPUT;
        int outSlot = OUTPUT;
        ItemStack output = current.getItem(outSlot);
        ItemStack input = current.getItem(inSlot);
        boolean inputConsumed = inputPlaced && initialInputCount > 0 && input.getCount() < initialInputCount;
        if (inputConsumed) inputPlaced = false;
        if (current.isBurning() || inputConsumed) observedWorking = true;
        if (!observedWorking) return workingObservation();
        // Input consumption is not a completion signal for high-speed Iron
        // Furnaces: the machine can clear the input and publish the result on
        // the following tick. Require the actual output stack so the progress
        // panel cannot finish and collect before the product exists.
        boolean physicalDone = output.getCount() >= expectedOutputCount(activePhysicalOperations);
        if (!physicalDone) return workingObservation();
        if (queuedOperations <= 0) return doneObservation();
        drainActivePhysicalResults(current, completedBatchResults);
        clearActivePhysicalState();
        return startNextPhysicalBatch()
                ? workingObservation()
                : failObservation("Iron Furnace could not start the next physical batch");
    }

    @Override
    protected boolean isMachineCraftFinished(@NotNull ServerLevel level, @NotNull BlockEntity be) {
        return observeMachineCraft(level, be).phase() == CraftPhase.DONE;
    }

    @NotNull
    @Override
    public ItemStack collectResult(@NotNull ServerPlayer player) {
        List<ItemStack> results = collectAllResults(player);
        if (results.isEmpty()) return ItemStack.EMPTY;
        ItemStack combined = results.get(0).copy();
        for (int i = 1; i < results.size(); i++) {
            ItemStack next = results.get(i);
            if (ItemStack.isSameItemSameTags(combined, next)) {
                combined.grow(next.getCount());
            }
        }
        return combined;
    }

    @NotNull
    @Override
    public List<ItemStack> collectAllResults(@NotNull ServerPlayer player) {
        if (!refreshMachine()) return List.of();
        List<ItemStack> results = new ArrayList<>(completedBatchResults.size() + FACTORY_INPUT.length);
        for (ItemStack result : completedBatchResults) results.add(result.copy());
        completedBatchResults.clear();
        drainActivePhysicalResults(furnace, results);
        return results;
    }

    private void drainActivePhysicalResults(BlockIronFurnaceTileBase current,
                                            List<ItemStack> destination) {
        boolean changed = false;
        if (factoryMode) {
            captureFactoryOutputs(current, destination);
            for (int lane = 0; lane < FACTORY_INPUT.length; lane++) {
                // Lane ownership is the authoritative boundary. Do not gate
                // collection on expectedFactoryOutputCounts: that counter is
                // preparation state and may already be cleared after a lane
                // completed, while Iron Furnaces still has its real output in
                // the corresponding slot.
                int inputSlot = FACTORY_INPUT[lane];
                ItemStack result = current.getItem(inputSlot + 6).copy();
                if (!result.isEmpty()) {
                    destination.add(result);
                    RSIntegrationMod.debug("[RSI-IronFurnaces] collected factory output lane={} slot={} count={}",
                            lane, inputSlot + 6, result.getCount());
                }
                if (!current.getItem(inputSlot).isEmpty()) {
                    current.setItem(inputSlot, ItemStack.EMPTY);
                    changed = true;
                }
                if (!current.getItem(inputSlot + 6).isEmpty()) {
                    current.setItem(inputSlot + 6, ItemStack.EMPTY);
                    changed = true;
                }
            }
        } else {
            ItemStack result = current.getItem(OUTPUT).copy();
            if (!result.isEmpty()) destination.add(result);
            if (!current.getItem(INPUT).isEmpty()) current.setItem(INPUT, ItemStack.EMPTY);
            if (!current.getItem(OUTPUT).isEmpty()) current.setItem(OUTPUT, ItemStack.EMPTY);
            changed = true;
        }
        if (changed) {
            current.setChanged();
            if (level != null && pos != null) {
                level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            }
        }
    }

    private void captureFactoryOutputs(BlockIronFurnaceTileBase current,
                                       List<ItemStack> destination) {
        boolean changed = false;
        for (int lane = 0; lane < FACTORY_INPUT.length; lane++) {
            int outputSlot = FACTORY_INPUT[lane] + 6;
            ItemStack output = current.getItem(outputSlot);
            if (output.isEmpty()) continue;
            ItemStack captured = output.copy();
            destination.add(captured);
            if (ownedFactoryLanes[lane]) capturedFactoryOutputCounts[lane] += captured.getCount();
            RSIntegrationMod.debug("[RSI-IronFurnaces] captured factory output lane={} slot={} count={}",
                    lane, outputSlot, captured.getCount());
            current.setItem(outputSlot, ItemStack.EMPTY);
            changed = true;
        }
        if (changed) {
            current.setChanged();
            if (level != null && pos != null) {
                level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 3);
            }
        }
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        if (recipe == null || level == null) return null;
        ItemStack result = recipe.getResultItem(level.registryAccess()).copy();
        return result.isEmpty() ? null
                : new ExpectedProduction(result, expectedOutputCount(plannedOperations));
    }

    @Override
    public void releasePreparationResources() {
        // AbstractBatchDelegate invokes this before physical failure cleanup.
        // Keep active lane ownership until clearMachineState can remove the
        // inputs; discarded preparation probes have not placed anything yet.
        if (!inputPlaced) releaseFactoryLease();
    }

    @Override
    protected void clearMachineState(BlockEntity be, @Nullable ServerPlayer player) {
        if (!(be instanceof BlockIronFurnaceTileBase current)) {
            releaseFactoryLease();
            clearInternalBatchState();
            resetState();
            return;
        }
        // Refund physical slots only when no ledger will cover them. `player == null`
        // is not sufficient: terminate() nulls the player under SILENT_REFUND, and
        // that policy still refunds the ledger — doing both duplicates the material.
        boolean refundPhysical = player == null && !usingSharedLedger;
        List<ItemStack> recoveredInputs = new ArrayList<>();
        if (!queuedMaterial.isEmpty() && queuedOperations > 0) {
            recoveredInputs.add(queuedMaterial.copyWithCount(queuedOperations));
        }
        if (factoryMode) {
            for (int lane = 0; lane < FACTORY_INPUT.length; lane++) {
                if (!ownedFactoryLanes[lane]) continue;
                int inSlot = FACTORY_INPUT[lane];
                ItemStack input = current.getItem(inSlot);
                if (!input.isEmpty() && ItemStack.isSameItemSameTags(input, queuedMaterial)) {
                    int owned = Math.min(input.getCount(), initialFactoryInputCounts[lane]);
                    ItemStack recovered = input.copyWithCount(owned);
                    ItemStack retained = input.copy();
                    retained.shrink(owned);
                    current.setItem(inSlot, retained.isEmpty() ? ItemStack.EMPTY : retained);
                    recoveredInputs.add(recovered);
                    if (refundPhysical) refund(recovered);
                }
            }
            inputPlaced = false;
        } else if (inputPlaced) {
            int inSlot = INPUT;
            ItemStack input = current.getItem(inSlot);
            if (!input.isEmpty() && ItemStack.isSameItemSameTags(input, queuedMaterial)) {
                int owned = Math.min(input.getCount(), initialInputCount);
                ItemStack recovered = input.copyWithCount(owned);
                ItemStack retained = input.copy();
                retained.shrink(owned);
                current.setItem(inSlot, retained.isEmpty() ? ItemStack.EMPTY : retained);
                recoveredInputs.add(recovered);
                if (refundPhysical) refund(recovered);
            }
            inputPlaced = false;
        }
        recordFailureRecoveredInputs(recoveredInputs);
        refundFuel(current);
        current.setChanged();
        releaseFactoryLease();
        clearInternalBatchState();
        resetState();
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        releaseFactoryLease();
        clearInternalBatchState();
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (refreshMachine()) refundFuel(furnace);
        releaseFactoryLease();
        clearInternalBatchState();
        resetState();
    }

    private void refundFuel(BlockIronFurnaceTileBase current) {
        ItemStack fuel = current.getItem(FUEL);
        if (fuel.isEmpty() || BlockIronFurnaceTileBase.getBurnTime(fuel, current.recipeType) <= 0) return;
        if (!initialFuel.isEmpty() && !ItemStack.isSameItemSameTags(initialFuel, fuel)) return;
        int owned = Math.min(fuel.getCount(), suppliedFuelCount);
        if (owned <= 0) return;
        ItemStack refund = fuel.copyWithCount(owned);
        ItemStack retained = fuel.copy();
        retained.shrink(owned);
        current.setItem(FUEL, retained.isEmpty() ? ItemStack.EMPTY : retained);
        refund(refund);
    }

    private void refund(ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (player != null) {
            if (!leftover.isEmpty()) ItemHandlerHelper.giveItemToPlayer(player, leftover);
        } else if (!leftover.isEmpty()) {
            if (level != null && pos != null) Block.popResource(level, pos, leftover.copy());
        }
    }

    @NotNull
    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    private int findFactorySlot(BlockIronFurnaceTileBase f) {
        for (int i = 0; i < FACTORY_INPUT.length; i++)
            if (f.getItem(FACTORY_INPUT[i]).isEmpty() && f.getItem(FACTORY_INPUT[i] + 6).isEmpty()) return i;
        return -1;
    }

    private int reserveFactorySlot(String key, BlockIronFurnaceTileBase f) {
        boolean[] leases = FACTORY_LEASES.computeIfAbsent(key, ignored -> new boolean[FACTORY_INPUT.length]);
        synchronized (leases) {
            for (int i = 0; i < FACTORY_INPUT.length; i++) {
                if (!leases[i] && f.getItem(FACTORY_INPUT[i]).isEmpty()
                        && f.getItem(FACTORY_INPUT[i] + 6).isEmpty()) {
                    leases[i] = true;
                    ownedFactoryLanes[i] = true;
                    return i;
                }
            }
        }
        return -1;
    }

    private boolean ensureFactoryLeases(int required) {
        if (!factoryMode || furnace == null || factoryLeaseKey == null
                || required <= 0 || required > FACTORY_INPUT.length) return false;
        while (ownedFactoryLaneCount() < required) {
            if (reserveFactorySlot(factoryLeaseKey, furnace) < 0) return false;
        }
        return true;
    }

    private int effectiveFuelTicks(ItemStack fuel, int cookTicks) {
        int rawBurn = BlockIronFurnaceTileBase.getBurnTime(fuel, furnace.recipeType);
        // getCookTime() already includes the machine's speed/fuel augment
        // modifiers. Applying another speed divisor here makes every fuel
        // stack appear to cover fewer furnace ticks than it really does,
        // causing multi-item requests to over-consume fuel and stall.
        return effectiveFuelTicks(rawBurn, cookTicks, 1, 1);
    }

    static int effectiveFuelTicks(int rawBurn, int cookTicks, int multiplier, int divisor) {
        if (rawBurn <= 0 || cookTicks <= 0 || multiplier <= 0 || divisor <= 0) return 0;
        long scaled = (long) rawBurn * cookTicks / 200L;
        scaled = scaled * multiplier / divisor;
        return (int) Math.min(Integer.MAX_VALUE, scaled);
    }

    private int rainbowLaneCapacity() {
        if (!rainbowMode || recipe == null || level == null) return 1;
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty() || result.getCount() <= 0) return 1;
        int outputCapacity = result.getMaxStackSize() / result.getCount();
        int inputCapacity = recipe.getIngredients().isEmpty() ? 64
                : java.util.Arrays.stream(recipe.getIngredients().get(0).getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .mapToInt(ItemStack::getMaxStackSize)
                .min().orElse(64);
        return Math.max(1, Math.min(64, Math.min(outputCapacity, inputCapacity)));
    }

    private int ownedFactoryLaneCount() {
        int count = 0;
        for (boolean owned : ownedFactoryLanes) if (owned) count++;
        return count;
    }

    private int findOwnedEmptyFactoryLane() {
        for (int lane = 0; lane < FACTORY_INPUT.length; lane++) {
            if (ownedFactoryLanes[lane]
                    && initialFactoryInputCounts[lane] == 0
                    && furnace.getItem(FACTORY_INPUT[lane]).isEmpty()
                    && furnace.getItem(FACTORY_INPUT[lane] + 6).isEmpty()) return lane;
        }
        return -1;
    }

    private int expectedOutputCount(int operations) {
        if (recipe == null || level == null || operations <= 0) return 0;
        ItemStack result = recipe.getResultItem(level.registryAccess());
        return result.isEmpty() ? 0 : result.getCount() * operations;
    }

    private void releaseFactoryLease() {
        if (factoryLeaseKey == null) return;
        boolean[] leases = FACTORY_LEASES.get(factoryLeaseKey);
        if (leases != null) synchronized (leases) {
            for (int i = 0; i < ownedFactoryLanes.length; i++) {
                if (ownedFactoryLanes[i]) leases[i] = false;
                ownedFactoryLanes[i] = false;
            }
            boolean empty = true;
            for (boolean lease : leases) empty &= !lease;
            if (empty) FACTORY_LEASES.remove(factoryLeaseKey, leases);
        }
        factoryLeaseKey = null;
        factorySlot = -1;
        java.util.Arrays.fill(initialFactoryInputCounts, 0);
        java.util.Arrays.fill(expectedFactoryOutputCounts, 0);
        java.util.Arrays.fill(capturedFactoryOutputCounts, 0);
    }

    private void rollbackActiveFactoryPlacement(BlockIronFurnaceTileBase f) {
        for (int i = 0; i < ownedFactoryLanes.length; i++) {
            if (!ownedFactoryLanes[i] || initialFactoryInputCounts[i] <= 0) continue;
            f.setItem(FACTORY_INPUT[i], ItemStack.EMPTY);
        }
        clearActivePhysicalState();
        f.setChanged();
    }

    private void clearActivePhysicalState() {
        inputPlaced = false;
        initialInputCount = 0;
        activePhysicalOperations = 0;
        observedWorking = false;
        factorySlot = -1;
        java.util.Arrays.fill(initialFactoryInputCounts, 0);
        java.util.Arrays.fill(expectedFactoryOutputCounts, 0);
        java.util.Arrays.fill(capturedFactoryOutputCounts, 0);
    }

    private void clearInternalBatchState() {
        clearActivePhysicalState();
        queuedMaterial = ItemStack.EMPTY;
        queuedOperations = 0;
        plannedOperations = 1;
        plannedFactoryLanes = 1;
        completedBatchResults.clear();
    }

}
