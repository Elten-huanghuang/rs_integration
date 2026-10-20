package com.huanghuang.rsintegration.mods.bossesdelight;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.recipe.BossesDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.BossesDelightReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.RecipeWrapper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * BOMD 乐事（bosses_delight）灵魂锅批量委托。
 * <p>
 * 反编译核对（{@code SoulCookingPotBlockEntity}）的槽位与流程：
 * <ul>
 *   <li>0–5 号槽是食材，6 号槽是"待装盛"的展示槽，7 号槽是装盛容器，8 号槽是成品；</li>
 *   <li>热源不是火，而是下方方块带 {@code bosses_delight:cold_sources} 标签（冰 / 浮冰 / 蓝冰）；</li>
 *   <li>每完成一份，原生都会无条件对 0–5 全部槽位各扣 1，
 *       因此 <b>每条配方食材恰好占一个槽位</b>；</li>
 *   <li>产物先落在 6 号槽；只有 7 号槽里放着与配方 {@code getOutputContainer()} 同种的容器时，
 *       才把成品逐份搬进 8 号槽，同时每份扣掉一个容器。</li>
 * </ul>
 * 因此本委托的"一次发配 N 份"= 食材每槽各放 N 个、容器槽放 N 个，
 * 成品在 8 号槽一次收走。
 * <p>
 * 与农夫乐事烹饪锅（{@link com.huanghuang.rsintegration.mods.farmersdelight.CookingPotBatchDelegate}）
 * 的差别都在这里就地补齐，而不是复用其父类：父类认的是农夫乐事的配方类、机器类与热源，
 * 那套口径在这里会全部落空。
 * <p>
 * 一个必须处理的坑：灵魂锅的配方查询是"从 0–5 号槽收集非空物品 + 相同数量的需求物品"，
 * 因此 <b>6 份食材必须各不相同</b>；同一个物品出现在两个槽位会让 {@code RecipeMatcher} 匹配失败、
 * 锅永远不开火。这里在放料后做一次原生配方复核，匹配不上立即整批回滚。
 */
public final class SoulCookingPotBatchDelegate extends AbstractBatchDelegate {

    /** 食材槽数量（原生 {@code SoulCookingPotRecipe.INPUT_SLOTS}）。 */
    static final int INPUT_SLOTS = BossesDelightRecipeHandler.INGREDIENTS_AREA;
    /** 装盛容器槽。 */
    static final int CONTAINER_SLOT = BossesDelightRecipeHandler.CONTAINER_SLOT;
    /** 成品槽。 */
    static final int OUTPUT_SLOT = BossesDelightRecipeHandler.OUTPUT_SLOT;
    /** 展示槽，成品在装盛前先落在这里。 */
    static final int MEAL_DISPLAY_SLOT = BossesDelightRecipeHandler.MEAL_DISPLAY_SLOT;
    /** 库存总槽数。 */
    static final int INVENTORY_SIZE = BossesDelightRecipeHandler.INVENTORY_SIZE;

    /** 热源标签：下方方块必须带它才算"有冷源"。 */
    private static final ResourceLocation COLD_SOURCES_TAG =
            new ResourceLocation("bosses_delight", "cold_sources");

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;
    private boolean craftObservedWorking;
    /** 本次实际放进去的份数（输入预载时可能小于计划数）。 */
    private int activeOperations = 1;
    private int plannedOperations = 1;
    private final List<ItemStack> placedInputs = new ArrayList<>();
    private ItemStack placedContainer = ItemStack.EMPTY;

