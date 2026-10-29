package com.huanghuang.rsintegration.mods.youkaishomecoming.steamer;

import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.mods.youkaishomecoming.YoukaiRegistryIds;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Batch delegate for Youkais Homecoming Steamer. */
public final class SteamerBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private BlockPos potBasePos;
    private String machineNamespace;
    private AbstractCookingRecipe recipe;
    private boolean craftDone;
    /** 本次操作铺进蒸笼的份数；1 表示走原生单份路径。 */
    private int plannedOperations = 1;
    /** 已按批次铺进蒸笼格的原料，失败清理时按指纹摘回，避免留成"残渣"。 */
    private final List<ItemStack> placedInputs = new ArrayList<>();

    // Reflection cache
    private static volatile Field racksField;
    private static volatile Method tryAddItemMethod;
    private static volatile Method tryTakeItemMethod;
    private static volatile Method mayExtractMethod;
    private static volatile Field stackField;
    private static volatile Method rackSetStackMethod;
    private static volatile Method setChangedMethod;
    private static volatile Method isHeatedMethod;
    private static volatile Field waterPropertyField;
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
        ResourceLocation machineId = BuiltInRegistries.BLOCK.getKey(
                level.getBlockState(pos).getBlock());
        this.machineNamespace = YoukaiRegistryIds.isSupportedNamespace(machineId.getNamespace())
                ? machineId.getNamespace()
                : recipeId.getNamespace();

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (!(found instanceof AbstractCookingRecipe)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = (AbstractCookingRecipe) found;
        this.craftDone = false;
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        Ingredient ing = recipe.getIngredients().get(0);
        if (ing.isEmpty()) return null;
        return List.of(new IngredientSpec(ing, 1));
    }

    // -- 批量契约 --
    // 蒸锅的"多份"落在蒸笼格上：每格一份、各自累计蒸制进度，装满后整批一起等。
    // 注意：GraphConcurrencyEligibility 把蒸锅登记为 WORLD_CAPTURE_TYPES，但
    // GraphConcurrencyPolicy.intersectCapabilities 里运行时声明（下面的 machineSlot）
    // 只要不是 AMBIGUOUS/ENTITY 就会胜出，所以并发行为实际由本方法决定。
    // 蒸笼格里的成品由 canCollectResultWithoutWorldCapture() 权威收取，因此本委托不再
    // 声明 getExpectedOutput()：非空 expected 会被图派发当成「必须世界捕获」，
    // 与 machineSlot 声明冲突，节点会以 START_REJECTED（机器拒绝启动此配方）失败。

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // 食材由共享账本预留、成品留在蒸笼格里，失败清理可在机器离线下完成。
        return BatchConcurrencyCapabilities.machineSlot();
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        if (!supportsInputBuffer()) return Math.max(0, Math.min(1, remainingOperations));
        plannedOperations = Math.max(1, inputBufferPlan(remainingOperations).operations());
        return plannedOperations;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        plannedOperations = Math.max(1, executions);
    }

    @Override
    public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        int operations = Math.max(1, totalOperations);
        int capacity = Math.max(1, inputBufferPlan(operations).operations());
        // prepared 阶段蒸笼尚未加载区块，这里只能从契约里取上界；
        // 真正的格子数在铺料时再核验，放不下就整体回滚。
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

    /** 本机按蒸笼格数与单份蒸制耗时折算出的最大并行份数。 */
    private int localOperationLimit() {
        return SteamerBatchConfig.bufferedOperationCapacity(
                rackLayers(), processTime(), SteamerBatchConfig.inputBufferLimit());
    }

    @Override
    public boolean supportsInputBuffer() {
        if (!SteamerBatchConfig.inputBufferEnabled() || recipe == null
                || myLevel == null || myPos == null || !myLevel.hasChunkAt(myPos)) return false;
        IngredientSpec spec = getRequiredMaterials().get(0);
        if (spec == null || spec.isEmpty() || spec.count() != 1
                || spec.role() != DemandRole.CONSUMED
                || hasCraftingRemainder(spec)) {
            // 有材料返还（工具/容器）的配方会破坏"每格一份"的前置假设，退回逐次路径。
            return false;
        }
        if (findPotBase() == null) return false;
        // 蒸笼没装好、没盖盖子、没水没热源都不适合铺满。
        return hasRackStructure() && localOperationLimit() > 0;
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
        BlockEntity be = myLevel.getBlockEntity(potPos());
        if (be == null || !isSteamerBE(be)) return InputBufferContract.none();
        List<?> racks = getRacks(be);
        if (racks == null || racks.isEmpty()) return InputBufferContract.none();

        ItemStack prototype = ingredientPrototype();
        if (prototype.isEmpty()) return InputBufferContract.none();
        int operations = localOperationLimit();
        if (operations <= 0) return InputBufferContract.none();

        // 整锅只蒸同一种食材，因此只声明"一条物料"。
        // 执行链的预留是按物料条目逐条发放的：若为每个蒸笼格都声明一条，
        // 一次批量会要求 N 份材料、却只拿得到一份，纽带就断在这里。
        // 真正的"每格一份"由 startBufferedOperation 铺料时自行切分，契约只声明总容量。
        int slots = SteamerBatchConfig.slotCapacity(rackLayers());
        int capacity = Math.min(slots,
                SteamerBatchConfig.effectiveSlotCapacity(prototype.getMaxStackSize()));
        if (capacity <= 0) return InputBufferContract.none();
        List<InputBufferContract.InputSlot> inputs = List.of(
                new InputBufferContract.InputSlot(
                        "yhk:steamer:input", 0, prototype, 1, capacity,
                        getRequiredMaterials().get(0).consumption()));

        ItemStack output = getRecipeResult();
        if (output.isEmpty()) return InputBufferContract.none();
        // 输出端口来源必须与 outputContract() 一致：产物留在蒸笼格里，由 collectResult 读取，
        // 既不是世界掉落也不是机器槽位，两处都声明为 VIRTUAL。
        return new InputBufferContract(operations, inputs,
                List.of(new OutputContract.Port(outputPortId(), null, output,
                        Math.max(1, output.getCount()),
                        OutputContract.Kind.PRIMARY, OutputContract.Source.VIRTUAL)));
    }

    @Override
    public List<IngredientSpec> getFlatBatchRequiredMaterials(int executions) {
        // 与契约保持同一声明口径：物料条目只有一条，整批材料的份数按份数放大。
        if (executions <= 0) return null;
        List<IngredientSpec> base = getRequiredMaterials();
        if (base == null || base.isEmpty()) return null;
        Ingredient ing = base.get(0).ingredient();
        if (ing == null || ing.isEmpty()) return null;
        return List.of(new IngredientSpec(ing, executions, base.get(0).consumption()));
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
        // 全锅只蒸同一种食材，所以契约只声明一条物料的多个物理格；
        // 变量化必须同时满足"每格一份"和"格数够"。
        if (plan.inputs().size() != 1 || plan.outputs().size() != 1) return false;
        InputBufferPlan.InputSlot input = plan.inputs().get(0);
        long required = plan.operations();
        if (input.consumption().reusableWithinWorkerBatch()
                || required > Integer.MAX_VALUE
                || input.stack().getCount() != (int) required
                || !recipe.getIngredients().get(0).test(input.stack())) {
            return false;
        }
        InputBufferPlan expected = inputBufferPlan(plan.operations());
        if (!expected.enabled() || expected.operations() != plan.operations()) return false;

        plannedOperations = plan.operations();
        // 铺料时把同一格堆叠切成 N 个单份 ItemStack，按下标逐个占格。
        List<ItemStack> perSlot = new ArrayList<>(plan.operations());
        for (int i = 0; i < plan.operations(); i++) {
            perSlot.add(input.stack().copyWithCount(1));
        }
        return startPreparedOperation(player, perSlot, sharedLedger);
    }

    @Override
    public OutputContract outputContract() {
        if (!supportsInputBuffer()) return OutputContract.none();
        ItemStack output = getRecipeResult();
        if (output.isEmpty()) return OutputContract.none();
        // 蒸笼格的产物由 collectResult 读取，并非世界掉落，因此这里声明为虚拟端口。
        return new OutputContract(List.of(new OutputContract.Port(outputPortId(), null, output,
                Math.max(1, output.getCount()),
                OutputContract.Kind.PRIMARY, OutputContract.Source.VIRTUAL)));
    }

    /** 批量结算用的输出端口标识。 */
    protected String outputPortId() {
        return "youkaishomecoming:steamer:output";
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        // 蒸制配方没有副产物，产物全部由 collectResult 从蒸笼格取出。
        return true;
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
                        insertIntoStorage(player, mat, false);
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
        if (sharedLedger.storageEndpoint() != null) setStorageEndpoint(sharedLedger.storageEndpoint());
        // usingSharedLedger already set by caller — don't overwrite
        this.craftDone = false;
        // 单份路径下这里会被 upper 调用方/Fabric 默认值覆盖为 1；
        // 批量路径由 startBufferedOperation 预先置成整批份数，这里不能重置。
        if (!usingSharedLedger) plannedOperations = 1;

        if (materials.isEmpty()) return false;

        forceChunkLoad(true);
        if (!myLevel.hasChunkAt(myPos)) return false;

        // Find pot base -- the BE lives in the bottom pot block.
        // myPos might be the rack or lid position if the user bound those.
        BlockEntity be = findPotBase();
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Pot base not found at myPos={}", myPos);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        if (!isHeated(be)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Steamer] Not heated at {}", potPos());
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.no_heat"));
            forceChunkLoad(false);
            return false;
        }

        if (!hasWater() && !MachineWaterSupply.fillProperty("youkaishomecoming_steamer", 1000,
                storageEndpoint(), player, () -> setWaterProperty(myLevel.getBlockState(potPos()), true))) {
            forceChunkLoad(false);
            return false;
        }

        // Verify racks exist and are valid
        List<?> racks = getRacks(be);
        if (racks == null || racks.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] No racks at potBase={}", potBasePos);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.rack_invalid"));
            forceChunkLoad(false);
            return false;
        }

        // Verify lid is present
        if (!hasLid(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Lid not found above potBase={}", potBasePos);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.lid_missing"));
            forceChunkLoad(false);
            return false;
        }

        // Try to add each material to racks until every portion has a slot.
        // 单份路径下 materials 只有一个元素，行为与旧实现完全一致；
        // 批量路径下每个元素都是一份独立食材，各占一个蒸笼格。
        List<ItemStack> placed = new ArrayList<>(materials.size());
        boolean inserted = true;
        for (ItemStack portion : materials) {
            if (portion.isEmpty()) continue;
            if (tryAddItemToAnyRack(racks, be, portion.copyWithCount(1))) {
                placed.add(portion.copyWithCount(1));
                placedInputs.add(portion.copyWithCount(1));
                continue;
            }
            inserted = false;
            break;
        }

        if (!inserted) {
            RSIntegrationMod.LOGGER.debug("[RSI-Steamer] Racks full at {}", potBasePos);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.steamer_full"));
            // 已铺进去的部分必须摘回来，否则会留成锅里的"残渣"。
            detachRecoveredInputs(be, placed);
            forceChunkLoad(false);
            return false;
        }

        be.setChanged();
        return true;
    }

    /** 依次尝试每个蒸笼格，第一个装得下这份食材的格胜出。 */
    private boolean tryAddItemToAnyRack(List<?> racks, BlockEntity be, ItemStack portion) {
        for (Object rack : racks) {
            if (tryAddItemToRack(rack, be, portion)) return true;
        }
        return false;
    }

    /**
     * 铺料中途失败时，把本次已放进去的食材从蒸笼格里摘掉，绝不留成残渣。
     * 只摘与本次 {@code placed} 指纹一致、且 {@code mayExtract()} 为真的格子：
     * 正在蒸的食材不属于本次失败回滚范围，动它会与原生进度对不上。
     */
    private void detachRecoveredInputs(BlockEntity be, List<ItemStack> placed) {
        List<?> racks = getRacks(be);
        if (racks != null) {
            for (ItemStack expected : placed) {
                if (expected.isEmpty()) continue;
                for (Object rack : racks) {
                    Object[] items = getRackItems(rack);
                    if (items == null) continue;
                    for (Object itemData : items) {
                        if (itemData == null) continue;
                        ItemStack visible = getStack(itemData);
                        if (visible.isEmpty() || !ItemStack.isSameItemSameTags(visible, expected)) continue;
                        if (!mayExtract(itemData)) continue;
                        setStack(itemData, be, ItemStack.EMPTY);
                        markItemDirty(itemData);
                        break;
                    }
                }
            }
        }
        // 这些材料由共享账本退款，不在这里直接退还，避免同一份退两遍。
        placedInputs.clear();
        be.setChanged();
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isSteamerBE(be)) return false;
        return getReadyItemData(be).size() >= Math.max(1, plannedOperations);
    }

    /**
     * 收集所有已蒸熟、可取的格子。
     * <p>
     * 原生的 {@code mayExtract()} 只有在食材"已经变成成品"时才为真，
     * 因此这里取到的都是熟食，不会把生食材当成品交出去。
     */
    private List<Object> getReadyItemData(BlockEntity be) {
        List<Object> ready = new ArrayList<>();
        List<?> racks = getRacks(be);
        if (racks == null) return ready;
        for (Object rack : racks) {
            Object[] items = getRackItems(rack);
            if (items == null) continue;
            for (Object itemData : items) {
                if (itemData == null) continue;
                if (mayExtract(itemData)) ready.add(itemData);
            }
        }
        return ready;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        List<ItemStack> all = collectAllResults(player);
        return all.isEmpty() ? ItemStack.EMPTY : all.get(0);
    }

    @Override
    public List<ItemStack> collectAllResults(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(potBasePos != null ? potBasePos : myPos);
        if (be == null || !isSteamerBE(be)) return List.of();

        List<ItemStack> results = new ArrayList<>();
        for (Object itemData : getReadyItemData(be)) {
            ItemStack s = getStack(itemData);
            if (s.isEmpty()) continue;
            setStack(itemData, be, ItemStack.EMPTY);
            markItemDirty(itemData);
            results.add(s.copy());
        }
        if (!results.isEmpty()) {
            be.setChanged();
        }
        // 无论取到几份都算本次操作已收尾；取不到时交给链的缺产物路径，
        // 不在这里凭空补一份 recipe.getResultItem()——那会在产物已被磁铁吸走时刷物。
        craftDone = true;
        return List.copyOf(results);
    }

    @Override
    public List<OutputAccounting.CollectedOutput> collectStructuredResults(ServerPlayer player) {
        OutputContract contract = outputContract();
        if (contract.ports().size() != 1) return List.of();
        OutputContract.Port port = contract.ports().get(0);
        // 逐格上报：链在结构化结算路径下只看 collectStructuredResults，
        // 会忽略 collectAllResults，因此这里必须自己列出每一份产物供端口对账。
        return collectAllResults(player).stream()
                .filter(stack -> stack != null && !stack.isEmpty())
                .map(stack -> new OutputAccounting.CollectedOutput(port.portId(), port.source(), stack))
                .toList();
    }

    @Override
    public boolean canCollectResultWithoutWorldCapture() {
        return true;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        this.player = player == null ? this.player : player;
        recordFailureRecoveredInputs(recoverUnconsumedInputs());
        forceChunkLoad(false);
        craftDone = false;
        plannedOperations = 1;
        potBasePos = null;
        placedInputs.clear();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        forceChunkLoad(false);
        // 收尾清空蒸笼格：成品已由 collectAllResults 逐格取出并结算，
        // 留在这里的要么是已被账本结算（不再退款）的材质，要么是原生自己放的料。
        // 一律清掉、不再退还，否则会与账本的退款/虚拟产物重复结算。
        clearRackSlots();
        craftDone = false;
        plannedOperations = 1;
        setStorageEndpoint(null);
        potBasePos = null;
        placedInputs.clear();
    }

    /**
     * 失败清理：只摘回"还是原料形态"的那部分。
     * <p>
     * 这些正是共享账本为本次操作预留的材料，把它们如实上报后由账本统一退款；
     * 已经蒸熟的那些不属于退款范围，留在这里由 {@link #onBatchFinished} 清掉，
     * 绝不在两处重复退还同一份材料。
     *
     * @return 本次确实收回的原料堆叠，供账本据此退款
     */
    private List<ItemStack> recoverUnconsumedInputs() {
        List<ItemStack> recovered = new ArrayList<>();
        BlockPos pos = potBasePos != null ? potBasePos : myPos;
        if (!myLevel.hasChunkAt(pos)) return recovered;
        BlockEntity be = myLevel.getBlockEntity(pos);
        if (be == null || !isSteamerBE(be)) return recovered;

        ItemStack raw = ingredientPrototype();
        List<?> racks = getRacks(be);
        if (racks != null && !raw.isEmpty()) {
            for (Object rack : racks) {
                Object[] items = getRackItems(rack);
                if (items == null) continue;
                for (Object itemData : items) {
                    if (itemData == null) continue;
                    ItemStack s = getStack(itemData);
                    if (s.isEmpty() || !mayExtract(itemData)) continue;
                    if (!ItemStack.isSameItemSameTags(s, raw)) continue;
                    setStack(itemData, be, ItemStack.EMPTY);
                    markItemDirty(itemData);
                    recovered.add(s.copy());
                }
            }
            if (!recovered.isEmpty()) be.setChanged();
        }
        placedInputs.clear();
        return recovered;
    }

    /** 清空蒸笼格里所有"可提取"的堆叠，不做退还（结算已由账本完成）。 */
    private void clearRackSlots() {
        BlockPos pos = potBasePos != null ? potBasePos : myPos;
        if (!myLevel.hasChunkAt(pos)) return;
        BlockEntity be = myLevel.getBlockEntity(pos);
        if (be == null || !isSteamerBE(be)) return;
        List<?> racks = getRacks(be);
        if (racks == null) return;
        boolean cleared = false;
        for (Object rack : racks) {
            Object[] items = getRackItems(rack);
            if (items == null) continue;
            for (Object itemData : items) {
                if (itemData == null) continue;
                ItemStack s = getStack(itemData);
                if (s.isEmpty() || !mayExtract(itemData)) continue;
                setStack(itemData, be, ItemStack.EMPTY);
                markItemDirty(itemData);
                cleared = true;
            }
        }
        if (cleared) be.setChanged();
    }

    @Override
    public BlockPos getMachinePos() { return potBasePos != null ? potBasePos : myPos; }

    // -- plan helpers --

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.youkaishomecoming.heat_warning"));
        warnings.add(Component.translatable("rsi.youkaishomecoming.steamer_rack_warning"));
        warnings.add(Component.translatable("rsi.youkaishomecoming.lid_warning"));
        return warnings;
    }

    // -- multi-block structure --

    /** 蒸锅本体之上叠了几层蒸笼（不含本体自带的那一层）。 */
    private int rackLayers() {
        if (myLevel == null || potBasePos == null) return 0;
        int layers = 0;
        BlockPos pos = potBasePos.above();
        for (int i = 0; i < SteamerBatchConfig.MAX_RACKS - SteamerBatchConfig.POT_BASE_RACKS; i++) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(myLevel.getBlockState(pos).getBlock());
            if (!matchesMachineBlock(key, "steamer_rack")) break;
            layers++;
            pos = pos.above();
        }
        return layers;
    }

    /** 蒸笼层数与盖子是否满足批量铺料的前置条件。 */
    private boolean hasRackStructure() {
        if (rackLayers() <= 0) return false;
        BlockEntity be = myLevel.getBlockEntity(potPos());
        return be != null && isSteamerBE(be) && hasLid(be);
    }

    /** 单份蒸制耗时（tick），供批量份数折算；读取失败时回退到基准值。 */
    private int processTime() {
        if (recipe == null) return SteamerBatchConfig.BASE_STEAM_TIME;
        int time = recipe.getCookingTime();
        return time > 0 ? time : SteamerBatchConfig.BASE_STEAM_TIME;
    }

    /** 本配方的产物（不含数量缩放）。 */
    private ItemStack getRecipeResult() {
        if (recipe == null || myLevel == null) return ItemStack.EMPTY;
        ItemStack out = RecipeIndex.tryGetResultItem(recipe, myLevel.registryAccess());
        return out == null ? ItemStack.EMPTY : out;
    }

    /** 本配方唯一输入的第一个可用样本，用于声明输入缓冲槽位原型。 */
    private ItemStack ingredientPrototype() {
        if (recipe == null || recipe.getIngredients().isEmpty()) return ItemStack.EMPTY;
        for (ItemStack candidate : recipe.getIngredients().get(0).getItems()) {
            if (candidate != null && !candidate.isEmpty()) return candidate.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    private BlockEntity findPotBase() {
        // Walk downward from myPos to find the steamer pot block.
        // The SteamerBlockEntity lives in the pot (bottom) block.
        BlockPos pos = myPos;
        for (int i = 0; i < 5; i++) {
            BlockState state = myLevel.getBlockState(pos);
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if (matchesMachineBlock(key, "steamer_pot")) {
                BlockEntity be = myLevel.getBlockEntity(pos);
                if (be != null && isSteamerBE(be)) {
                    this.potBasePos = pos.immutable();
                    return be;
                }
            }
            pos = pos.below();
        }
        return null;
    }

    private boolean hasLid(BlockEntity be) {
        List<?> racks = getRacks(be);
        if (racks == null) return false;

        // YHK stores a lid in CAPPED on compact stacks. Only a full-height
        // stack places a separate steamer_lid block above the structure.
        List<SteamerStructurePolicy.Layer> layers = new ArrayList<>();
        BlockPos checkPos = potBasePos;
        for (int i = 0; i < 6; i++) {
            BlockState state = myLevel.getBlockState(checkPos);
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            boolean structural = matchesMachineBlock(key, "steamer_pot")
                    || matchesMachineBlock(key, "steamer_rack");
            layers.add(new SteamerStructurePolicy.Layer(
                    structural, matchesMachineBlock(key, "steamer_lid"),
                    structural && isCapped(state)));
            if (!structural) break;
            checkPos = checkPos.above();
        }
        return SteamerStructurePolicy.hasLid(layers);
    }

    private boolean matchesMachineBlock(ResourceLocation id, String path) {
        return id != null && machineNamespace.equals(id.getNamespace()) && path.equals(id.getPath());
    }

    private static boolean isCapped(BlockState state) {
        for (var property : state.getProperties()) {
            if (property instanceof BooleanProperty booleanProperty
                    && "capped".equalsIgnoreCase(property.getName())) {
                return state.getValue(booleanProperty);
            }
        }
        return false;
    }

    // -- reflection --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.steamerBEClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] YHKReflection steamer probe not ready");
            return;
        }
        try {
            racksField = YHKReflection.steamerBEClass.getField("racks");

            // isHeated(Level, BlockPos) is from HeatableBlockEntity interface (default method)
            isHeatedMethod = YHKReflection.steamerBEClass.getMethod("isHeated", Level.class, BlockPos.class);
            if (isHeatedMethod != null) isHeatedMethod.setAccessible(true);

            if (YHKReflection.rackDataClass != null) {
                tryAddItemMethod = YHKReflection.rackDataClass.getMethod("tryAddItem", YHKReflection.steamerBEClass, Level.class, ItemStack.class);
                tryAddItemMethod.setAccessible(true);
                tryTakeItemMethod = findMethodInHierarchy(YHKReflection.rackDataClass, "tryTakeItem",
                        YHKReflection.steamerBEClass, Level.class, Player.class,
                        InteractionHand.class);
                if (tryTakeItemMethod != null) tryTakeItemMethod.setAccessible(true);
            }

            if (YHKReflection.rackItemDataClass != null) {
                mayExtractMethod = YHKReflection.rackItemDataClass.getMethod("mayExtract");
                stackField = YHKReflection.rackItemDataClass.getField("stack");
                rackSetStackMethod = YHKReflection.rackItemDataClass.getMethod("setStack", YHKReflection.steamerBEClass, ItemStack.class);
                rackSetStackMethod.setAccessible(true);
                setChangedMethod = YHKReflection.rackItemDataClass.getMethod("setChanged");
            }

            if (YHKReflection.steamerPotBlockClass != null) {
                try {
                    // Try WATER (old YHK SteamerPotBlock) then WATERLOGGED (new YHK BasePotBlock)
                    try {
                        waterPropertyField = YHKReflection.steamerPotBlockClass.getDeclaredField("WATER");
                    } catch (NoSuchFieldException e) {
                        waterPropertyField = YHKReflection.steamerPotBlockClass.getDeclaredField("WATERLOGGED");
                    }
                    waterPropertyField.setAccessible(true);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Water property probe failed", e);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Reflection probe failed", e);
        }
    }

    private static boolean isSteamerBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.steamerBEClass != null && YHKReflection.steamerBEClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.steamerBEClass != null && YHKReflection.steamerBEClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
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

    // -- rack operations --

    private static List<?> getRacks(BlockEntity be) {
        if (racksField != null) {
            try {
                return (List<?>) racksField.get(be);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to read racks field", e);
            }
        }
        return null;
    }

    private boolean tryAddItemToRack(Object rack, BlockEntity be, ItemStack stack) {
        if (tryAddItemMethod != null) {
            try {
                return (boolean) tryAddItemMethod.invoke(rack, be, myLevel, stack);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Steamer] Failed to add item to rack", e);
            }
        }
        return false;
    }

    private static Object[] getRackItems(Object rack) {
        if (rack == null) return null;
        try {
            Field listField = rack.getClass().getField("list");
            return (Object[]) listField.get(rack);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to read rack items list", e);
        }
        return null;
    }

    private static boolean mayExtract(Object itemData) {
        if (mayExtractMethod != null) {
            try {
                return (boolean) mayExtractMethod.invoke(itemData);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to check mayExtract", e);
            }
        }
        return false;
    }

    private static ItemStack getStack(Object itemData) {
        if (stackField != null) {
            try {
                return (ItemStack) stackField.get(itemData);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Steamer] Failed to read rack stack -- item may be lost", e);
            }
        }
        return ItemStack.EMPTY;
    }

    private static void setStack(Object itemData, BlockEntity be, ItemStack stack) {
        if (rackSetStackMethod != null) {
            try {
                rackSetStackMethod.invoke(itemData, be, stack);
                return;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Steamer] Failed to set rack stack (method)", e);
            }
        }
        // Fallback: direct field set
        if (stackField != null) {
            try {
                stackField.set(itemData, stack);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.error("[RSI-Steamer] Failed to set rack stack (field)", e);
            }
        }
    }

    private static void markItemDirty(Object itemData) {
        if (setChangedMethod != null) {
            try {
                setChangedMethod.invoke(itemData);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to mark item dirty", e);
            }
        }
    }

    // -- heat / water --

    private BlockPos potPos() {
        return potBasePos != null ? potBasePos : myPos;
    }

    private boolean isHeated(BlockEntity be) {
        if (isHeatedMethod == null) return true; // can't check, assume heated
        try {
            return (boolean) isHeatedMethod.invoke(be, myLevel, potPos());
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Steamer] isHeated invoke failed", e);
        }
        return true; // can't check, assume heated so we don't block
    }

    private boolean hasWater() {
        var state = myLevel.getBlockState(potPos());
        if (waterPropertyField != null) {
            try {
                Object prop = waterPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp))
                    return state.getValue(bp);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to read water property", e);
            }
        }
        for (var prop : state.getProperties()) {
            String name = prop.getName();
            if ((name.equalsIgnoreCase("water") || name.equalsIgnoreCase("waterlogged"))
                    && prop instanceof BooleanProperty bp)
                return state.getValue(bp);
        }
        return true;
    }

    private boolean setWaterProperty(BlockState state, boolean value) {
        BlockPos p = potPos();
        if (waterPropertyField != null) {
            try {
                Object prop = waterPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp)) {
                    return myLevel.setBlock(p, state.setValue(bp, value), 3);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Steamer] Failed to set water property", e);
            }
        }
        for (var prop : state.getProperties()) {
            String name = prop.getName();
            if ((name.equalsIgnoreCase("water") || name.equalsIgnoreCase("waterlogged"))
                    && prop instanceof BooleanProperty bp) {
                return myLevel.setBlock(p, state.setValue(bp, value), 3);
            }
        }
        return false;
    }

    // -- cleanup --

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }
}
