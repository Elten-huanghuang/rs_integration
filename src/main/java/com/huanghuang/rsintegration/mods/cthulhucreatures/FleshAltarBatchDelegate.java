package com.huanghuang.rsintegration.mods.cthulhucreatures;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 通过原祭坛的自动加工逻辑执行，材料和产物均按实际槽位结算。 */
public final class FleshAltarBatchDelegate extends AbstractBatchDelegate {
    private static final FleshAltarRecipeHandler HANDLER = new FleshAltarRecipeHandler();
    private static final String PRIMARY_PORT = "flesh_altar:primary";

    private ServerLevel level;
    private BlockPos pos;
    private BlockEntity machine;
    private Container inventory;
    private Recipe<?> recipe;
    private List<IngredientSpec> specs = List.of();
    private ItemStack result = ItemStack.EMPTY;
    private List<ItemStack> placed = List.of();
    private boolean started;
    private int activeOperations = 1;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel resolved = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (resolved == null || !resolved.hasChunkAt(pos)) return false;
        BlockEntity be = resolved.getBlockEntity(pos);
        Recipe<?> found = resolved.getRecipeManager().byKey(recipeId).orElse(null);
        if (be == null || !be.getClass().getName().equals(CthulhuCreaturesRSModule.PACKAGE
                + "FleshAltarBlockEntity") || !(be instanceof Container container)
                || container.getContainerSize() != 3 || found == null || !HANDLER.canHandle(found)) return false;
        List<IngredientSpec> inputs = HANDLER.getIngredients(found);
        ItemStack output = HANDLER.getResultItem(found, resolved.registryAccess());
        if (inputs == null || inputs.size() != 2 || output.isEmpty()) return false;
        this.level = resolved;
        this.pos = pos.immutable();
        this.machine = be;
        this.inventory = container;
        this.recipe = found;
        this.specs = inputs;
        this.result = output;
        this.machineDim = resolved.dimension().location();
        this.machineServer = player.server;
        return true;
    }

    @Override public List<IngredientSpec> getRequiredMaterials() { return specs; }

    @Override
    public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, BlockPos pos) {
        if (!validateAndInit(player, recipeId, dim, pos)) {
            return PreparationResult.retry("flesh altar unavailable");
        }
        return idle() ? PreparationResult.ready() : PreparationResult.retry("flesh altar occupied");
    }

    @Override public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.machineSlotWithLocalWorldItems();
    }

    @Override public boolean supportsInputBuffer() {
        return inventory != null && !specs.isEmpty() && !hasRemainders();
    }

    private boolean hasRemainders() {
        return specs.stream().flatMap(spec -> Arrays.stream(spec.ingredient().getItems()))
                .anyMatch(ItemStack::hasCraftingRemainingItem);
    }

    @Override public InputBufferContract inputBufferContract() {
        if (!supportsInputBuffer()) return InputBufferContract.none();
        List<InputBufferContract.InputSlot> inputs = new ArrayList<>();
        for (int slot = 0; slot < specs.size(); slot++) {
            IngredientSpec spec = specs.get(slot);
            ItemStack[] candidates = spec.ingredient().getItems();
            if (candidates.length == 0) return InputBufferContract.none();
            int capacity = Math.min(64, inventory.getMaxStackSize());
            for (ItemStack candidate : candidates) capacity = Math.min(capacity, candidate.getMaxStackSize());
            inputs.add(new InputBufferContract.InputSlot("legacy:material:" + slot, slot,
                    candidates[0], spec.count(), false, capacity));
        }
        int outputLimit = Math.min(inventory.getMaxStackSize(), result.getMaxStackSize()) / result.getCount();
        int timeoutSeconds;
        try { timeoutSeconds = RSIntegrationConfig.MULTIBLOCK_CRAFT_TIMEOUT_SECONDS.get(); }
        catch (IllegalStateException | NullPointerException ignored) { timeoutSeconds = 300; }
        int timeLimit = Math.max(1, (timeoutSeconds * 20 - 20) / FleshAltarRecipeHandler.craftingTime(recipe));
        return new InputBufferContract(Math.min(outputLimit, timeLimit), inputs, outputContract().ports());
    }

    @Override public InputBufferPlan inputBufferPlan(int requestedOperations) {
        return inputBufferContract().plan(requestedOperations);
    }

    @Override public int prepareFlatBatch(int remainingOperations) {
        return supportsInputBuffer() ? inputBufferPlan(remainingOperations).operations()
                : remainingOperations > 0 ? 1 : 0;
    }

    @Override public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        int workers = Math.max(1, workerCount);
        int share = Math.max(1, (totalOperations + workers - 1) / workers);
        return supportsInputBuffer() ? Math.max(1, inputBufferPlan(share).operations()) : 1;
    }

    @Override public boolean tryStartWithInputBuffer(ServerPlayer player, InputBufferPlan plan,
                                                    ExtractionLedger sharedLedger) {
        if (plan == null || !plan.enabled() || plan.inputs().size() != 2
                || inputBufferPlan(plan.operations()).operations() != plan.operations()) return false;
        for (int i = 0; i < 2; i++) {
            InputBufferPlan.InputSlot input = plan.inputs().get(i);
            if (input.slot() != i || input.reusable() || input.perOperation() != specs.get(i).count()) return false;
        }
        return startShared(plan.inputs().stream().map(InputBufferPlan.InputSlot::stack).toList(),
                plan.operations(), sharedLedger);
    }

    @Override public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                                 ExtractionLedger sharedLedger) {
        return startShared(materials, 1, sharedLedger);
    }

    private boolean startShared(List<ItemStack> materials, int operations, ExtractionLedger sharedLedger) {
        useSharedLedger(sharedLedger);
        return place(materials, operations);
    }

    @Override public boolean tryStartSingleCraft(ServerPlayer player) {
        if (!idle()) return false;
        if (storageEndpoint() == null) network = CraftPacketUtils.resolveNetworkForCraft(player, level.dimension(), pos);
        ExtractionLedger privateLedger = new ExtractionLedger();
        privateLedger.setStorageEndpoint(storageEndpoint());
        List<ItemStack> materials = new ArrayList<>();
        for (IngredientSpec spec : specs) {
            ItemStack stack = CraftPacketUtils.ensureMaterialAvailable(player, level.dimension(), pos,
                    spec.ingredient(), spec.count(), privateLedger);
            if (stack.isEmpty()) { privateLedger.close(); return false; }
            materials.add(stack.copy());
        }
        if (!privateLedger.commit(network, player)) { privateLedger.close(); return false; }
        ledger = privateLedger;
        usingSharedLedger = false;
        if (place(materials, 1)) return true;
        privateLedger.refundCommitted(network, player);
        return false;
    }

    private boolean idle() {
        if (started || level == null || !level.hasChunkAt(pos) || machine.isRemoved()
                || level.getBlockEntity(pos) != machine) return false;
        for (int i = 0; i < 3; i++) if (!inventory.getItem(i).isEmpty()) return false;
        return true;
    }

    private boolean place(List<ItemStack> materials, int operations) {
        if (!idle() || operations < 1 || materials.size() != 2) return false;
        List<ItemStack> inputs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            IngredientSpec spec = specs.get(i);
            ItemStack stack = materials.get(i);
            int count = spec.count() * operations;
            if (stack == null || stack.isEmpty() || stack.getCount() != count
                    || count > Math.min(inventory.getMaxStackSize(), stack.getMaxStackSize())
                    || !IngredientMatcher.test(spec.ingredient(), stack)) return false;
            inputs.add(stack.copy());
        }
        if ((long) result.getCount() * operations > Math.min(inventory.getMaxStackSize(), result.getMaxStackSize())
                || !selectsRequestedRecipe(inputs)) return false;
        placed = List.copyOf(inputs);
        activeOperations = operations;
        for (int i = 0; i < 2; i++) inventory.setItem(i, inputs.get(i).copy());
        machine.setChanged();
        started = true;
        markCraftStarted();
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean selectsRequestedRecipe(List<ItemStack> inputs) {
        // 两个配方材料相同时，由原配方管理器决定实际执行哪个；不能把错误配方作为目标投料。
        SimpleContainer preview = new SimpleContainer(inputs.get(0), inputs.get(1), ItemStack.EMPTY);
        Recipe<?> selected = (Recipe<?>) level.getRecipeManager()
                .getRecipeFor((RecipeType) recipe.getType(), preview, level).orElse(null);
        return selected != null && selected.getId().equals(recipe.getId());
    }

    @Override protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        if (!started) return workingObservation();
        if (be != machine) return failObservation("flesh altar replaced");
        ItemStack output = inventory.getItem(2);
        if (!output.isEmpty() && (!ItemStack.isSameItemSameTags(output, result)
                || output.getCount() > expectedCount() || output.getCount() % result.getCount() != 0)) {
            return failObservation("unexpected flesh altar output");
        }
        int completed = output.isEmpty() ? 0 : output.getCount() / result.getCount();
        for (int i = 0; i < 2; i++) {
            ItemStack actual = inventory.getItem(i);
            int remaining = specs.get(i).count() * (activeOperations - completed);
            if (remaining == 0) {
                ItemStack remainder = placed.get(i).getCraftingRemainingItem();
                if (!actual.isEmpty() && (remainder.isEmpty() || !ItemStack.isSameItemSameTags(remainder, actual))) {
                    return failObservation("flesh altar inputs not consumed");
                }
            } else if (!MaterialMatcher.sameRuntimeFragment(placed.get(i), actual) || actual.getCount() != remaining) {
                return failObservation("flesh altar input changed or output extracted");
            }
        }
        return completed == activeOperations ? doneObservation() : workingObservation();
    }

    @Override protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return observeMachineCraft(level, be).phase() == CraftPhase.DONE;
    }

    private int expectedCount() { return result.getCount() * activeOperations; }

    @Override public ExpectedProduction getExpectedProduction() {
        return result.isEmpty() ? null : new ExpectedProduction(result, expectedCount());
    }

    @Override public OutputContract outputContract() {
        if (result.isEmpty() || hasRemainders()) return OutputContract.none();
        return new OutputContract(List.of(new OutputContract.Port(PRIMARY_PORT, 2, result,
                result.getCount(), InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.SLOT)));
    }

    @Override public ItemStack collectResult(ServerPlayer player) {
        if (!started || !level.hasChunkAt(pos) || level.getBlockEntity(pos) != machine) return ItemStack.EMPTY;
        ItemStack visible = inventory.getItem(2);
        if (!ItemStack.isSameItemSameTags(visible, result) || visible.getCount() < expectedCount()) return ItemStack.EMPTY;
        ItemStack collected = inventory.removeItem(2, expectedCount());
        machine.setChanged();
        if (ledger != null && !usingSharedLedger) ledger.settleAllCommitted();
        return collected;
    }

    @Override public List<ItemStack> collectAllResults(ServerPlayer player) {
        ItemStack primary = collectResult(player);
        if (primary.isEmpty()) return List.of();
        List<ItemStack> outputs = new ArrayList<>();
        outputs.add(primary);
        for (int i = 0; i < placed.size(); i++) {
            ItemStack remainder = placed.get(i).getCraftingRemainingItem();
            ItemStack actual = inventory.getItem(i);
            if (!remainder.isEmpty() && ItemStack.isSameItemSameTags(remainder, actual)) {
                outputs.add(inventory.removeItem(i, actual.getCount()));
            }
        }
        return outputs;
    }

    @Override public boolean collectsPhysicalSecondaryOutputs() { return true; }

    @Override public List<OutputAccounting.CollectedOutput> collectStructuredResults(ServerPlayer player) {
        ItemStack collected = collectResult(player);
        return collected.isEmpty() ? List.of() : List.of(new OutputAccounting.CollectedOutput(
                PRIMARY_PORT, OutputContract.Source.SLOT, collected));
    }

    @Override protected void clearMachineState(BlockEntity be, @Nullable ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        if (started && be == machine) {
            for (int i = 0; i < placed.size(); i++) {
                ItemStack actual = inventory.getItem(i);
                ItemStack supplied = placed.get(i);
                if (MaterialMatcher.sameRuntimeFragment(supplied, actual)) {
                    ItemStack removed = inventory.removeItem(i, Math.min(actual.getCount(), supplied.getCount()));
                    if (!removed.isEmpty()) recovered.add(removed);
                }
            }
            machine.setChanged();
        }
        recordFailureRecoveredInputs(recovered);
        if (ledger != null && !usingSharedLedger) {
            for (ItemStack stack : recovered) insertIntoStorage(player, stack, false);
            ledger.settleAllCommitted();
        }
        started = false;
        resetState();
    }

    @Override public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        started = false;
        placed = List.of();
        resetState();
    }

    @Override public BlockPos getMachinePos() { return pos; }

    @Override public String describeExecutionState() {
        return "flesh_altar operations=" + activeOperations + " output="
                + (inventory == null ? 0 : inventory.getItem(2).getCount()) + "/" + expectedCount();
    }
}