    private static volatile Field inventoryField;
    private static volatile boolean reflectionProbed;

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // 材料由共享账本预留，成品在机器自身的输出槽结算，不需要世界捕获。
        return BatchConcurrencyCapabilities.machineSlot();
    }

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
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (!isSupportedRecipe(found)) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.craftObservedWorking = false;
        this.activeOperations = 1;
        this.plannedOperations = 1;
        this.placedInputs.clear();
        this.placedContainer = ItemStack.EMPTY;

        BlockEntity existing = level.getBlockEntity(pos);
        if (existing == null || !isSupportedBlockEntity(existing)) return false;
        IItemHandler handler = getInventory(existing);
        // 准备阶段保持无副作用：只确认库存可读，不清理旧内容。
        return handler != null && handler.getSlots() >= INVENTORY_SIZE;
    }

    /** 只接受灵魂锅自己的配方类，且排除玩家给锅具点亮的特殊配方。 */
    public static boolean isSupportedRecipe(Recipe<?> candidate) {
        return candidate != null
                && BossesDelightRecipeHandler.RECIPE_CLASS.equals(candidate.getClass().getName())
                && !candidate.isSpecial();
    }

    private static boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        return BossesDelightReflection.isSoulCookingPot(blockEntity);
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        if (recipe == null) return null;
        var handler = ModRecipeHandlers.handlerFor(recipe);
        return handler != null ? handler.getIngredients(recipe) : null;
    }

    /**
     * 灵魂锅不能拆成"每份一次"的单次执行路径：
     * 展示槽被清空后它就再也装不回来，直接调用单次开局会丢产物。
     */
    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        return false;
    }

    // ── 输入预载 ──

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
                ? Math.max(Math.max(1, configuredLimit), soulPotInputBufferLimit())
                : Math.max(1, configuredLimit);
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return supportsInputBuffer();
    }

    @Override
    public boolean supportsInputBuffer() {
        if (!soulPotInputBufferEnabled() || recipe == null || myLevel == null || myPos == null
                || !myLevel.hasChunkAt(myPos)) return false;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isSupportedBlockEntity(be)) return false;
        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) return false;
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty() || specs.size() > INPUT_SLOTS + 1) return false;
        int ingredientCount = specs.size() - (requiredContainer().isEmpty() ? 0 : 1);
        if (ingredientCount <= 0 || ingredientCount > INPUT_SLOTS) return false;
        for (IngredientSpec spec : specs) {
            if (spec == null || spec.isEmpty() || spec.count() != 1
                    || spec.role() != DemandRole.CONSUMED
                    || ingredientPrototype(spec).isEmpty()) return false;
        }
        // 6 份食材必须两两不同物品，否则原生的 RecipeMatcher 永远匹配不上。
        if (!hasDistinctIngredientItems(specs, ingredientCount)) return false;
        return !resultItem().isEmpty();
    }

    /**
     * 原生的 {@code processCooking} 是"对 6 个输入槽各扣 1"，
     * 一旦展示槽（或清空后的容器槽）混进食材，食材就会在展示槽里原地打转，
     * 因此预载只提供"常温一次一份"这条路径。
     */
    private boolean hasDistinctIngredientItems(List<IngredientSpec> specs, int ingredientCount) {
        List<Item> seen = new ArrayList<>(ingredientCount);
        for (int i = 0; i < ingredientCount; i++) {
            ItemStack prototype = ingredientPrototype(specs.get(i));
            if (prototype.isEmpty()) return false;
            for (Item existing : seen) {
                if (existing == prototype.getItem()) return false;
            }
            seen.add(prototype.getItem());
        }
        return true;
    }

    @Override
    public InputBufferContract inputBufferContract() {
        if (!supportsInputBuffer()) return InputBufferContract.none();
        BlockEntity be = myLevel.getBlockEntity(myPos);
        IItemHandler handler = be == null ? null : getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) return InputBufferContract.none();
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null) return InputBufferContract.none();
        ItemStack container = requiredContainer();
        int ingredientCount = specs.size() - (container.isEmpty() ? 0 : 1);

        List<InputBufferContract.InputSlot> inputs = new ArrayList<>(specs.size());
        for (int i = 0; i < ingredientCount; i++) {
            ItemStack prototype = ingredientPrototype(specs.get(i));
            inputs.add(new InputBufferContract.InputSlot("legacy:material:" + i, i, prototype,
                    1, false, slotCapacity(handler, i, prototype)));
        }
        if (!container.isEmpty()) {
            inputs.add(new InputBufferContract.InputSlot(
                    "legacy:material:" + ingredientCount, CONTAINER_SLOT,
                    container.copyWithCount(1), 1, false,
                    slotCapacity(handler, CONTAINER_SLOT, container)));
        }
        ItemStack output = resultItem();
        int outputCapacity = slotCapacity(handler, OUTPUT_SLOT, output);
        int operations = bufferedOperationCapacity(soulPotInputBufferLimit(), inputs,
                output.getCount(), outputCapacity);
        if (operations <= 0) return InputBufferContract.none();
        return new InputBufferContract(operations, inputs,
                List.of(new OutputContract.Port(outputPortId(), OUTPUT_SLOT, output,
                        output.getCount(), InputBufferPlan.OutputPort.Kind.PRIMARY,
                        OutputContract.Source.SLOT)));
    }

    @Override
    public InputBufferPlan inputBufferPlan(int requestedOperations) {
        return inputBufferContract().plan(requestedOperations);
    }

    @Override
    public boolean startBufferedOperation(@NotNull ServerPlayer player,
                                           @NotNull InputBufferPlan plan,
                                           @NotNull ExtractionLedger sharedLedger) {
        if (!supportsInputBuffer() || plan == null || !plan.enabled()) return false;
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || plan.inputs().size() != specs.size()) return false;
        List<ItemStack> ordered = new ArrayList<>(specs.size());
        ItemStack container = requiredContainer();
        for (int i = 0; i < specs.size(); i++) {
            InputBufferPlan.InputSlot input = inputByEntryId(plan, "legacy:material:" + i);
            long required = (long) specs.get(i).count() * plan.operations();
            if (input == null || input.reusable() || input.perOperation() != specs.get(i).count()
                    || required > Integer.MAX_VALUE
                    || input.stack().getCount() != (int) required
                    || !specs.get(i).ingredient().test(input.stack())) return false;
            ordered.add(input.stack().copy());
        }
        InputBufferPlan expected = inputBufferPlan(plan.operations());
        if (!expected.enabled() || expected.operations() != plan.operations()) return false;
        plannedOperations = plan.operations();
        return startPreparedOperation(player, ordered, sharedLedger, !container.isEmpty());
    }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        return startPreparedOperation(player, materials, sharedLedger, false);
    }

    /**
     * 放料与开局。
     *
     * @param containerAppended 材料列表末尾是否追加了容器条目
     *                          （预载路径由契约生成，单次路径由调用方按同一口径拼好）
     */
    private boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                          ExtractionLedger sharedLedger, boolean containerAppended) {
        this.player = player;
        this.usingSharedLedger = true;
        this.craftDone = false;
        this.craftObservedWorking = false;

        if (recipe == null || myLevel == null || myPos == null || !myLevel.hasChunkAt(myPos)) {
            return false;
        }
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isSupportedBlockEntity(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-SoulPot] Wrong block entity at {}", myPos);
            return false;
        }
        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-SoulPot] Cannot access item handler at {}", myPos);
            return false;
        }
        if (!isHeated(be)) {
            // 没有冷源时原生不会推进，材料放进去只会停在锅里。
            player.sendSystemMessage(Component.translatable("rsi.bossesdelight.no_cold_source"));
            return false;
        }

        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null) return false;
        ItemStack container = containerAppended ? requiredContainer() : ItemStack.EMPTY;
        int ingredientCount = specs.size() - (container.isEmpty() ? 0 : 1);
        if (ingredientCount <= 0 || materials.size() != ingredientCount + (container.isEmpty() ? 0 : 1)) {
            return false;
        }

        // 陈旧内容先还给网络：它们不属于本次共享账本。
        if (storageEndpoint() == null || !drainExistingContents(handler)) return false;
        placedInputs.clear();
        placedContainer = ItemStack.EMPTY;

        int operations = Math.max(1, plannedOperations);
        forceMachineChunk(myLevel, myPos, true);

        int insertedSlots = 0;
        for (int i = 0; i < ingredientCount; i++) {
            ItemStack material = materials.get(i);
            IngredientSpec spec = specs.get(i);
            long required = (long) spec.count() * operations;
            if (material.isEmpty() || required > Integer.MAX_VALUE
                    || material.getCount() != (int) required
                    || !spec.ingredient().test(material)
                    || !handler.getStackInSlot(i).isEmpty()
                    || !handler.insertItem(i, material.copy(), true).isEmpty()) {
                rollbackInputs(handler, insertedSlots);
                forceMachineChunk(myLevel, myPos, false);
                return false;
            }
            ItemStack remainder = handler.insertItem(i, material.copy(), false);
            if (!remainder.isEmpty()) {
                rollbackInputs(handler, insertedSlots);
                forceMachineChunk(myLevel, myPos, false);
                return false;
            }
            placedInputs.add(material.copy());
            insertedSlots++;
        }

        if (!container.isEmpty()) {
            ItemStack containerMaterial = materials.get(ingredientCount);
            long required = (long) specs.get(specs.size() - 1).count() * operations;
            if (containerMaterial.isEmpty() || required > Integer.MAX_VALUE
                    || containerMaterial.getCount() != (int) required
                    || !handler.getStackInSlot(CONTAINER_SLOT).isEmpty()
                    || !handler.insertItem(CONTAINER_SLOT, containerMaterial.copy(), true).isEmpty()
                    || !handler.insertItem(CONTAINER_SLOT, containerMaterial.copy(), false).isEmpty()) {
                rollbackInputs(handler, insertedSlots);
                forceMachineChunk(myLevel, myPos, false);
                return false;
            }
            placedContainer = containerMaterial.copy();
        }
        be.setChanged();
        activeOperations = operations;

        // 原生配方复核：6 份食材中若有重复物品，锅会一直不开火，
        // 这时必须整批回滚，而不是让材料烂在锅里。
        if (!matchesPreparedRecipe(be)) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-SoulPot] Recipes {} rejected its prepared slots at {}",
                    recipe.getId(), myPos);
            rollbackInputs(handler, insertedSlots);
            forceMachineChunk(myLevel, myPos, false);
            return false;
        }

        markCraftStarted();
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-SoulPot] Materials inserted at {}", myPos);
        return true;
    }

    /**
     * 用原生 {@code matches()} 复核铺好的槽位。
     * <p>
     * 灵魂锅的配方类声明的是 {@code Recipe<RecipeWrapper>}，
     * 因此容器类型不是问题，直接把灵魂锅自己的 {@code ItemStackHandler} 包一层即可。
     */
    private boolean matchesPreparedRecipe(BlockEntity be) {
        IItemHandler handler = getInventory(be);
        if (handler == null || recipe == null || myLevel == null) return false;
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            boolean matched = ((Recipe) recipe).matches(
                    new RecipeWrapper((IItemHandlerModifiable) handler),
                    myLevel);
            return matched;
        } catch (RuntimeException e) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-SoulPot] Recipe {} match probe failed", recipe.getId(), e);
            return false;
        }
    }

    // ── 观测与结算 ──

    @Override
    @NotNull
    protected CraftObservation observeMachineCraft(@NotNull ServerLevel level,
                                                   @NotNull BlockEntity be) {
        if (!isSupportedBlockEntity(be)) {
            return failObservation("soul cooking pot block entity changed");
        }
        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) {
            return failObservation("soul cooking pot inventory unavailable");
        }
        if (!isHeated(be)) {
            return failObservation("soul cooking pot lost its cold source");
        }

        ItemStack output = handler.getStackInSlot(OUTPUT_SLOT);
        if (!output.isEmpty()) {
            if (!isExpectedOutput(output)) {
                return failObservation("soul cooking pot output slot held another item");
            }
            int expected = expectedOutputCount();
            if (output.getCount() >= expected) return doneObservation();
            craftObservedWorking = true;
            return workingObservation();
        }

        // 产物还压在展示槽里等容器：属于"烹饪中后段"，不能判 DONE。
        ItemStack meal = handler.getStackInSlot(MEAL_DISPLAY_SLOT);
        if (!meal.isEmpty() && isExpectedOutput(meal)) {
            craftObservedWorking = true;
            return workingObservation();
        }
        if (craftObservedWorking && !hasOwnedInputLeft(handler)) {
            // 食材已耗尽却既没进输出槽也没留在展示槽 = 产物丢失，交给上层走失败清理。
            return failObservation("soul cooking pot finished without producing its meal");
        }
        if (!areOwnedInputsPresent(handler)) {
            return failObservation("soul cooking pot inputs changed before the recipe started");
        }
        return new CraftObservation(phase);
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isSupportedBlockEntity(be)) return false;
        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) return false;
        ItemStack output = handler.getStackInSlot(OUTPUT_SLOT);
        return !output.isEmpty() && isExpectedOutput(output)
                && output.getCount() >= expectedOutputCount();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;
        IItemHandler handler = getInventory(be);
        if (handler == null) return ItemStack.EMPTY;
        ItemStack visible = handler.getStackInSlot(OUTPUT_SLOT);
        if (!isExpectedOutput(visible)) return ItemStack.EMPTY;
        int amount = Math.min(visible.getCount(), expectedOutputCount());
        ItemStack result = handler.extractItem(OUTPUT_SLOT, amount, false);
        if (!result.isEmpty()) be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    public List<OutputAccounting.CollectedOutput> collectStructuredResults(ServerPlayer player) {
        ItemStack result = collectResult(player);
        return result.isEmpty() ? List.of() : List.of(new OutputAccounting.CollectedOutput(
                outputPortId(), OutputContract.Source.SLOT, result));
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        List<ItemStack> recovered = new ArrayList<>();
        if (be != null && isSupportedBlockEntity(be)) {
            IItemHandler handler = getInventory(be);
            if (handler != null && handler.getSlots() >= INVENTORY_SIZE) {
                recovered.addAll(clearOwnedSlots(handler, !usingSharedLedger));
                be.setChanged();
            }
        }
        recordFailureRecoveredInputs(recovered);
        releaseMachineChunk();
        craftDone = false;
        craftObservedWorking = false;
        resetState();
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        placedInputs.clear();
        placedContainer = ItemStack.EMPTY;
        craftDone = false;
        craftObservedWorking = false;
        resetState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        if (!markTerminalCleanup()) return;
        if (myLevel != null && myPos != null && myLevel.hasChunkAt(myPos)) {
            BlockEntity be = myLevel.getBlockEntity(myPos);
            if (be != null && isSupportedBlockEntity(be)) {
                IItemHandler handler = getInventory(be);
                if (handler != null && handler.getSlots() >= INVENTORY_SIZE) {
                    clearOwnedSlots(handler, !usingSharedLedger);
                    be.setChanged();
                }
            }
        }
        releaseMachineChunk();
        craftDone = false;
        craftObservedWorking = false;
        resetState();
    }

    // ── 账本与清理 ──

    private boolean drainExistingContents(IItemHandler handler) {
        boolean drained = true;
        boolean changed = false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack existing = handler.getStackInSlot(slot);
            if (existing.isEmpty()) continue;
            ItemStack removed = handler.extractItem(slot, existing.getCount(), false);
            if (removed.isEmpty()) {
                drained = false;
                continue;
            }
            changed = true;
            ItemStack leftover = insertIntoStorage(player, removed.copy(), false);
            if (!leftover.isEmpty() && player != null) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
            if (!handler.getStackInSlot(slot).isEmpty()) drained = false;
        }
        return drained || !changed;
    }

    /**
     * 回滚"本次放进去的"材料：只用插料时记录的堆叠做精确匹配，
     * 不碰锅里的其他内容（例如玩家手工放进去的东西）。
     */
    private void rollbackInputs(IItemHandler handler, int insertedSlots) {
        for (int i = 0; i < insertedSlots; i++) {
            extractOwned(handler, i, placedInputs.size() > i ? placedInputs.get(i) : ItemStack.EMPTY);
        }
        placedInputs.clear();
        extractOwned(handler, CONTAINER_SLOT, placedContainer);
        placedContainer = ItemStack.EMPTY;
    }

    /**
     * 终结清理：清掉本次放进去的食材与容器，以及已经产出但没被收走的成品。
     *
     * @param refundInputs 使用私有账本时才直接退回网络；共享账本的回退由账本自己完成，
     *                     这里只负责把物理内容移出机器，避免重复退款。
     */
    private List<ItemStack> clearOwnedSlots(IItemHandler handler, boolean refundInputs) {
        List<ItemStack> recovered = new ArrayList<>();
        for (int i = 0; i < placedInputs.size(); i++) {
            ItemStack removed = extractOwned(handler, i, placedInputs.get(i));
            if (removed.isEmpty()) continue;
            recovered.add(removed.copy());
            if (refundInputs) refundToStorage(removed);
        }
        ItemStack container = extractOwned(handler, CONTAINER_SLOT, placedContainer);
        if (!container.isEmpty()) {
            recovered.add(container.copy());
            if (refundInputs) refundToStorage(container);
        }
        placedInputs.clear();
        placedContainer = ItemStack.EMPTY;

        ItemStack meal = handler.getStackInSlot(MEAL_DISPLAY_SLOT);
        if (!meal.isEmpty() && isExpectedOutput(meal)) {
            ItemStack removed = handler.extractItem(MEAL_DISPLAY_SLOT, meal.getCount(), false);
            if (!removed.isEmpty()) {
                recovered.add(removed.copy());
                reclaimProduced(removed);
            }
        }
        ItemStack output = handler.getStackInSlot(OUTPUT_SLOT);
        if (!output.isEmpty() && isExpectedOutput(output)) {
            ItemStack removed = handler.extractItem(OUTPUT_SLOT, output.getCount(), false);
            if (!removed.isEmpty()) {
                recovered.add(removed.copy());
                reclaimProduced(removed);
            }
        }
        return recovered;
    }

    /** 成品不属于输入账本，无论材料归属如何都要还给网络。 */
    private void reclaimProduced(ItemStack stack) {
        if (!stack.isEmpty()) refundToStorage(stack);
    }

    private ItemStack extractOwned(IItemHandler handler, int slot, ItemStack expected) {
        if (expected == null || expected.isEmpty()) return ItemStack.EMPTY;
        ItemStack visible = handler.getStackInSlot(slot);
        if (visible.isEmpty() || !ItemStack.isSameItemSameTags(visible, expected)) return ItemStack.EMPTY;
        return handler.extractItem(slot, Math.min(visible.getCount(), expected.getCount()), false);
    }

    private void refundToStorage(ItemStack stack) {
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (!leftover.isEmpty() && player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
        }
    }

    // ── 机器状态 ──

    /** 下方方块必须带 {@code bosses_delight:cold_sources} 标签（冰 / 浮冰 / 蓝冰）。 */
    private static boolean isHeated(BlockEntity be) {
        if (be == null || be.getLevel() == null) return false;
        Level level = be.getLevel();
        BlockPos below = be.getBlockPos().below();
        if (!level.hasChunkAt(below)) return false;
        TagKey<Block> tag = BlockTags.create(COLD_SOURCES_TAG);
        return level.getBlockState(below).is(tag);
    }

    @Nullable
    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack result = resultItem();
        if (result.isEmpty()) return null;
        return new ExpectedProduction(result, expectedOutputCount());
    }

    @Override
    public OutputContract outputContract() {
        if (!supportsInputBuffer()) return OutputContract.none();
        ItemStack result = resultItem();
        if (result.isEmpty()) return OutputContract.none();
        return new OutputContract(List.of(new OutputContract.Port(outputPortId(), OUTPUT_SLOT,
                result, result.getCount(), InputBufferPlan.OutputPort.Kind.PRIMARY,
                OutputContract.Source.SLOT)));
    }

    private int expectedOutputCount() {
        ItemStack result = resultItem();
        if (result.isEmpty()) return 0;
        long count = (long) result.getCount() * Math.max(1, activeOperations);
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private boolean isExpectedOutput(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemStack expected = resultItem();
        return !expected.isEmpty() && IBatchDelegate.matchesProducedItem(stack, expected);
    }

    private boolean hasOwnedInputLeft(IItemHandler handler) {
        for (int i = 0; i < placedInputs.size(); i++) {
            ItemStack visible = handler.getStackInSlot(i);
            if (!visible.isEmpty() && ItemStack.isSameItemSameTags(visible, placedInputs.get(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean areOwnedInputsPresent(IItemHandler handler) {
        for (int i = 0; i < placedInputs.size(); i++) {
            ItemStack visible = handler.getStackInSlot(i);
            if (visible.isEmpty() || !ItemStack.isSameItemSameTags(visible, placedInputs.get(i))) {
                return false;
            }
        }
        return true;
    }

    // ── 配方助手 ──

    private ItemStack resultItem() {
        return recipe == null || myLevel == null ? ItemStack.EMPTY
                : ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
    }

    private ItemStack requiredContainer() {
        if (recipe == null) return ItemStack.EMPTY;
        RegistryAccess access = myLevel != null ? myLevel.registryAccess() : RegistryAccess.EMPTY;
        ItemStack container = BossesDelightRecipeHandler.getOutputContainer(recipe);
        if (container.isEmpty()) return ItemStack.EMPTY;
        // 容器按"每份一个"预留，原型数量保持 1。
        return container.copyWithCount(1);
    }

    /** 成品在 8 号槽结算，端口 ID 与 JEI 提示共用。 */
    private String outputPortId() {        return "bosses_delight:soul_cooking_pot:output";
    }

    private static int slotCapacity(IItemHandler handler, int slot, ItemStack prototype) {
        return Math.max(1, Math.min(handler.getSlotLimit(slot), prototype.getMaxStackSize()));
    }

    /** 与农夫乐事锅同口径的批次容量：受配置上限、各输入槽容量、输出槽容量共同约束。 */
    static int bufferedOperationCapacity(int configuredLimit,
                                         List<InputBufferContract.InputSlot> inputs,
                                         int outputPerOperation, int outputCapacity) {
        if (configuredLimit <= 0 || inputs == null || inputs.isEmpty()
                || outputPerOperation <= 0 || outputCapacity <= 0) return 0;
        int operations = Math.min(configuredLimit, outputCapacity / outputPerOperation);
        for (InputBufferContract.InputSlot input : inputs) {
            if (input == null || input.perOperation() <= 0 || input.capacity() <= 0) return 0;
            operations = Math.min(operations, input.capacity() / input.perOperation());
        }
        return Math.max(0, operations);
    }

    @Nullable
    private static InputBufferPlan.InputSlot inputByEntryId(InputBufferPlan plan, String entryId) {
        return plan.inputs().stream()
                .filter(input -> entryId.equals(input.entryId()))
                .findFirst().orElse(null);
    }

    private static ItemStack ingredientPrototype(IngredientSpec spec) {
        if (spec == null || spec.ingredient() == null) return ItemStack.EMPTY;
        return Arrays.stream(spec.ingredient().getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    // ── 配置 ──

    private static boolean soulPotInputBufferEnabled() {
        try {
            return RSIntegrationConfig.ENABLE_BD_SOUL_COOKING_POT_INPUT_BUFFER.get();
        } catch (IllegalStateException | NullPointerException ignored) {
            return false;
        }
    }

    private static int soulPotInputBufferLimit() {
        try {
            return Math.max(1, RSIntegrationConfig.BD_SOUL_COOKING_POT_INPUT_BUFFER_LIMIT.get());
        } catch (IllegalStateException | NullPointerException ignored) {
            return 1;
        }
    }

    // ── 反射 ──

    private static void probeReflection() {
        if (reflectionProbed) return;
        synchronized (SoulCookingPotBatchDelegate.class) {
            if (reflectionProbed) return;
            Class<?> type = BossesDelightReflection.soulCookingPotBEClass;
            if (type != null) {
                try {
                    inventoryField = type.getDeclaredField("inventory");
                    inventoryField.setAccessible(true);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-Batch-SoulPot] Inventory reflection probe failed", e);
                }
            }
            reflectionProbed = true;
        }
    }

    @Nullable
    private static IItemHandler getInventory(BlockEntity be) {
        probeReflection();
        if (inventoryField != null) {
            try {
                return (IItemHandler) inventoryField.get(be);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Batch-SoulPot] field access failed", e);
            }
        }
        // 灵魂锅把库存挂在物品处理器能力上，字段反射失败时退回能力查询。
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }

    // ── 计划提示 ──

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                  @Nullable ResourceLocation dim,
                                                  @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        ItemStack container = recipe == null ? ItemStack.EMPTY
                : BossesDelightRecipeHandler.containerForCraft(recipe);
        if (!container.isEmpty()) {
            warnings.add(Component.translatable("rsi.farmersdelight.container_needed",
                    container.getHoverName()));
            warnings.add(Component.translatable("rsi.bossesdelight.container_stays"));
        }
        warnings.add(Component.translatable("rsi.bossesdelight.cold_source_warning"));
        return warnings;
    }
}
