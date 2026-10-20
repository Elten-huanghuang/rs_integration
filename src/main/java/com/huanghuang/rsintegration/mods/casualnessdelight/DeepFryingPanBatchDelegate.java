package com.huanghuang.rsintegration.mods.casualnessdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.recipe.DeepFryingRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.CasualnessDelightReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 随意乐事炸锅委托。
 *
 * <p>原生炸锅只有一个输入槽，能接收整叠原料；每完成一份就向锅边生成一个世界掉落，
 * 并从输入槽扣一个。因此批量启动只调用一次 {@code addItemToCook}，输出由世界捕获累计。</p>
 */
public final class DeepFryingPanBatchDelegate extends AbstractBatchDelegate {

    private static final int INPUT_SLOT = 0;
    // 默认 MaterialPlan 使用 legacy:material:<index> 作为稳定 ID，输入缓冲
    // 必须沿用同一身份，否则预扣材料无法绑定到炸锅唯一输入槽。
    static final String INPUT_ID = "legacy:material:0";
    private static final String OUTPUT_ID = "casualness_delight:deep_frying_pan:output";

    private ServerPlayer player;
    private ServerLevel myLevel;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private ItemStack placedInput = ItemStack.EMPTY;
    private int plannedOperations = 1;
    private int activeOperations = 1;
    private boolean craftStarted;
    private boolean observedCooking;

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return new BatchConcurrencyCapabilities(
                BatchConcurrencyCapabilities.MaterialOwnership.CHAIN_RESERVED,
                BatchConcurrencyCapabilities.OutputOwnership.OWNED_WORLD_CAPTURE,
                BatchConcurrencyCapabilities.CleanupContract.SEPARABLE_OFFLINE,
                BatchConcurrencyCapabilities.SideEffects.LOCAL_WORLD_ITEMS,
                BatchConcurrencyCapabilities.PreparationContract.RETRY_SAFE,
                List.of());
    }

    @Override
    public boolean allowsOverlappingOutputCaptureOrigins() {
        return true;
    }

    @Override
    public boolean validateAndInit(ServerPlayer player, ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (level == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.dim_not_found"));
            return false;
        }
        this.player = player;
        this.myLevel = level;
        this.machineDim = level.dimension().location();
        this.myPos = pos;
        this.recipe = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!isSupportedRecipe(recipe)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        BlockEntity be = level.getBlockEntity(pos);
        IItemHandler inventory = getInventory(be);
        if (!CasualnessDelightReflection.isDeepFryingPan(be)
                || inventory == null || inventory.getSlots() <= INPUT_SLOT
                || resultItem().isEmpty()) return false;
        placedInput = ItemStack.EMPTY;
        plannedOperations = 1;
        activeOperations = 1;
        craftStarted = false;
        observedCooking = false;
        return true;
    }

    public static boolean isSupportedRecipe(@Nullable Recipe<?> candidate) {
        return candidate != null
                && DeepFryingRecipeHandler.RECIPE_CLASS.equals(candidate.getClass().getName())
                && !candidate.isSpecial();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (!isSupportedRecipe(recipe)) return null;
        var handler = ModRecipeHandlers.handlerFor(recipe);
        return handler == null ? null : handler.getIngredients(recipe);
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        return false;
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        if (!supportsInputBuffer()) return Math.max(0, Math.min(1, remainingOperations));
        plannedOperations = inputBufferPlan(Math.max(1, remainingOperations)).operations();
        return plannedOperations;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        plannedOperations = Math.max(1, executions);
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        int capacity = supportsInputBuffer()
                ? inputBufferContract().operationLimit() : 1;
        return ParallelBatchSizing.boundedEvenShare(totalOperations, workerCount,
                Math.max(1, capacity));
    }

    @Override
    public int flatBatchOperationLimit(int configuredLimit) {
        return supportsInputBuffer()
                ? Math.max(Math.max(1, configuredLimit), inputBufferLimit())
                : Math.max(1, configuredLimit);
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return supportsInputBuffer();
    }

    @Override
    public boolean supportsInputBuffer() {
        if (!inputBufferEnabled() || recipe == null || myLevel == null || myPos == null
                || !myLevel.hasChunkAt(myPos)) return false;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        IItemHandler inventory = getInventory(be);
        List<IngredientSpec> specs = getRequiredMaterials();
        ItemStack result = resultItem();
        return CasualnessDelightReflection.isDeepFryingPan(be)
                && inventory != null && inventory.getSlots() > INPUT_SLOT
                && specs != null && specs.size() == 1
                && specs.get(0).count() == 1
                && specs.get(0).role() == DemandRole.CONSUMED
                && !ingredientPrototype(specs.get(0)).isEmpty()
                && !result.isEmpty();
    }

    @Override
    public InputBufferContract inputBufferContract() {
        if (!supportsInputBuffer()) return InputBufferContract.none();
        BlockEntity be = myLevel.getBlockEntity(myPos);
        IItemHandler inventory = getInventory(be);
        IngredientSpec spec = getRequiredMaterials().get(0);
        ItemStack prototype = ingredientPrototype(spec);
        int capacity = Math.max(1, Math.min(inputBufferLimit(),
                Math.min(inventory.getSlotLimit(INPUT_SLOT), prototype.getMaxStackSize())));
        ItemStack result = resultItem();
        return new InputBufferContract(capacity,
                List.of(new InputBufferContract.InputSlot(INPUT_ID, INPUT_SLOT,
                        prototype, 1, false, capacity)),
                List.of(new OutputContract.Port(OUTPUT_ID, null, result,
                        result.getCount(), InputBufferPlan.OutputPort.Kind.PRIMARY,
                        OutputContract.Source.WORLD)));
    }

    @Override
    public InputBufferPlan inputBufferPlan(int requestedOperations) {
        return inputBufferContract().plan(requestedOperations);
    }

    @Override
    public boolean startBufferedOperation(ServerPlayer player, InputBufferPlan plan,
                                           ExtractionLedger sharedLedger) {
        if (!supportsInputBuffer() || plan == null || !plan.enabled()
                || plan.operations() <= 0) return false;
        InputBufferPlan.InputSlot input = plan.inputs().stream()
                .filter(slot -> INPUT_ID.equals(slot.entryId()))
                .findFirst().orElse(null);
        List<IngredientSpec> specs = getRequiredMaterials();
        if (input == null || specs == null || specs.size() != 1
                || input.reusable() || input.perOperation() != 1
                || input.stack().getCount() != plan.operations()
                || !specs.get(0).ingredient().test(input.stack())) return false;
        plannedOperations = plan.operations();
        return startPreparedOperation(player, List.of(input.stack()), sharedLedger);
    }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        if (recipe == null || myLevel == null || myPos == null || materials.size() != 1) return false;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        IItemHandler inventory = getInventory(be);
        if (!CasualnessDelightReflection.isDeepFryingPan(be)
                || inventory == null || inventory.getSlots() <= INPUT_SLOT
                || !inventory.getStackInSlot(INPUT_SLOT).isEmpty()) return false;
        ItemStack input = materials.get(0);
        int operations = supportsInputBuffer() ? Math.max(1, plannedOperations) : 1;
        if (input.isEmpty() || input.getCount() != operations) return false;
        IngredientSpec spec = getRequiredMaterials() == null ? null : getRequiredMaterials().get(0);
        if (spec == null || !spec.ingredient().test(input)) return false;
        if (!isHeated(be)) {
            player.sendSystemMessage(Component.translatable("rsi.casualness_delight.no_heat"));
            return false;
        }

        this.player = player;
        this.usingSharedLedger = true;
        ItemStack remainder = addItemToCook(be, input.copy(), player);
        if (!remainder.isEmpty()) return false;
        placedInput = input.copy();
        activeOperations = operations;
        craftStarted = true;
        observedCooking = false;
        forceMachineChunk(myLevel, myPos, true);
        be.setChanged();
        markCraftStarted();
        return true;
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        if (!CasualnessDelightReflection.isDeepFryingPan(be)) {
            return failObservation("deep frying pan block entity changed");
        }
        ItemStack stored = getStoredStack(be);
        if (!stored.isEmpty() && !sameItem(stored, placedInput)) {
            return failObservation("deep frying pan input changed");
        }
        if (isCooking(be)) observedCooking = true;
        if (stored.isEmpty() && observedCooking) return doneObservation();
        return workingObservation();
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return craftStarted && observedCooking && getStoredStack(be).isEmpty();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        // 炸锅原生通过世界掉落产出，捕获器负责消费实体；这里不能重复取槽。
        return ItemStack.EMPTY;
    }

    @Override
    public List<OutputAccounting.CollectedOutput> collectStructuredResults(ServerPlayer player) {
        return List.of();
    }

    @Override
    public ItemStack getExpectedOutput() {
        ItemStack result = resultItem();
        if (result.isEmpty()) return null;
        long count = (long) result.getCount() * Math.max(1, activeOperations);
        return result.copyWithCount(count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count);
    }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack expected = getExpectedOutput();
        return expected == null || expected.isEmpty()
                ? null : new ExpectedProduction(expected, expected.getCount());
    }

    @Override
    public OutputContract outputContract() {
        if (!supportsInputBuffer()) return OutputContract.none();
        ItemStack result = resultItem();
        return result.isEmpty() ? OutputContract.none() : new OutputContract(List.of(
                new OutputContract.Port(OUTPUT_ID, null, result, result.getCount(),
                        InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.WORLD)));
    }

    @Override
    public AABB getOutputCaptureRegion() {
        return myPos == null ? null : new AABB(myPos).inflate(1.5);
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        IItemHandler inventory = getInventory(be);
        if (inventory != null && inventory.getSlots() > INPUT_SLOT) {
            ItemStack current = inventory.getStackInSlot(INPUT_SLOT);
            if (!current.isEmpty() && sameItem(current, placedInput)) {
                ItemStack removed = inventory.extractItem(INPUT_SLOT, current.getCount(), false);
                if (!removed.isEmpty()) {
                    recovered.add(removed.copy());
                    if (!usingSharedLedger) refundToStorage(removed);
                }
            }
            be.setChanged();
        }
        recordFailureRecoveredInputs(recovered);
        forceMachineChunk(myLevel, myPos, false);
        resetOperationState();
        resetState();
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        recordFailureRecoveredInputs(List.of());
        resetOperationState();
        resetState();
    }

    @Override
    public void onBatchFinished(@Nullable ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        forceMachineChunk(myLevel, myPos, false);
        resetOperationState();
        resetState();
    }

    @Override
    public BlockPos getMachinePos() {
        return myPos;
    }

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                   @Nullable ResourceLocation dim,
                                                   @Nullable BlockPos pos) {
        return List.of(Component.translatable("rsi.casualness_delight.heat_warning"));
    }

    private void resetOperationState() {
        placedInput = ItemStack.EMPTY;
        craftStarted = false;
        observedCooking = false;
        plannedOperations = 1;
        activeOperations = 1;
    }

    private ItemStack resultItem() {
        return recipe == null || myLevel == null ? ItemStack.EMPTY
                : ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
    }

    private static ItemStack ingredientPrototype(IngredientSpec spec) {
        return spec == null || spec.ingredient() == null ? ItemStack.EMPTY
                : Arrays.stream(spec.ingredient().getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    private static boolean sameItem(ItemStack left, ItemStack right) {
        return !left.isEmpty() && !right.isEmpty()
                && ItemStack.isSameItemSameTags(left, right);
    }

    private static boolean inputBufferEnabled() {
        try {
            return RSIntegrationConfig.ENABLE_CD_DEEP_FRYING_PAN_INPUT_BUFFER.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return false;
        }
    }

    private static int inputBufferLimit() {
        try {
            return Math.max(1, RSIntegrationConfig.CD_DEEP_FRYING_PAN_INPUT_BUFFER_LIMIT.get());
        } catch (IllegalStateException | NullPointerException ignored) {
            return 1;
        }
    }

    @Nullable
    private static IItemHandler getInventory(@Nullable BlockEntity be) {
        if (be == null || !CasualnessDelightReflection.isDeepFryingPan(be)) return null;
        try {
            Object value = be.getClass().getMethod("getInventory").invoke(be);
            return value instanceof IItemHandler handler ? handler : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return be.getCapability(ForgeCapabilities.ITEM_HANDLER)
                    .resolve().orElse(null);
        }
    }

    private static ItemStack getStoredStack(BlockEntity be) {
        try {
            Object value = be.getClass().getMethod("getStoredStack").invoke(be);
            return value instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            IItemHandler inventory = getInventory(be);
            return inventory == null || inventory.getSlots() <= INPUT_SLOT
                    ? ItemStack.EMPTY : inventory.getStackInSlot(INPUT_SLOT);
        }
    }

    private static boolean isCooking(BlockEntity be) {
        return invokeBoolean(be, "isCooking");
    }

    private static boolean isHeated(BlockEntity be) {
        return invokeBoolean(be, "isHeated");
    }

    private static boolean invokeBoolean(BlockEntity be, String method) {
        try {
            return Boolean.TRUE.equals(be.getClass().getMethod(method).invoke(be));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static ItemStack addItemToCook(BlockEntity be, ItemStack input, Player player) {
        try {
            Object value = be.getClass().getMethod("addItemToCook", ItemStack.class, Player.class)
                    .invoke(be, input, player);
            return value instanceof ItemStack stack ? stack : input;
        } catch (ReflectiveOperationException | RuntimeException e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Batch-DeepFrying] addItemToCook failed", e);
            return input;
        }
    }

    private void refundToStorage(ItemStack stack) {
        ItemStack remainder = insertIntoStorage(player, stack, false);
        if (!remainder.isEmpty() && player != null) ItemHandlerHelper.giveItemToPlayer(player, remainder);
    }
}
