package com.huanghuang.rsintegration.mods.youkaishomecoming.cooking;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.mods.youkaishomecoming.YoukaiRegistryIds;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import com.huanghuang.rsintegration.util.Reflect;
import dev.xkmc.youkaishomecoming.content.block.food.PotFoodBlock;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Base batch delegate for Youkais Homecoming cooking pots. Subclasses: SmallPot, ShortPot, LargePot. */
public class CookingPotBatchDelegate extends AbstractBatchDelegate {

    // Indices into BOWL_PATHS / POT_PATHS / BE_CLASS_NAMES.
    protected static final int SMALL = 0;
    protected static final int SHORT = 1;
    protected static final int LARGE = 2;

    /** 原生锅的原料格数量（CookingBlockEntity 构造时以 12 个槽位创建容器）。 */
    protected static final int MAX_INGREDIENT_SLOTS = 12;
    /** 与农夫乐事锅一致的容器槽下标。 */
    protected static final int CONTAINER_SLOT = 5;
    /** 与农夫乐事锅一致的输出槽下标。 */
    protected static final int OUTPUT_SLOT = 6;

    protected final int potIndex;

    public CookingPotBatchDelegate() { this(SMALL); }
    protected CookingPotBatchDelegate(int potIndex) { this.potIndex = potIndex; }

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private String machineNamespace;
    private Recipe<?> recipe;
    private boolean craftDone;
    private int plannedOperations = 1;
    // 已按批次放进锅里的原料，失败/超时时按指纹原样回收，避免把玩家原料留成"残余物"。
    private final List<ItemStack> placedInputs = new ArrayList<>();
    private final List<ItemStack> detachedRecoveredInputs = new ArrayList<>();

