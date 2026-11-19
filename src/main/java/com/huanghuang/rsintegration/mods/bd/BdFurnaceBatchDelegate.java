package com.huanghuang.rsintegration.mods.bd;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.crafting.batch.ReceivingMachineDelegate;
import com.huanghuang.rsintegration.crafting.consumption.InputConsumption;
import com.huanghuang.rsintegration.crafting.progress.MachineCycleProgress;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import com.wintercogs.beyonddimensions.api.storage.key.impl.ItemStackKey;
import com.wintercogs.beyonddimensions.common.block.entity.BaseNetFurnaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class BdFurnaceBatchDelegate extends ReceivingMachineDelegate {
    private static final String OUTPUT = "beyonddimensions:furnace_output";
    private static final String INPUT = "slot:0";
    private static final Mode RECEIVE = Mode.find("receiveMode");
    private static final Mode POP = Mode.find("popMode");

    private ServerLevel level;
    private BlockPos pos;
    private BaseNetFurnaceBlockEntity<?> furnace;
    private BdFurnaceOutputHook<?> hook;
    private AbstractCookingRecipe recipe;
    private ItemStack result = ItemStack.EMPTY;
    private ItemStack ownedInput = ItemStack.EMPTY;
    private ItemStack notPlaced = ItemStack.EMPTY;
    private int remainingInputs;
    private int activeOperations;
    private int[] pendingOutput;
    private boolean inputChanged;

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        return prepare(player, recipeId, dim, pos).state() == PreparationState.READY;
    }

    @Override
    public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, BlockPos pos) {
        this.pos = pos;
        level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null || !level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof BaseNetFurnaceBlockEntity<?> machine)
                || !(machine instanceof BdFurnaceOutputHook<?> outputHook)) {
            return PreparationResult.retry("BD furnace unavailable");
        }
        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!supportsRecipe(found) || found.getType() != outputHook.rsi$recipeType()) {
            return PreparationResult.retry("unsupported BD furnace recipe");
        }
        furnace = machine;
        hook = outputHook;
        recipe = (AbstractCookingRecipe) found;
        result = recipe.getResultItem(level.registryAccess()).copy();
        if (result.isEmpty() || result.getCount() > result.getMaxStackSize()) {
            return PreparationResult.retry("invalid BD furnace result");
        }
        if (!canUseSlots()) {
            return PreparationResult.waiting("BD furnace slots or settings are occupied",
                    Component.translatable("rsi.bd_furnace.waiting"));
        }
        if (!hasFuelSupply() && furnace.getLitTime().stream().noneMatch(time -> time > 0)) {
            return PreparationResult.waiting("BD furnace needs fuel",
                    Component.translatable("rsi.bd_furnace.no_fuel"));
        }
        setMachineServer(player.server);
        setMachineDim(level.dimension().location());
        return PreparationResult.ready();
    }

    private static boolean supportsRecipe(@Nullable Recipe<?> recipe) {
        if (recipe == null) return false;
        Class<?> type = recipe.getClass();
        return type == SmeltingRecipe.class || type == BlastingRecipe.class || type == SmokingRecipe.class;
    }

    private boolean canUseSlots() {
        return furnace.getInputStorageSlots().isEmpty() && furnace.getOutputStorageSlots().isEmpty()
                && furnace.getInputFilterSlots().isEmpty()
                && (!POP.open(furnace) || RECEIVE.open(furnace));
    }

    @Override
    public MaterialPlan materialPlan() {
        return MaterialPlan.graphSlots(CraftPacketUtils.extractIngredientSpecs(recipe));
    }

    private boolean hasFuelSupply() {
        return !furnace.getFuelStorageSlots().isEmpty()
                || furnace.getNet() != null && !furnace.getFuelFilterSlots().isEmpty();
    }

    @Override
    public boolean supportsInputBuffer() {
        return inputBufferContract().enabled();
    }

    @Override
    public InputBufferContract inputBufferContract() {
        if (recipe == null || recipe.getIngredients().isEmpty()) return InputBufferContract.none();
        ItemStack prototype = Arrays.stream(recipe.getIngredients().get(0).getItems())
                .filter(stack -> !stack.isEmpty())
                .min(Comparator.comparingInt(ItemStack::getMaxStackSize)).orElse(ItemStack.EMPTY);
        return bufferContract(prototype, false);
    }

    private InputBufferContract bufferContract(ItemStack input, boolean checkRecipe) {
        if (input.isEmpty() || result.isEmpty() || !isCurrent()) return InputBufferContract.none();
        var inputs = furnace.getInputStorageSlots();
        var outputs = furnace.getOutputStorageSlots();
        boolean hasFuel = hasFuelSupply();
        ItemStack single = input.copyWithCount(1);
        SimpleContainer container = checkRecipe ? new SimpleContainer(single) : null;
        List<InputBufferContract.InputSlot> slots = new ArrayList<>();
        int limit = 0;
        for (int slot = 0; slot < furnace.getCapacity(); slot++) {
            if (furnace.getCookTime().get(slot) != 0 || (!hasFuel && furnace.getLitTime().get(slot) <= 0)) {
                continue;
            }
            if (container != null && hook.rsi$recipeChecks().get(slot).getRecipeFor(container, level)
                    .filter(actual -> actual.getId().equals(recipe.getId())).isEmpty()) continue;
            long outputCapacity = Math.min(outputs.getSlotCapacity(slot), result.getMaxStackSize());
            int capacity = (int) Math.min(outputCapacity / result.getCount(),
                    Math.min(inputs.getSlotCapacity(slot), single.getMaxStackSize()));
            if (capacity <= 0) continue;
            limit += capacity;
            slots.add(new InputBufferContract.InputSlot(INPUT, slot, single, 1,
                    capacity, InputConsumption.consumed()));
        }
        slots.sort(Comparator.comparingInt(
                (InputBufferContract.InputSlot slot) -> furnace.getLitTime().get(slot.slot())).reversed());
        return new InputBufferContract(limit, slots, outputContract().ports());
    }

    @Override
    public InputBufferPlan inputBufferPlan(int requestedOperations) {
        return inputBufferContract().plan(requestedOperations);
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        return Math.min(Math.max(0, remainingOperations), inputBufferContract().operationLimit());
    }

    @Override
    public int flatBatchOperationLimit(int configuredLimit) {
        return Math.max(Math.max(1, configuredLimit), inputBufferContract().operationLimit());
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return inputBufferContract().operationLimit() > 1;
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return ParallelBatchSizing.boundedEvenShare(totalOperations, workerCount,
                inputBufferContract().operationLimit());
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        ledger = materialAccess().newLedger();
        ledger.setStorageEndpoint(storageEndpoint());
        ItemStack input = CraftPacketUtils.ensureMaterialAvailable(player, level.dimension(), pos,
                recipe.getIngredients().get(0), 1, ledger);
        return !input.isEmpty() && ledger.commitToEndpoint(storageEndpoint(), player) && startBatch(input);
    }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials, ExtractionLedger sharedLedger) {
        if (materials.size() != 1) return false;
        useSharedLedger(sharedLedger);
        return startBatch(materials.get(0));
    }

    @Override
    public boolean startBufferedOperation(ServerPlayer player, InputBufferPlan plan, ExtractionLedger sharedLedger) {
        if (!plan.enabled()) return false;
        ItemStack input = plan.inputs().get(0).stack();
        long count = 0;
        for (var slot : plan.inputs()) {
            if (!INPUT.equals(slot.entryId()) || slot.perOperation() != 1 || slot.stack().isEmpty()
                    || !ItemStack.isSameItemSameTags(input, slot.stack())) return false;
            count += slot.stack().getCount();
        }
        if (count != plan.operations()) return false;
        useSharedLedger(sharedLedger);
        // 预留完成后用确切变体重算物理布局，避免按标签代表物的堆叠上限投料。
        return startBatch(input.copyWithCount(plan.operations()));
    }

    private boolean startBatch(ItemStack input) {
        if (input.isEmpty() || activeOperations != 0 || !ownedInput.isEmpty()) return false;
        notPlaced = input.copy();
        if (!isCurrent() || !canUseSlots() || production == null) return false;
        InputBufferPlan plan = bufferContract(input, true).plan(input.getCount());
        if (plan.operations() != input.getCount()) return false;
        var slots = furnace.getInputStorageSlots();
        var key = new ItemStackKey(input.copyWithCount(1));
        for (var slot : plan.inputs()) {
            if (!slots.insert(slot.slot(), key, slot.stack().getCount(), true).isEmpty()) return false;
        }
        int count = Math.multiplyExact(result.getCount(), plan.operations());
        prepareOutputBatch(List.of(new TaskOutputReception.Demand(OUTPUT,
                TaskOutputReception.resource(result), count, count)));
        ownedInput = input.copyWithCount(1);
        for (var slot : plan.inputs()) {
            int offered = slot.stack().getCount();
            var rest = slots.insert(slot.slot(), key, offered, false);
            int placed = offered - (int) rest.amount();
            remainingInputs += placed;
            notPlaced.shrink(placed);
            if (!rest.isEmpty()) return false;
        }
        activeOperations = plan.operations();
        pendingOutput = new int[furnace.getCapacity()];
        production.activate();
        hook.rsi$setInputConsumed(this::onInputConsumed);
        hook.rsi$setOutputReceiver(this::beforeNativeOutput);
        markCraftStarted();
        return true;
    }

    private void onInputConsumed(int lane, KeyAmount input) {
        if (remainingInputs <= 0 || input.amount() != 1 || !matches(input, ownedInput)) {
            inputChanged = true;
            return;
        }
        remainingInputs--;
        pendingOutput[lane] += result.getCount();
    }

    @Override
    public boolean acceptsFixedOutput() {
        return !result.isEmpty();
    }

    private void beforeNativeOutput() {
        if (RECEIVE.open(furnace)) collectOutput();
    }

    private void collectOutput() {
        var batch = outputBatch();
        if (pendingOutput == null || batch == null || batch.closed() || !isCurrent()) return;
        var outputSlots = furnace.getOutputStorageSlots();
        for (int lane = 0; lane < pendingOutput.length; lane++) {
            if (pendingOutput[lane] <= 0) continue;
            KeyAmount visible = outputSlots.getStackBySlot(lane);
            long count = Math.min(Math.min(pendingOutput[lane], visible.amount()), batch.remaining(OUTPUT));
            if (count <= 0 || !matches(visible, result)) continue;
            // 只领取原生消耗记录对应的成品，其余物品与经验仍归 BD。
            KeyAmount taken = outputSlots.extract(lane, count, false);
            if (taken.key() instanceof ItemStackKey item && !taken.isEmpty()) {
                production.hold(item.copyStackWithCount(taken.amount()));
                pendingOutput[lane] -= (int) taken.amount();
            }
        }
    }

    private static boolean matches(KeyAmount entry, ItemStack expected) {
        return entry.key() instanceof ItemStackKey item
                && ItemStack.isSameItemSameTags(item.getReadOnlyStack(), expected);
    }

    private boolean isCurrent() {
        return level.hasChunkAt(pos) && !furnace.isRemoved() && level.getBlockEntity(pos) == furnace;
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity entity) {
        if (entity != furnace || activeOperations == 0) {
            return failObservation("BD furnace replaced or unavailable");
        }
        collectOutput();
        if (inputChanged || !ownedInputsIntact() || !furnace.getInputFilterSlots().isEmpty()
                || (POP.open(furnace) && !RECEIVE.open(furnace))) {
            return failObservation("BD furnace inputs or settings changed");
        }
        if (Arrays.stream(pendingOutput).anyMatch(count -> count != 0)) {
            return failObservation("BD furnace output was removed or did not match the recipe");
        }
        if (remainingInputs == 0) return outputBatch().complete() ? doneObservation()
                : failObservation("BD furnace output batch is incomplete");
        long cycle = Math.max(1L, (long) recipe.getCookingTime() + 1);
        long progress = (activeOperations - remainingInputs) * cycle;
        var slots = furnace.getInputStorageSlots();
        for (int lane = 0; lane < pendingOutput.length; lane++) {
            if (!slots.getStackBySlot(lane).isEmpty()) progress += furnace.getCookTime().get(lane);
        }
        observedCycle = MachineCycleProgress.of(progress, activeOperations * cycle);
        return workingObservation(progress);
    }

    private boolean ownedInputsIntact() {
        long total = 0;
        var slots = furnace.getInputStorageSlots();
        for (int lane = 0; lane < slots.getSlots(); lane++) {
            KeyAmount input = slots.getStackBySlot(lane);
            if (input.isEmpty()) continue;
            if (!matches(input, ownedInput)) return false;
            total += input.amount();
        }
        return total == remainingInputs;
    }

    @Override
    public OperationOutputSource outputSource() {
        return OperationOutputSource.incremental(level -> collectOutput(),
                player -> new OperationOutputSource.Handoff(List.of(), List.of()));
    }

    @Override
    public OutputContract outputContract() {
        if (result.isEmpty()) return OutputContract.none();
        return new OutputContract(List.of(new OutputContract.Port(OUTPUT, null, result, result.getCount(),
                OutputContract.Kind.PRIMARY, OutputContract.Source.SLOT)));
    }

    @Override
    public BlockPos getMachinePos() {
        return pos;
    }

    @Override
    protected void clearMachineState(BlockEntity entity, @Nullable ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        if (!notPlaced.isEmpty()) recovered.add(notPlaced.copy());
        if (entity == furnace && !furnace.isRemoved() && remainingInputs > 0
                && !inputChanged && ownedInputsIntact()) {
            var slots = furnace.getInputStorageSlots();
            for (int lane = 0; lane < slots.getSlots(); lane++) {
                KeyAmount input = slots.getStackBySlot(lane);
                if (input.isEmpty()) continue;
                var taken = slots.extract(lane, input.amount(), false);
                if (taken.key() instanceof ItemStackKey item && !taken.isEmpty()) {
                    recovered.add(item.copyStackWithCount(taken.amount()));
                    furnace.getCookTime().set(lane, 0);
                }
            }
            furnace.setChanged();
        }
        settleFailureRecoveredInputs(recovered, player);
        clearBatch();
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        settleFailureRecoveredInputs(notPlaced.isEmpty() ? List.of() : List.of(notPlaced.copy()), player);
        clearBatch();
    }

    @Override
    protected void clearUnavailableMachineState(@Nullable ServerPlayer player) {
        clearMissingMachineState(player);
    }

    @Override
    public void cleanup(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        clearBatch();
    }

    private void clearBatch() {
        if (activeOperations > 0) {
            hook.rsi$setOutputReceiver(null);
            hook.rsi$setInputConsumed(null);
        }
        pendingOutput = null;
        activeOperations = 0;
        remainingInputs = 0;
        ownedInput = ItemStack.EMPTY;
        notPlaced = ItemStack.EMPTY;
        resetState();
    }

    // 0.7 保存枚举字段，0.8 从机器设置读取同名枚举。
    private record Mode(@Nullable Method getter, @Nullable Field field) {
        static Mode find(String name) {
            var type = BaseNetFurnaceBlockEntity.class;
            try {
                return new Mode(type.getMethod(name), null);
            } catch (NoSuchMethodException ignored) {
                try {
                    return new Mode(null, type.getField(name));
                } catch (NoSuchFieldException absent) {
                    return new Mode(null, null);
                }
            }
        }

        boolean open(BaseNetFurnaceBlockEntity<?> furnace) {
            try {
                Object value = getter != null ? getter.invoke(furnace) : field == null ? null : field.get(furnace);
                return value instanceof Enum<?> mode && "OPEN".equals(mode.name());
            } catch (ReflectiveOperationException ignored) {
                return false;
            }
        }
    }
}