    // -- reflection cache --
    private static volatile Field itemsField;          // CookingBlockEntity.items (public final)
    private static volatile Method tryAddItemMethod;   // CookingBlockEntity.tryAddItem(ItemStack, boolean)
    private static volatile Method isHeatedMethod;     // HeatableBlockEntity.isHeated(Level, BlockPos)
    private static volatile Method getInputMethod;     // PotCookingRecipe.getInput()
    private static volatile Method getResultMethod;    // PotCookingRecipe.getResult()
    private static volatile Method inProgressMethod;   // TimedRecipeBlockEntity.inProgress()
    private static volatile Method containerMethod;    // CookingBlockEntity.container()
    private static volatile Method getProcessTimeMethod; // TimedRecipe.getProcessTime()
    private static volatile Method getInventoryMethod; // CookingBlockEntity.getInventory()
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
        ResourceLocation machineId = BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock());
        this.machineNamespace = YoukaiRegistryIds.isSupportedNamespace(machineId.getNamespace())
                ? machineId.getNamespace()
                : recipeId.getNamespace();

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || !isPotCookingRecipe(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.plannedOperations = 1;
        this.placedInputs.clear();
        this.detachedRecoveredInputs.clear();
        return true;
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK
                .getKey(level.getBlockState(pos).getBlock());
        if (!YoukaiRegistryIds.isSupportedNamespace(blockId.getNamespace())) return false;
        return CookingPotCompletionPolicy.isIdleBowl(
                level.getBlockState(pos), bowlKey(blockId.getNamespace(), potIndex));
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        probeReflection();
        List<Ingredient> ingredients = getRecipeInput(recipe);
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        // PotFood recipes must have a valid serving-container contract. Never
        // silently omit bowls, which would mint free bowl-servings at collection.
        if (isPotFoodRecipe(recipe)) {
            ItemStack bowls = computeServeBowls(recipe);
            if (bowls.isEmpty()) return null;
            specs.add(new IngredientSpec(Ingredient.of(bowls), bowls.getCount()));
        }
        return specs.isEmpty() ? null : specs;
    }

    // -- 并发与批量契约 --
    // 三台锅共用同一份实现：并发声明、批量份数、输入缓冲契约都在这里统一声明。
    //
    // 注意：这三台锅的 ModType 已在 GraphConcurrencyEligibility.WORLD_CAPTURE_TYPES 里，
    // 而 AsyncCraftChain 是「资格表非空则优先」，所以下面的 concurrencyCapabilities()
    // 对它们只是契约完整性，实际并发由资格表决定，改这里不会改变并发行为。

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // 原料与容器全部由共享账本预留，成品始终落在机器自己的槽位/世界掉落，
        // 失败清理可在机器离线下独立完成，因此允许与同类型机器并行。
        return BatchConcurrencyCapabilities.machineSlotWithLocalWorldItems();
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        if (!supportsInputBuffer()) return Math.max(0, Math.min(1, remainingOperations));
        plannedOperations = Math.max(1, inputBufferPlan(remainingOperations).operations());
        return plannedOperations;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        plannedOperations = supportsInputBuffer() ? Math.max(1, executions) : 1;
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        int operations = Math.max(1, totalOperations);
        int capacity = Math.max(1, inputBufferPlan(operations).operations());
        // prepared 阶段机器尚未加载区块，这里只能从契约里取一个上界；
        // 真正的物理容量在铺料失败时按 rollback 收回，不会造成材料损失。
        return ParallelBatchSizing.boundedEvenShare(operations, operations, capacity);
    }

    @Override
    public int flatBatchOperationLimit(int configuredLimit) {
        if (!supportsInputBuffer()) return Math.max(1, configuredLimit);
        return Math.max(Math.max(1, configuredLimit), localOperationLimit());
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return supportsInputBuffer();
    }

    /** 本机按配方耗时与槽位容量折算出的最大并行份数。 */
    private int localOperationLimit() {
        if (recipe == null) return CookingPotBatchConfig.inputBufferLimit();
        BlockEntity be = myLevel == null || myPos == null || !myLevel.hasChunkAt(myPos)
                ? null : myLevel.getBlockEntity(myPos);
        IItemHandler handler = be == null ? null : getInventory(be);
        int slotCapacity = handler == null ? Integer.MAX_VALUE
                : CookingPotBatchConfig.effectiveSlotCapacity(
                        handler.getSlotLimit(0), maxIngredientStackSize());
        ItemStack output = getRecipeResult(recipe);
        int outputCapacity = handler == null ? Integer.MAX_VALUE
                : CookingPotBatchConfig.effectiveSlotCapacity(
                        handler.getSlotLimit(0), Math.max(1, output.getMaxStackSize()));
        return CookingPotBatchConfig.bufferedOperationCapacity(potIndex,
                processTime(recipe), CookingPotBatchConfig.inputBufferLimit(),
                slotCapacity, Math.max(1, output.getCount()), outputCapacity);
    }

    @Override
    public boolean supportsInputBuffer() {
        // YHK 原生 finishRecipe() 会清空整个输入容器，且 CookingItemContainer
        // 每槽上限为 1，无法安全地在同一台锅里预载多份。保留配置项以兼容旧配置，
        // 但普通锅统一走原生 tryAddItem 的单次路径，避免材料丢失或启动失败。
        return false;
    }

    private static boolean hasCraftingRemainder(IngredientSpec spec) {
        if (spec == null || spec.ingredient() == null) return true;
        ItemStack[] candidates = spec.ingredient().getItems();
        if (candidates.length == 0) return true;
        for (ItemStack candidate : candidates) {
            if (candidate == null || candidate.isEmpty() || candidate.hasCraftingRemainingItem()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public InputBufferContract inputBufferContract() {
        if (!supportsInputBuffer()) return InputBufferContract.none();
        BlockEntity be = myLevel.getBlockEntity(myPos);
        IItemHandler handler = be == null ? null : be.getCapability(
                ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
        if (handler == null || handler.getSlots() <= 0) return InputBufferContract.none();

        List<IngredientSpec> specs = getRequiredMaterials();
        boolean potFood = isPotFoodRecipe(recipe);
        ItemStack bowls = potFood ? computeServeBowls(recipe) : ItemStack.EMPTY;
        int ingredientCount = specs.size() - (bowls.isEmpty() ? 0 : 1);

        List<InputBufferContract.InputSlot> inputs = new ArrayList<>(specs.size());
        for (int i = 0; i < ingredientCount; i++) {
            IngredientSpec spec = specs.get(i);
            ItemStack prototype = ingredientPrototype(spec);
            if (prototype.isEmpty()) return InputBufferContract.none();
            inputs.add(new InputBufferContract.InputSlot(
                    "yhk:material:" + i, i, prototype, Math.max(1, spec.count()), false,
                    CookingPotBatchConfig.effectiveSlotCapacity(
                            handler.getSlotLimit(i), prototype.getMaxStackSize())));
        }
        if (!bowls.isEmpty()) {
            ItemStack prototype = bowls.copyWithCount(1);
            inputs.add(new InputBufferContract.InputSlot(
                    "yhk:container", CONTAINER_SLOT, prototype,
                    Math.max(1, bowls.getCount()), false,
                    CookingPotBatchConfig.effectiveSlotCapacity(
                            handler.getSlotLimit(CONTAINER_SLOT), prototype.getMaxStackSize())));
        }

        ItemStack output = getRecipeResult(recipe);
        if (output.isEmpty()) return InputBufferContract.none();
        int operations = localOperationLimit();
        if (operations <= 0) return InputBufferContract.none();
        return new InputBufferContract(operations, inputs,
                List.of(new OutputContract.Port(outputPortId(), OUTPUT_SLOT,
                        output, Math.max(1, output.getCount()),
                        InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.SLOT)));
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

        ItemStack bowls = isPotFoodRecipe(recipe) ? computeServeBowls(recipe) : ItemStack.EMPTY;
        boolean hasContainer = !bowls.isEmpty();
        List<ItemStack> ordered = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            String entryId = hasContainer && i == specs.size() - 1
                    ? "yhk:container" : "yhk:material:" + i;
            InputBufferPlan.InputSlot input = inputByEntryId(plan, entryId);
            long required = (long) Math.max(1, spec.count()) * plan.operations();
            if (input == null || input.reusable() || required > Integer.MAX_VALUE
                    || input.stack().getCount() != (int) required
                    || !spec.ingredient().test(input.stack())) {
                return false;
            }
            ordered.add(input.stack().copy());
        }
        InputBufferPlan expected = inputBufferPlan(plan.operations());
        if (!expected.enabled() || expected.operations() != plan.operations()) return false;
        plannedOperations = plan.operations();
        return startPreparedOperation(player, ordered, sharedLedger);
    }

    @Override
    public OutputContract outputContract() {
        if (!supportsInputBuffer()) return OutputContract.none();
        ItemStack output = getRecipeResult(recipe);
        if (output.isEmpty()) return OutputContract.none();
        return new OutputContract(List.of(new OutputContract.Port(outputPortId(), OUTPUT_SLOT,
                output, Math.max(1, output.getCount()),
                InputBufferPlan.OutputPort.Kind.PRIMARY, OutputContract.Source.SLOT)));
    }

    /** 批量结算用的输出端口标识，各锅子类可覆盖以区分来源。 */
    protected String outputPortId() {
        return "youkaishomecoming:cooking_pot:output";
    }

    private static ItemStack ingredientPrototype(IngredientSpec spec) {
        if (spec == null || spec.ingredient() == null) return ItemStack.EMPTY;
        for (ItemStack candidate : spec.ingredient().getItems()) {
            if (candidate != null && !candidate.isEmpty()) return candidate.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    private int maxIngredientStackSize() {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return 64;
        ItemStack prototype = ingredientPrototype(specs.get(0));
        return prototype.isEmpty() ? 64 : prototype.getMaxStackSize();
    }

    @Nullable
    private static InputBufferPlan.InputSlot inputByEntryId(InputBufferPlan plan, String entryId) {
        return plan.inputs().stream()
                .filter(input -> entryId.equals(input.entryId()))
                .findFirst().orElse(null);
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            if (storageEndpoint() == null) setStorageEndpoint(CraftPacketUtils.resolveEndpointForCraft(player, myDim, myPos).orElse(null));
            if (storageEndpoint() == null && !hasStorageAccess()) return false;
            ledger.setStorageEndpoint(storageEndpoint());

            for (IngredientSpec spec : specs) {
                if (spec.isEmpty()) continue;
                ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                        player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
                if (reserved.isEmpty()) {
                    return false;
                }
                materials.add(reserved.copy());
            }

            if (!ledger.commitToEndpoint(storageEndpoint(), player)) return false;

            this.usingSharedLedger = false;
            if (!startPreparedOperation(player, materials, ledger)) {
                for (ItemStack mat : materials) {
                    if (!mat.isEmpty())
                        insertIntoStorage(player, mat.copy(), false);
                }
                return false;
            }
            return true;
        }
    }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        // usingSharedLedger already set by caller — don't overwrite
        this.craftDone = false;

        forceChunkLoad(true);
        if (!myLevel.hasChunkAt(myPos)) return false;

        BlockEntity be = findCookingBE();
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-CookPot] CookingBE not found near {}", myPos);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        // Existing ingredients belong to the machine, not this operation's
        // reservation ledger. Return them to RS before placing fresh inputs so
        // a stale/partially completed pot cannot make the scheduler retry.
        if (!hasStorageAccess() || !drainExistingContentsToNetwork(be)) return false;

        if (!isHeated(be)) {
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.no_heat"));
            forceChunkLoad(false);
            return false;
        }

        // Ensure this pot can cook this recipe -- matchContainer() checks
        // that result.getCraftingRemainingItem() matches be.container()
        if (!matchesContainer(be, recipe)) {
            RSIntegrationMod.LOGGER.debug("[RSI-CookPot] Recipe {} does not match this pot type (index {})",
                    recipe.getId(), potIndex);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.wrong_pot"));
            forceChunkLoad(false);
            return false;
        }

        // setLastPlayer is needed for recipe matching in tryAddItem
        try {
            Method setPlayer = be.getClass().getMethod("setLastPlayer",
                    Player.class);
            setPlayer.invoke(be, player);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] setLastPlayer reflection failed", e);
        }

        ItemStack serveBowls = computeServeBowls(recipe);
        if (isPotFoodRecipe(recipe)) {
            if (serveBowls.isEmpty() || materials.isEmpty()) {
                forceChunkLoad(false);
                return false;
            }
            ItemStack suppliedBowls = materials.get(materials.size() - 1);
            long requiredBowls = (long) serveBowls.getCount() * plannedOperations;
            if (requiredBowls > Integer.MAX_VALUE
                    || !ItemStack.isSameItemSameTags(serveBowls, suppliedBowls)
                    || suppliedBowls.getCount() != (int) requiredBowls) {
                forceChunkLoad(false);
                return false;
            }
            // 批量模式下原生容器槽必须为空，铺料失败时才能干净回滚。
            if (plannedOperations > 1 && !isContainerSlotEmpty(be)) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookPot] Container slot occupied at {}", myPos);
                forceChunkLoad(false);
                return false;
            }
        }

        // 每份原料各占一个固定槽位，槽内数量 = 单份数量 × 份数；
        // 原生 tick 每完成一份就整槽扣掉一份，多份因此在同一槽内排队。
        // 槽位从 startIngredientIndex() 起排：盛装容器固定占 CONTAINER_SLOT，
        // 原料槽必须让开它，否则某份原料会被塞进容器槽导致配方不匹配。
        List<ItemStack> placed = new ArrayList<>();
        int slot = startIngredientIndex();
        for (int i = 0; i < materials.size(); i++) {
            ItemStack mat = materials.get(i);
            if (mat.isEmpty()) continue;
            // 只有最后一个、已校验过的材料是盛装容器。
            if (!serveBowls.isEmpty() && i == materials.size() - 1) continue;
            int targetSlot = batchSlot(slot);
            if (!tryPlaceBatchInput(be, targetSlot, mat)) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookPot] Failed to place {} x{} into slot {}",
                        mat.getHoverName().getString(), mat.getCount(), targetSlot);
                detachRecoveredInputs(be, placed);
                forceChunkLoad(false);
                return false;
            }
            placed.add(mat.copy());
            placedInputs.add(mat.copy());
            slot++;
        }

        be.setChanged();
        markCraftStarted();
        return true;
    }

    /** 原料槽在"原料优先、容器槽保留"前提下的实际下标。 */
    private static int batchSlot(int placementIndex) {
        return placementIndex < CONTAINER_SLOT ? placementIndex : placementIndex + 1;
    }

    /**
     * 原料起始槽位。
     * <p>
     * 普通锅与幻想乡三锅都从 0 号槽开始铺；但带盛装容器的配方必须把
     * {@link #CONTAINER_SLOT} 让给容器，因此原料从 1 号槽起排。子类可覆盖。
     */
    protected int startIngredientIndex() {
        return 0;
    }

    /**
     * 铺满批次所需的原料槽数上限（不含容器槽）。子类按自己的槽位布局覆盖。
     */
    protected int ingredientSlotCapacity() {
        return MAX_INGREDIENT_SLOTS;
    }

    /**
     * 往固定槽位铺料：先用原生 {@code tryAddItem} 校验配方匹配，再按批次数量写入。
     * <p>
     * {@code tryAddItem} 只接受单个物品（内部强制 copyWithCount(1)），因此
     * 校验通过后由委托自己按整批数量覆盖槽位——原生 tick 每完成一份扣一个，
     * 这正是"一次铺满、逐份产出"所需的行为。
     */
    private boolean tryPlaceBatchInput(BlockEntity be, int slot, ItemStack stack) {
        if (stack.isEmpty()) return true;
        // tryAddItem 会自行选择第一个合法空槽并写入一件物品；先调用它再
        // 要求目标槽为空会把刚写入的槽误判为失败。当前锅具只启用单次路径，
        // 因此这里直接返回原生调用结果，slot 参数仅保留给旧批量接口。
        return tryAddItem(be, stack.copyWithCount(1));
    }

    private boolean isContainerSlotEmpty(BlockEntity be) {
        IItemHandler handler = getInventory(be);
        return handler == null || handler.getStackInSlot(CONTAINER_SLOT).isEmpty();
    }

    /** 铺料中途失败时，把本次已放入的原料摘到"待回收"列表，绝不留成锅里的残渣。 */
    private void detachRecoveredInputs(BlockEntity be, List<ItemStack> placed) {
        IItemHandler handler = getInventory(be);
        if (handler != null) {
            for (ItemStack expected : placed) {
                if (expected.isEmpty()) continue;
                for (int slot = 0; slot < handler.getSlots(); slot++) {
                    ItemStack visible = handler.getStackInSlot(slot);
                    if (visible.isEmpty() || !ItemStack.isSameItemSameTags(visible, expected)) continue;
                    ItemStack removed = handler.extractItem(slot, visible.getCount(), false);
                    if (!removed.isEmpty()) detachedRecoveredInputs.add(removed.copy());
                    break;
                }
            }
        }
        placedInputs.clear();
        if (be != null) be.setChanged();
    }

    private static ItemStack getInventoryStack(BlockEntity be, int slot) {
        IItemHandler handler = getInventory(be);
        if (handler == null || slot < 0 || slot >= handler.getSlots()) return ItemStack.EMPTY;
        return handler.getStackInSlot(slot);
    }

    @Nonnull
    @Override
    protected CraftObservation observeMissingMachineCraft(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        var state = level.getBlockState(pos);
        ItemStack expected = getRecipeResult(recipe);
        if (CookingPotCompletionPolicy.isExpectedResultBlock(state, expected)) {
            return doneObservation();
        }
        return failObservation("machine block entity missing");
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        BlockPos pos = be.getBlockPos();

        // Case 1: BE is gone -- finishRecipe() replaced the pot with a BlockItem
        // result (e.g. soup block placed at the pot's own position).
        if (!isCookingBE(be)) {
            // If the block here is not air and not a cooking pot, it's the result.
            var state = level.getBlockState(pos);
            if (!state.isAir()) {
                String key = BuiltInRegistries.BLOCK
                        .getKey(state.getBlock()).toString();
                // Make sure it's not one of the bowl/pot blocks (some pots
                // auto-convert back to bowl after cooking).
                boolean isPot = key.contains("cooking_") || key.contains("iron_pot")
                        || key.contains("stockpot");
                if (!isPot) return true; // Result block replaced the pot
            }
            return false;
        }

        // Case 2: Container is empty -- finishRecipe() cleared items and
        // dropped/spawned the result.
        Container container = getItemsContainer(be);
        if (container != null) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                if (!container.getItem(i).isEmpty()) return false;
            }
            return true;
        }

        // Fallback: use TimedRecipeBlockEntity progress fields
        int progress = Reflect.<Integer>getField(be, "recipeProgress").orElse(-1);
        int total = Reflect.<Integer>getField(be, "totalTime").orElse(-1);
        return total > 0 && progress >= total;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        craftDone = true;

        ItemStack expected = getRecipeResult(recipe);
        BlockPos above = myPos.above();

        // Case 0: Soup-pot result (PotFoodBlock). YHK's finishRecipe places a
        // pot_of_X block at the pot position; the player is meant to scoop
        // servings out with bowls (each serving consumes 1 bowl), NOT to pick
        // up the whole pot. Serve it into bowls here instead of collecting the
        // block item.
        ItemStack served = tryServePotFood(player);
        if (served != null) return served;

        // Case 1: Result block replaced the pot (BlockItem result).
        // finishRecipe() calls level.setBlock(getBlockPos(), state) for blocks.
        var stateAtPot = myLevel.getBlockState(myPos);
        if (!stateAtPot.isAir()) {
            // PotFoodBlock is handled transactionally above. This guard prevents
            // future serving regressions from returning the entire pot as an item.
            if (stateAtPot.getBlock() instanceof PotFoodBlock) return ItemStack.EMPTY;
            String key = BuiltInRegistries.BLOCK
                    .getKey(stateAtPot.getBlock()).toString();
            boolean isPot = key.contains("cooking_") || key.contains("iron_pot")
                    || key.contains("stockpot");
            if (!isPot) {
                var blockItem = stateAtPot.getBlock().asItem();
                if (blockItem != Items.AIR) {
                    myLevel.removeBlock(myPos, false);
                    return new ItemStack(blockItem);
                }
            }
        }

        // Case 2: Non-block result dropped as entity at pos.above().
        var entities = myLevel.getEntitiesOfClass(
                ItemEntity.class,
                new AABB(above).inflate(0.5));
        for (var itemEntity : entities) {
            if (!itemEntity.isAlive()) continue;
            ItemStack s = itemEntity.getItem();
            if (!s.isEmpty() && (expected.isEmpty() || ItemStack.isSameItem(s, expected))) {
                ItemStack result = s.copy();
                itemEntity.discard();
                return result;
            }
        }

        // Case 3: Block placed above the pot (edge case).
        var stateAbove = myLevel.getBlockState(above);
        if (!stateAbove.isAir()) {
            var blockItem = stateAbove.getBlock().asItem();
            if (blockItem != Items.AIR) {
                myLevel.removeBlock(above, false);
                return new ItemStack(blockItem);
            }
        }

        // Last resort: do NOT fabricate expected.copy() — if the real result
        // escaped to a magnet it is already with the player, so minting a copy
        // duplicates it. Return EMPTY; getExpectedOutput() is non-null so the
        // chain runs abortWithoutRefund (no dupe, no unfair refund). A genuine
        // vanish becomes item loss — the accepted trade-off (loss >> dupe).
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack getExpectedOutput() {
        // Non-null enables the chain's missing-output detection (see collectResult)
        // and lets CraftOutputInterceptor match by item type. Soup-pot recipes
        // deliver served food (tryServePotFood returns non-empty before this
        // matters), so the block-item expected value only gates the escape path.
        if (recipe == null) return null;
        ItemStack out = getRecipeResult(recipe);
        return out.isEmpty() ? null : out;
    }

    @Override
    public boolean canCollectResultWithoutWorldCapture() {
        return true;
    }

    /**
     * Serve a PotFoodBlock and atomically restore the matching empty pot.
     * Returns null only when the block is definitely not a PotFoodBlock. EMPTY
     * means it is a PotFoodBlock but its serving contract could not be proven;
     * callers must fail closed and leave the world state untouched.
     */
    @Nullable
    private ItemStack tryServePotFood(ServerPlayer player) {
        var state = myLevel.getBlockState(myPos);
        if (!(state.getBlock() instanceof PotFoodBlock potFood)) return null;

        ItemStack servings = potFood.asBowls();
        var emptyPot = emptyPotState(potFood, state);
        if (servings.isEmpty() || emptyPot == null) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-CookPot] Cannot safely serve PotFoodBlock at {}; preserving it in-world", myPos);
            return ItemStack.EMPTY;
        }
        if (!myLevel.setBlock(myPos, emptyPot, 3)) {
            RSIntegrationMod.LOGGER.error(
                    "[RSI-CookPot] Failed to restore empty pot at {}; preserving output as failed", myPos);
            return ItemStack.EMPTY;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-CookPot] Served pot at {}: {} x{}; restored {}",
                myPos, servings.getHoverName().getString(), servings.getCount(),
                bowlKey(machineNamespace, potIndex));
        return servings.copy();
    }

    @Nullable
    private BlockState emptyPotState(
            PotFoodBlock potFood, BlockState foodState) {
        ItemStack potItem = new ItemStack(potFood.asItem());
        if (!potItem.hasCraftingRemainingItem()) return null;
        ItemStack remainder = potItem.getCraftingRemainingItem();
        if (!(remainder.getItem() instanceof BlockItem blockItem)) return null;
        ResourceLocation key = BuiltInRegistries.BLOCK
                .getKey(blockItem.getBlock());
        if (!machineNamespace.equals(key.getNamespace())
                || !BOWL_PATHS[potIndex].equals(key.getPath())) return null;

        var emptyState = blockItem.getBlock().defaultBlockState();
        var facing = BlockStateProperties.FACING;
        if (foodState.hasProperty(facing) && emptyState.hasProperty(facing)) {
            emptyState = emptyState.setValue(facing, foodState.getValue(facing));
        }
        return emptyState;
    }

    private static boolean isPotFoodRecipe(Recipe<?> recipe) {
        ItemStack result = getRecipeResult(recipe);
        return !result.isEmpty()
                && result.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof PotFoodBlock;
    }

    /** Compute one serving container per food returned by PotFoodBlock.asBowls(). */
    private static ItemStack computeServeBowls(Recipe<?> recipe) {
        ItemStack result = getRecipeResult(recipe);
        if (result.isEmpty() || !(result.getItem() instanceof BlockItem blockItem)
                || !(blockItem.getBlock() instanceof PotFoodBlock potFood)) {
            return ItemStack.EMPTY;
        }
        ItemStack servings = potFood.asBowls();
        if (servings.isEmpty()) return ItemStack.EMPTY;
        ItemStack foodUnit = servings.copyWithCount(1);
        if (!foodUnit.hasCraftingRemainingItem()) return ItemStack.EMPTY;
        ItemStack container = foodUnit.getCraftingRemainingItem();
        if (container.isEmpty()) return ItemStack.EMPTY;
        return container.copyWithCount(servings.getCount());
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        this.player = player == null ? this.player : player;
        recordFailureRecoveredInputs(clearAndRefund());
        forceChunkLoad(false);
        craftDone = false;
        setStorageEndpoint(null);
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        // 不做清空：原生的 finishRecipe() 已逐份消耗食材，collectResult() 也已取走成品，
        // 锅里的盛装容器与恢复出来的空锅要留在世界里给玩家复用。
        //
        // 批量路径下若还有"铺了但没被吃掉"的余料，也不要在这里退：
        // 成功收尾时共享账本已处于 committed，任何额外退款都会重复结算；
        // 这些余料会在下一次操作开始时由 drainExistingContentsToNetwork() 归还到存储，
        // 玩家也可以直接手动取回。
        forceChunkLoad(false);
        craftDone = false;
        setStorageEndpoint(null);
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // -- plan helpers --

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {}

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.youkaishomecoming.cookpot_heat_warning"));
        // Soup-pot recipes are served into bowls; surface the bowl cost so the
        // player knows N bowls will be consumed (auto-crafted if missing).
        ItemStack bowls = computeServeBowls(recipe);
        if (!bowls.isEmpty()) {
            warnings.add(Component.translatable("rsi.youkaishomecoming.cookpot_bowl_warning",
                    bowls.getCount(), bowls.getHoverName()));
        }
        return warnings;
    }

    // -- BE discovery --

    // YHK bowl blocks are IronBowlBlock (no BE).  They must be converted to the
    // corresponding cooking block before a CookingBlockEntity exists.
    // Bowl -> Pot mappings (from IronBowlBlock.use):
    //   small_iron_pot     -> cooking_small_iron_pot  (SmallCookingPotBlockEntity)
    //   short_iron_pot     -> cooking_short_iron_pot  (MidCookingPotBlockEntity)
    //   stockpot           -> cooking_stockpot        (LargeCookingPotBlockEntity)
    private static final String[] BOWL_PATHS = {
        "small_iron_pot", "short_iron_pot", "stockpot" };
    private static final String[] POT_PATHS = {
        "cooking_small_iron_pot", "cooking_short_iron_pot", "cooking_stockpot" };

    static String bowlKey(String namespace, int potIndex) {
        return YoukaiRegistryIds.stringId(namespace, BOWL_PATHS[potIndex]);
    }

    static ResourceLocation cookingKey(String namespace, int potIndex) {
        return YoukaiRegistryIds.id(namespace, POT_PATHS[potIndex]);
    }

    private BlockEntity findCookingBE() {
        if (!myLevel.hasChunkAt(myPos)) return null;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be != null && isCookingBE(be)) return be;

        // Fallback to level-level lookup.
        be = myLevel.getBlockEntity(myPos);
        if (be != null && isCookingBE(be)) return be;

        // If we're looking at an IronBowlBlock (empty bowl, no BE), replace it
        // with the cooking variant to create the CookingBlockEntity.
        // Only convert the bowl matching this delegate's pot type.
        var state = myLevel.getBlockState(myPos);
        var block = state.getBlock();
        if (!(block instanceof EntityBlock)) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK
                    .getKey(block);
            if (machineNamespace.equals(blockId.getNamespace())
                    && BOWL_PATHS[potIndex].equals(blockId.getPath())) {
                var potBlock = BuiltInRegistries.BLOCK
                        .get(cookingKey(machineNamespace, potIndex));
                if (potBlock != null) {
                    var cookingState = potBlock.defaultBlockState();
                    if (state.hasProperty(BlockStateProperties.FACING)) {
                        cookingState = cookingState.setValue(
                                BlockStateProperties.FACING,
                                state.getValue(BlockStateProperties.FACING));
                    }
                    myLevel.setBlock(myPos, cookingState, 3);
                    RSIntegrationMod.LOGGER.info("[RSI-CookPot] Activated bowl -> {} at {}",
                            cookingKey(machineNamespace, potIndex), myPos);
                    be = myLevel.getBlockEntity(myPos);
                    if (be != null && isCookingBE(be)) return be;
                }
            }
        }

        RSIntegrationMod.LOGGER.warn("[RSI-CookPot] CookingBE not found at {}. "
                        + "Block={} class={} isEntityBlock={} chunkLoaded={}",
                myPos,
                block.getDescriptionId(),
                block.getClass().getName(),
                block instanceof EntityBlock,
                myLevel.isLoaded(myPos));
        return null;
    }

    // -- reflection (one-time probe) --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.cookingBEClass == null || YHKReflection.potCookingRecipeClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-CookPot] YHKReflection probe not ready");
            return;
        }
        try { itemsField = YHKReflection.cookingBEClass.getField("items"); }
        catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-CookPot] items field unavailable", e); }
        try { tryAddItemMethod = YHKReflection.cookingBEClass.getMethod(
                "tryAddItem", ItemStack.class, boolean.class); }
        catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-CookPot] tryAddItem unavailable", e); }
        try { isHeatedMethod = YHKReflection.cookingBEClass.getMethod(
                "isHeated", Level.class, BlockPos.class); }
        catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-CookPot] isHeated unavailable", e); }
        try { containerMethod = YHKReflection.cookingBEClass.getMethod("container"); }
        catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-CookPot] container unavailable", e); }
        getProcessTimeMethod = findMethodInHierarchy(
                YHKReflection.potCookingRecipeClass, "getProcessTime");
        getInventoryMethod = findMethodInHierarchy(YHKReflection.cookingBEClass, "getInventory");
        if (getInventoryMethod != null) getInventoryMethod.setAccessible(true);
        inProgressMethod = findMethodInHierarchy(
                YHKReflection.cookingBEClass.getSuperclass(), "inProgress");
        try { getInputMethod = YHKReflection.potCookingRecipeClass.getMethod("getInput"); }
        catch (Exception e) { getInputMethod = null; }
        try { getResultMethod = YHKReflection.potCookingRecipeClass.getMethod("getResult"); }
        catch (Exception e) { getResultMethod = null; }
    }

    private static Method findMethodInHierarchy(Class<?> clazz, String name, Class<?>... paramTypes) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredMethod(name, paramTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static boolean isCookingBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.cookingBEClass != null && YHKReflection.cookingBEClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.cookingBEClass != null && YHKReflection.cookingBEClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static boolean isPotCookingRecipe(Recipe<?> recipe) {
        probeReflection();
        if (YHKReflection.potCookingRecipeClass != null && YHKReflection.potCookingRecipeClass.isAssignableFrom(recipe.getClass())) return true;
        Class<?> clazz = recipe.getClass();
        while (clazz != null) {
            if (YHKReflection.potCookingRecipeClass != null && YHKReflection.potCookingRecipeClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    // -- operation helpers --

    private static Container getItemsContainer(BlockEntity be) {
        probeReflection();
        if (itemsField != null) {
            try {
                Object val = itemsField.get(be);
                if (val instanceof Container c) return c;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] Items container reflection failed", e);
            }
        }
        return null;
    }

    private static boolean tryAddItem(BlockEntity be, ItemStack stack) {
        if (tryAddItemMethod != null) {
            try {
                return (boolean) tryAddItemMethod.invoke(be, stack, false);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] tryAddItem reflection failed", e);
            }
        }
        return false;
    }

    private static boolean isHeated(BlockEntity be) {
        if (isHeatedMethod == null) return true;
        try {
            return (boolean) isHeatedMethod.invoke(be, be.getLevel(), be.getBlockPos());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CookPot] isHeated invoke failed", e);
        }
        return true;
    }

    /** Check that the recipe result's {@code getCraftingRemainingItem()} matches
     *  the BE's {@code container()} item.  This ensures small-pot recipes don't
     *  get routed to stockpots and vice versa. */
    private static boolean matchesContainer(BlockEntity be, Recipe<?> recipe) {
        try {
            if (containerMethod == null) return true;
            Item container = (Item) containerMethod.invoke(be);
            ItemStack result = getRecipeResult(recipe);
            if (!result.isEmpty() && result.hasCraftingRemainingItem()) {
                return result.getCraftingRemainingItem().is(container);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-CookPot] matchContainer failed", e);
        }
        return true; // can't determine -- allow
    }

    @SuppressWarnings("unchecked")
    private static List<Ingredient> getRecipeInput(Recipe<?> recipe) {
        if (getInputMethod != null) {
            try {
                return (List<Ingredient>) getInputMethod.invoke(recipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] getRecipeInput reflection failed", e);
            }
        }
        Field inputField = findFieldInHierarchy(recipe.getClass(), "input");
        if (inputField != null) {
            try {
                inputField.setAccessible(true);
                Object value = inputField.get(recipe);
                if (value instanceof List<?> list) {
                    return list.stream().filter(Ingredient.class::isInstance)
                            .map(Ingredient.class::cast).toList();
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] input field reflection failed", e);
            }
        }
        return List.of();
    }

    private static ItemStack getRecipeResult(Recipe<?> recipe) {
        if (getResultMethod != null) {
            try {
                return (ItemStack) getResultMethod.invoke(recipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-CookingPot] getRecipeResult reflection failed", e);
            }
        }
        return ItemStack.EMPTY;
    }

    /** 配方耗时（tick）；读取失败时返回 0，由调用方回退到该锅的基准耗时。 */
    private static int processTime(Recipe<?> recipe) {
        if (getProcessTimeMethod != null) {
            try {
                Object value = getProcessTimeMethod.invoke(recipe);
                if (value instanceof Integer ticks && ticks > 0) return ticks;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-CookingPot] processTime reflection failed", e);
            }
        }
        return 0;
    }

    /** 原生物品能力包装（固定 8 槽：7 个食材槽 + 1 个容器槽）。 */
    @Nullable
    private static IItemHandler getInventory(BlockEntity be) {
        probeReflection();
        if (getInventoryMethod != null) {
            try {
                Object value = getInventoryMethod.invoke(be);
                if (value instanceof IItemHandler handler) return handler;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-CookPot] getInventory reflection failed", e);
            }
        }
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
    }

    // -- cleanup --

    /**
     * 清空锅体并回收其中的物品。
     * <p>
     * 与旧实现（把整锅物品塞进 recovered 列表）不同，这里把槽内残留直接退回
     * 存储或玩家手中：失败清理时丢在槽里的是<b>玩家自己的原料</b>，不是本次
     * 操作的预留材料。按"退款堆叠"记录会既误判
     * {@code physicalFailureCleanupCompleted}，又可能让调用方把同一份材料退两遍。
     *
     * @return 确实没能还给玩家的堆叠（离线且存储拒收），供调用方决定是否记录；
     *         正常退还的物品不会出现在返回值里，避免与共享账本的退款重复结算。
     */
    private List<ItemStack> clearAndRefund() {
        List<ItemStack> stranded = new ArrayList<>();
        // 铺料中途摘回的原料从未进入锅体，这里同样要真正还给玩家，不能只记一笔。
        for (ItemStack detached : new ArrayList<>(detachedRecoveredInputs)) {
            returnOrStrand(detached, stranded);
        }
        detachedRecoveredInputs.clear();
        // 正常完成路径不会调用 clearMachineState/onBatchFailed，这里清空即可：
        // 残留快照若跨操作留存，下一次失败会重复上报上一次的材料。
        placedInputs.clear();
        plannedOperations = 1;
        if (myLevel == null || myPos == null) return stranded;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isCookingBE(be)) return stranded;

        Container container = getItemsContainer(be);
        if (container != null) {
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack s = container.getItem(i);
                if (s.isEmpty()) continue;
                container.setItem(i, ItemStack.EMPTY);
                returnOrStrand(s, stranded);
            }
            be.setChanged();
            return stranded;
        }

        // Fallback: capability
        IItemHandler handler = be.getCapability(
                ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        if (handler != null) {
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack s = handler.extractItem(i, 64, false);
                if (s.isEmpty()) continue;
                returnOrStrand(s, stranded);
            }
            be.setChanged();
        }
        return stranded;
    }

    /**
     * 把槽内残留退回来源：共享账本材料走 {@link #refund}（由账本按玩家结算），
     * 其余先塞进存储、塞不下才转交玩家，最后仍无处安放的才并入 {@code stranded}。
     */
    private void returnOrStrand(ItemStack stack, List<ItemStack> stranded) {
        if (stack.isEmpty()) return;
        ItemStack leftover = insertIntoStorage(player, stack.copy(), false);
        if (leftover.isEmpty()) return;
        if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, leftover.copy());
            return;
        }
        stranded.add(leftover.copy());
    }

    /** Drain stale pot contents before a new operation owns the machine. */
    private boolean drainExistingContentsToNetwork(BlockEntity be) {
        Container container = getItemsContainer(be);
        if (container == null) {
            IItemHandler handler = be.getCapability(
                    ForgeCapabilities.ITEM_HANDLER)
                    .resolve().orElse(null);
            if (handler == null) return true;
            boolean drained = true;
            for (int i = 0; i < handler.getSlots(); i++) {
                ItemStack existing = handler.getStackInSlot(i);
                if (existing.isEmpty()) continue;
                ItemStack removed = handler.extractItem(i, existing.getCount(), false);
                if (removed.isEmpty()) {
                    drained = false;
                    continue;
                }
                ItemStack leftover = insertIntoStorage(player, removed.copy(), false);
                if (!leftover.isEmpty()) {
                    if (player != null) ItemHandlerHelper.giveItemToPlayer(player, leftover);
                    RSIntegrationMod.LOGGER.warn(
                            "[RSI-CookPot] RS rejected {} stale item(s) from {}",
                            leftover.getCount(), myPos);
                }
                if (!handler.getStackInSlot(i).isEmpty()) drained = false;
            }
            be.setChanged();
            return drained;
        }
        boolean drained = true;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack existing = container.getItem(i);
            if (existing.isEmpty()) continue;
            container.setItem(i, ItemStack.EMPTY);
            ItemStack leftover = insertIntoStorage(player, existing.copy(), false);
            if (!leftover.isEmpty()) {
                if (player != null) ItemHandlerHelper.giveItemToPlayer(player, leftover);
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-CookPot] RS rejected {} stale item(s) from {}",
                        leftover.getCount(), myPos);
            }
        }
        be.setChanged();
        return drained;
    }

    private void refund(ItemStack stack) {
        if (storageEndpoint() != null || storageEndpoint() != null) {
            ItemStack leftover = insertIntoStorage(player, stack.copy(), false);
            if (!leftover.isEmpty() && player != null)
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }
}
