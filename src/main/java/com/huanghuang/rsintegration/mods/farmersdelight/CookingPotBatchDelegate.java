package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import java.util.Arrays;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OperationInputRecovery;
import com.huanghuang.rsintegration.crafting.batch.OperationInputRecovery.Input;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.farmersdelight.FarmersDelightRecipeHandler;
import com.huanghuang.rsintegration.mods.goetydelight.GoetyDelightCookingPotSupport;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.FarmersDelightReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
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
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Batch delegate for Farmer's Delight Cooking Pot. */
public class CookingPotBatchDelegate extends AbstractBatchDelegate {

    // Slot layout matching CookingPotBlockEntity
    private static final int INPUT_SLOTS = 6;   // 0..5
    private static final int MEAL_DISPLAY_SLOT = 6;
    private static final int CONTAINER_SLOT = 7;
    private static final int OUTPUT_SLOT = 8;

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    /** 当前配方；子类（如 Let's Do 系列锅）的材料读取需要直接访问，故为 protected。 */
    protected Recipe<?> recipe;
    private boolean craftDone;
    private boolean arcaneCookingPot;
    private boolean goetyDelightCookingPot;
    private BlockEntity spellPotEntity;
    private FarmersSpellPotAccess spellPot;
    private ItemStack placedScroll = ItemStack.EMPTY;
    private int plannedOperations = 1;
    @Nullable
    private OperationInputRecovery inputRecovery;
    @Nullable
    private String startFailureDetail;

    private static volatile Field inventoryField;
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
        if (found == null) found = CosmopolitanTisaneRecipeResolver.resolve(recipeId);
        if (found == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        if (!isSupportedRecipe(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.arcaneCookingPot = false;
        this.plannedOperations = 1;
        this.inputRecovery = null;
        this.startFailureDetail = null;
        BlockEntity existing = level.getBlockEntity(pos);
        this.spellPotEntity = existing instanceof FarmersSpellPotAccess ? existing : null;
        this.spellPot = existing instanceof FarmersSpellPotAccess pot ? pot : null;
        if (FarmersSpellPotSupport.isRecipe(found) && spellPot == null) return false;
        if (!GoetyDelightCookingPotSupport.acceptsRecipe(existing, found)) return false;
        this.goetyDelightCookingPot = GoetyDelightCookingPotSupport.isCookingPot(existing);
        this.arcaneCookingPot = ArcaneStoveSupport.isArcaneCookingPot(existing);
        IItemHandler existingInventory = existing != null
                && isSupportedBlockEntity(existing)
                ? getInventory(existing) : null;
        if (existingInventory == null || existingInventory.getSlots() < inventorySize()) return false;
        // Existing contents are drained to the active RS network immediately
        // before a new operation starts. Preparation must remain side-effect
        // free because it is also used while probing candidate machines.
        return true;
    }

    @Override
    public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                     @Nullable ResourceLocation dim, BlockPos pos) {
        PreparationResult result = super.prepare(player, recipeId, dim, pos);
        if (result.state() != PreparationState.READY || spellPot != null) return result;
        // 奥术炉灶可以在启动时点火，魔法食事锅则要等选定卷轴后才能判断热源。
        if (arcaneStoveBelow() != null) return result;
        Component requirement = missingCookingRequirement(myLevel.getBlockEntity(pos));
        return requirement == null ? result
                : PreparationResult.waiting("cooking pot lacks its required heat or soul energy at " + pos, requirement);
    }

    @Override
    public MaterialPlan materialPlan() {
        return MaterialPlan.graphSlots(recipeSlots());
    }

    protected List<IngredientSpec> recipeSlots() {
        List<IngredientSpec> specs = getRecipeIngredientSpecs();
        if (specs == null) specs = new ArrayList<>();
        else specs = new ArrayList<>(specs);

        // Declared output containers are already appended by the recipe handler
        // so they participate in graph planning. Only add the legacy result-
        // remainder fallback here when the recipe declares no container.
        ItemStack declared = getDeclaredContainerItem(recipe);
        ItemStack container = getRequiredContainer(recipe,
                myLevel != null ? myLevel.registryAccess() : null);
        if (!declared.isEmpty() && !container.isEmpty()
                && (!ItemStack.isSameItemSameTags(declared, container)
                || declared.getCount() != container.getCount())) {
            for (int i = specs.size() - 1; i >= 0; i--) {
                IngredientSpec spec = specs.get(i);
                if (spec.count() == declared.getCount() && spec.ingredient().test(declared)) {
                    specs.remove(i);
                    break;
                }
            }
            specs.add(new IngredientSpec(Ingredient.of(container.copyWithCount(1)),
                    container.getCount()));
        } else if (declared.isEmpty() && !container.isEmpty() && !FarmersSpellPotSupport.isRecipe(recipe)) {
            specs.add(new IngredientSpec(Ingredient.of(container.copyWithCount(1)),
                    container.getCount()));
        }
        return specs.isEmpty() ? null : specs;
    }

    @Nullable
    private List<IngredientSpec> getRecipeIngredientSpecs() {
        var handler = ModRecipeHandlers.claimFor(recipe).winner();
        return handler != null
                ? handler.getIngredients(recipe)
                : CraftPacketUtils.extractIngredientSpecs(recipe);
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.machineSlotWithLocalWorldItems();
    }

    @Override
    public int prepareFlatBatch(int remainingOperations) {
        if (!supportsInputBuffer()) return Math.max(0, Math.min(1, remainingOperations));
        plannedOperations = inputBufferPlan(remainingOperations).operations();
        return plannedOperations;
    }

    @Override
    public void prepareGraphBatch(int executions) {
        int requested = Math.max(1, executions);
        // 图批次必须与实际输入缓冲上限一致；否则 startBufferedOperation 会因
        // plan.operations() 与 inputBufferPlan() 重新计算的上限不一致而失败，
        // 调度器却只看到 worker 未启动，表现为“一直执行但不制作”。
        plannedOperations = supportsInputBuffer()
                ? Math.max(1, inputBufferPlan(requested).operations())
                : 1;
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
                ? Math.max(Math.max(1, configuredLimit), inputBufferLimit())
                : Math.max(1, configuredLimit);
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return supportsInputBuffer();
    }

    @Override
    public boolean supportsInputBuffer() {
        if (spellPot != null || !inputBufferEnabled() || arcaneCookingPot || goetyDelightCookingPot || recipe == null
                || myLevel == null || myPos == null || !myLevel.hasChunkAt(myPos)) return false;
        BlockEntity machine = myLevel.getBlockEntity(myPos);
        if (machine == null || !supportsBufferedMachine(machine)) return false;
        List<IngredientSpec> specs = recipeSlots();
        if (specs == null || specs.isEmpty() || specs.size() > inputSlots() + 1) return false;
        ItemStack requiredContainer = getRequiredContainer(recipe, myLevel.registryAccess());
        ItemStack declaredContainer = getDeclaredContainerItem(recipe);
        if (!supportsBufferedContainer(requiredContainer, declaredContainer)) return false;
        int ingredientCount = specs.size() - (requiredContainer.isEmpty() ? 0 : 1);
        if (ingredientCount <= 0 || ingredientCount > inputSlots()) return false;
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            ItemStack prototype = ingredientPrototype(spec);
            if (spec == null || spec.isEmpty() || spec.role() != DemandRole.CONSUMED
                    || spec.count() <= 0 || prototype.isEmpty()) return false;
            if (i < ingredientCount && hasIngredientRemainderRisk(machine, spec)) return false;
        }
        return !getExpectedRecipeResult(recipe, myLevel.registryAccess()).isEmpty();
    }

    @Override
    public InputBufferContract inputBufferContract() {
        if (!supportsInputBuffer()) return InputBufferContract.none();
        BlockEntity be = myLevel.getBlockEntity(myPos);
        IItemHandler handler = be == null ? null : getInventory(be);
        if (handler == null || handler.getSlots() < inventorySize()) {
            return InputBufferContract.none();
        }
        List<IngredientSpec> specs = recipeSlots();
        ItemStack container = getRequiredContainer(recipe, myLevel.registryAccess());
        int ingredientCount = specs.size() - (container.isEmpty() ? 0 : 1);
        List<InputBufferContract.InputSlot> inputs = new ArrayList<>(specs.size());
        for (int i = 0; i < ingredientCount; i++) {
            IngredientSpec spec = specs.get(i);
            ItemStack prototype = ingredientPrototype(spec);
            inputs.add(new InputBufferContract.InputSlot(
                    "slot:" + i, i, prototype, spec.count(),
                    slotCapacity(handler, i, prototype), spec.consumption()));
        }
        if (!container.isEmpty()) {
            IngredientSpec spec = specs.get(specs.size() - 1);
            ItemStack prototype = ingredientPrototype(spec);
            inputs.add(new InputBufferContract.InputSlot(
                    "slot:" + (specs.size() - 1), containerSlot(), prototype,
                    spec.count(), slotCapacity(handler, containerSlot(), prototype),
                    spec.consumption()));
        }
        ItemStack output = getExpectedRecipeResult(recipe, myLevel.registryAccess());
        int outputCapacity = slotCapacity(handler, outputSlot(), output);
        int operations = bufferedOperationCapacity(inputBufferLimit(), inputs,
                output.getCount(), outputCapacity);
        if (operations <= 0) return InputBufferContract.none();
        return new InputBufferContract(operations, inputs,
                List.of(new OutputContract.Port(
                        outputPortId(), outputSlot(), output,
                        output.getCount(), OutputContract.Kind.PRIMARY,
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
        if (plan != null && plan.enabled()) {
            holdInputs(player, plan.inputs().stream().map(InputBufferPlan.InputSlot::stack).toList());
        }
        if (!supportsInputBuffer() || plan == null || !plan.enabled()) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-CookingPot] 输入缓冲不可用 recipe={} pos={}",
                    recipe == null ? null : recipe.getId(), myPos);
            return false;
        }
        List<IngredientSpec> specs = recipeSlots();
        if (specs == null || plan.inputs().size() != specs.size()) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-CookingPot] 输入缓冲规格不一致 recipe={} planned={} required={}",
                    recipe.getId(), plan.inputs().size(), specs == null ? null : specs.size());
            return false;
        }
        List<ItemStack> ordered = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            InputBufferPlan.InputSlot input = inputByEntryId(plan, "slot:" + i);
            IngredientSpec spec = specs.get(i);
            int expectedSlot = i == specs.size() - 1
                    && !getRequiredContainer(recipe, myLevel.registryAccess()).isEmpty()
                    ? containerSlot() : i;
            long required = (long) spec.count() * plan.operations();
            if (input == null || input.slot() != expectedSlot
                    || input.consumption().reusableWithinWorkerBatch()
                    || input.perOperation() != spec.count() || required > Integer.MAX_VALUE
                    || input.stack().getCount() != (int) required
                    || !spec.ingredient().test(input.stack())) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-CookingPot] 输入缓冲槽位校验失败 recipe={} entry={} expectedSlot={} actualSlot={} expectedCount={} actualCount={}",
                        recipe.getId(), i, expectedSlot, input == null ? null : input.slot(),
                        required, input == null ? null : input.stack().getCount());
                return false;
            }
            ordered.add(input.stack().copy());
        }
        InputBufferPlan expected = inputBufferPlan(plan.operations());
        if (!expected.enabled() || expected.operations() != plan.operations()) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-CookingPot] 输入缓冲上限复校验失败 recipe={} requested={} expected={} enabled={}",
                    recipe.getId(), plan.operations(), expected.operations(), expected.enabled());
            return false;
        }
        plannedOperations = plan.operations();
        return startPreparedOperation(player, ordered, sharedLedger);
    }

    @Override
    public OutputContract outputContract() {
        if (!supportsInputBuffer()) return OutputContract.none();
        ItemStack output = getExpectedRecipeResult(recipe, myLevel.registryAccess());
        return output.isEmpty() ? OutputContract.none() : new OutputContract(List.of(
                new OutputContract.Port(outputPortId(), outputSlot(),
                        output, output.getCount(), OutputContract.Kind.PRIMARY,
                        OutputContract.Source.SLOT)));
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        List<IngredientSpec> specs = recipeSlots();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = materialAccess().newLedger()) {
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

            if (!ledger.commitToEndpoint(storageEndpoint(), player)) return false;

            boolean started = false;
            try {
                started = startPreparedOperation(player, materials, ledger);
                return started;
            } finally {
                if (started) {
                    usingSharedLedger = false;
                } else {
                    recover(player, "cooking pot start failed");
                    List<ItemStack> recovered = failureRecoveredInputs();
                    if (recovered != null) {
                        ledger.retainCommittedRefunds(recovered);
                        ledger.refundCommittedToEndpoint(endpoint, player);
                    }
                }
            }
        }
    }

    private List<Input> holdInputs(ServerPlayer player, List<ItemStack> materials) {
        this.player = player;
        this.usingSharedLedger = true;
        this.craftDone = false;
        this.startFailureDetail = null;
        this.inputRecovery = new OperationInputRecovery();
        return new ArrayList<>(materials.stream().map(inputRecovery::hold).toList());
    }

    @Override
    public boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        // 即使机器在校验阶段拒绝启动，已交接但未投放的实物也必须可退。
        List<Input> inputs = holdInputs(player, materials);
        boolean started = false;
        try {
            started = startPreparedOperation(player, materials, inputs);
            return started;
        } finally {
            // 失败 worker 可能还要等其他机器结束；现在就撤回本次投料，稍后由账本结算。
            if (!started) inputRecovery.recover(myLevel);
        }
    }

    private boolean startPreparedOperation(ServerPlayer player, List<ItemStack> materials, List<Input> inputs) {
        int scrollIndex = spellPot == null ? -1 : (int) recipe.getIngredients().stream()
                .filter(ingredient -> !ingredient.isEmpty()).count();
        if (!placedScroll.isEmpty() && scrollIndex >= 0 && scrollIndex < materials.size()
                && ItemStack.matches(placedScroll, materials.get(scrollIndex))) {
            // 沿用上批卷轴时，仍由独立的卷轴回收流程认领，不能按未投放材料再退一次。
            inputs.get(scrollIndex).release();
        }

        if (!myLevel.hasChunkAt(myPos)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] 机器区块未加载 pos={}", myPos);
            return false;
        }

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] BlockEntity missing at {}", myPos);
            return false;
        }
        if (!isSupportedBlockEntity(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Wrong BE type: {}", be.getClass().getName());
            return false;
        }
        if ((be instanceof FarmersSpellPotAccess || spellPot != null) && be != spellPotEntity) {
            return false;
        }
        if (!GoetyDelightCookingPotSupport.acceptsRecipe(be, recipe)) return false;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null || itemHandler.getSlots() < inventorySize()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Cannot access item handler");
            return false;
        }

        // A stale/partially completed pot must not block the scheduler. Drain
        // every slot before reserving this operation's inputs; the old contents
        // are not owned by the new shared ledger.
        if (storageEndpoint() == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] 未选择存储端点 pos={}", myPos);
            return false;
        }
        List<IngredientSpec> requiredSpecs = recipeSlots();
        ItemStack requiredContainer = getRequiredContainer(recipe, myLevel.registryAccess());
        if (goetyDelightCookingPot && !GoetyDelightCookingPotSupport.matchesNativeChoice(
                be, recipe, materials, !requiredContainer.isEmpty())) {
            player.sendSystemMessage(Component.translatable("rsi.goetydelight.recipe_conflict"));
            return false;
        }
        ItemStack selectedScroll = ItemStack.EMPTY;
        Input scrollInput = null;
        if (spellPot != null) {
            ResourceLocation school = FarmersSpellPotSupport.requiredSchool(recipe);
            if (school != null) {
                if (requiredSpecs == null || materials.size() != requiredSpecs.size()) return false;
                if (scrollIndex >= materials.size() || requiredSpecs.get(scrollIndex).role() != DemandRole.CATALYST) {
                    return false;
                }
                selectedScroll = materials.get(scrollIndex);
                if (selectedScroll.getCount() != 1 || !school.equals(FarmersSpellPotSupport.schoolOf(selectedScroll))
                        || !spellPot.rsi$scrollInventory().isItemValid(0, selectedScroll)) {
                    player.sendSystemMessage(Component.translatable("rsi.farmers_spell.scroll_required", school.toString()));
                    return false;
                }
                materials = new ArrayList<>(materials);
                materials.remove(scrollIndex);
                scrollInput = inputs.remove(scrollIndex);
                requiredSpecs = new ArrayList<>(requiredSpecs);
                requiredSpecs.remove(scrollIndex);
            }
            boolean needsContainer = !requiredContainer.isEmpty();
            if (!FarmersSpellPotSupport.matchesNativeChoice(myLevel, recipe, materials, selectedScroll, needsContainer)) {
                player.sendSystemMessage(Component.translatable("rsi.farmers_spell.recipe_conflict"));
                return false;
            }
            if (!FarmersSpellPotSupport.hasHeat(be, myLevel, myPos, selectedScroll)) {
                player.sendSystemMessage(Component.translatable("rsi.farmersdelight.no_heat"));
                return false;
            }
            if (!prepareScrollSlot(selectedScroll)) return false;
        }
        BlockEntity heatSource = arcaneStoveBelow();
        if (spellPot == null && heatSource == null && !checkCookingRequirement(player, be)) return false;
        if (!drainExistingContentsToNetwork(itemHandler)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] 无法清空机器原有内容 pos={}", myPos);
            return false;
        }

        forceChunkLoad(true);

        // The bound machine is the pot, but Arcane Stove fuel state lives one
        // block below it. Invoke the stove's native interaction before checking heat.
        if (heatSource != null
                && !ArcaneStoveSupport.ensureBurning(player, heatSource,
                FarmersDelightRecipeHandler.getCookTime(recipe),
                new ArcaneStoveSupport.FuelAccess() {
                    @Override public ItemStack extract(ItemStack template) {
                        return extractExactFromStorage(player, template, 1, false);
                    }

                    @Override public void refund(ItemStack stack) {
                        ItemStack remainder = insertIntoStorage(player, stack, false);
                        if (!remainder.isEmpty()) {
                            ItemHandlerHelper.giveItemToPlayer(player, remainder);
                        }
                    }
                })) {
            forceChunkLoad(false);
            return false;
        }

        if (heatSource != null && spellPot == null && !checkCookingRequirement(player, be)) {
            forceChunkLoad(false);
            return false;
        }

        ItemStack containerMaterial = ItemStack.EMPTY;
        int inputEnd = materials.size();
        if (!requiredContainer.isEmpty()) {
            ItemStack suppliedContainer = inputEnd == 0 ? ItemStack.EMPTY : materials.get(inputEnd - 1);
            // 原生烹饪锅按物品类型认容器；投放和回收仍使用带原始 NBT 的实物。
            if (suppliedContainer.isEmpty() || !ItemStack.isSameItem(suppliedContainer, requiredContainer)) {
                startFailureDetail = "invalid serving container for recipe " + recipe.getId();
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Planned container missing for recipe {}: {}",
                        recipe.getId(), requiredContainer);
                return false;
            }
            long requiredCount = (long) requiredContainer.getCount() * plannedOperations;
            if (requiredCount > Integer.MAX_VALUE || suppliedContainer.getCount() != (int) requiredCount) {
                startFailureDetail = "wrong serving container count for recipe " + recipe.getId();
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-CookingPot] 容器数量不匹配 recipe={} required={} supplied={} operations={}",
                        recipe.getId(), requiredCount, suppliedContainer.getCount(), plannedOperations);
                return false;
            }
            containerMaterial = suppliedContainer.copy();
            inputEnd--;
        }
        List<Integer> ingredientIndices = new ArrayList<>(inputEnd);
        for (int i = 0; i < inputEnd; i++) {
            if (!materials.get(i).isEmpty()) ingredientIndices.add(i);
        }

        int materialCount = ingredientIndices.size();
        if (materialCount > inputSlots()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Recipe {} has {} ingredients but only {} input slots",
                    recipe.getId(), materialCount, inputSlots());
            forceChunkLoad(false);
            return false;
        }

        int requiredInputCount = requiredSpecs == null ? 0
                : requiredSpecs.size() - (requiredContainer.isEmpty() ? 0 : 1);
        if (requiredInputCount != materialCount) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Batch-CookingPot] 食材条目数量不匹配 recipe={} required={} supplied={}",
                    recipe.getId(), requiredInputCount, materialCount);
            return false;
        }

        // Insert one stack per recipe ingredient. The native pot consumes one
        // item from every occupied input slot after each completed cycle.
        for (int slot = 0; slot < materialCount; slot++) {
            int materialIndex = ingredientIndices.get(slot);
            ItemStack mat = materials.get(materialIndex);
            IngredientSpec spec = requiredSpecs.get(slot);
            long requiredCount = (long) spec.count() * plannedOperations;
            if (requiredCount > Integer.MAX_VALUE || mat.getCount() != (int) requiredCount
                    || !spec.ingredient().test(mat)) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-CookingPot] 食材校验失败 recipe={} slot={} required={} supplied={} ingredient={}",
                        recipe.getId(), slot, requiredCount, mat.getCount(),
                        CraftPacketUtils.describeIngredient(spec.ingredient()).getString());
                forceChunkLoad(false);
                return false;
            }
            ItemStack remainder = placeInput(inputs.get(materialIndex), be, itemHandler, slot, mat);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Failed to insert into slot {}: {}",
                        slot, remainder.getHoverName().getString());
                return false;
            }
        }
        be.setChanged();

        // Insert the container that was reserved by the same shared ledger as the
        // ingredients. Never extract it from RS out-of-band after commit.
        if (!requiredContainer.isEmpty()) {
            ItemStack existingContainer = itemHandler.getStackInSlot(containerSlot());
            if (!existingContainer.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Container slot occupied at {}", myPos);
                return false;
            }
            ItemStack simulated = itemHandler.insertItem(containerSlot(), containerMaterial, true);
            if (!simulated.isEmpty()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-CookingPot] 容器槽模拟插入失败 pos={} remainder={}",
                        myPos, simulated.getCount());
                return false;
            }
            ItemStack remainder = placeInput(inputs.get(inputEnd), be, itemHandler, containerSlot(), containerMaterial);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-CookingPot] 容器槽实际插入失败 pos={} remainder={}",
                        myPos, remainder.getCount());
                return false;
            }
            be.setChanged();
        }

        if (spellPot != null) {
            if (scrollInput != null && !installScroll(selectedScroll, scrollInput)) return false;
            spellPot.rsi$setManagedCooking(true);
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] Materials inserted, cooking should start next tick");
        markCraftStarted();
        return true;
    }

    private ItemStack placeInput(Input input, BlockEntity be, IItemHandler handler, int slot, ItemStack stack) {
        if (!handler.getStackInSlot(slot).isEmpty()) return stack;
        input.place(be, handler, slot);
        ItemStack remainder = handler.insertItem(slot, stack.copy(), false);
        if (!remainder.isEmpty()) inputRecovery.hold(remainder);
        return remainder;
    }

    @Override
    @Nullable
    public String lastStartFailureDetail() {
        return startFailureDetail;
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        if (spellPot != null) {
            if (be != spellPotEntity) return failObservation("Arcane Cooking Pot replaced");
            if (!ItemStack.matches(placedScroll, spellPot.rsi$scrollInventory().getStackInSlot(0))
                    && !isMachineCraftFinished(level, be)) {
                return failObservation("Arcane Cooking Pot scroll changed during cooking");
            }
        }
        return super.observeMachineCraft(level, be);
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isSupportedBlockEntity(be)) return false;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return false;

        int expectedCount = expectedOutputCount();
        ItemStack output = itemHandler.getStackInSlot(outputSlot());
        if (!output.isEmpty()) return matchesRecipeOutput(output)
                && output.getCount() >= expectedCount;

        ItemStack declared = getDeclaredContainerItem(recipe);
        if (!declared.isEmpty()) return false;
        ItemStack inferred = getRequiredContainer(recipe, level.registryAccess());
        ItemStack storedContainer = itemHandler.getStackInSlot(containerSlot());
        ItemStack meal = itemHandler.getStackInSlot(mealDisplaySlot());
        return !inferred.isEmpty() && storedContainer.getCount() >= inferred.getCount()
                && ItemStack.isSameItem(inferred, storedContainer)
                && matchesRecipeOutput(meal) && meal.getCount() >= expectedCount;
    }

    @Override public OperationOutputSource outputSource() {
        return OperationOutputSource.single(this::takeResult, outputContract());
    }

    private ItemStack takeResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null) return ItemStack.EMPTY;

        ItemStack result = itemHandler.extractItem(outputSlot(), 64, false);
        if (result.isEmpty()) {
            ItemStack meal = itemHandler.getStackInSlot(mealDisplaySlot());
            ItemStack declared = getDeclaredContainerItem(recipe);
            ItemStack inferred = getRequiredContainer(recipe, myLevel.registryAccess());
            ItemStack storedContainer = itemHandler.getStackInSlot(containerSlot());
            if (declared.isEmpty() && !inferred.isEmpty() && storedContainer.getCount() >= inferred.getCount()
                    && ItemStack.isSameItem(inferred, storedContainer)
                    && matchesRecipeOutput(meal)) {
                result = itemHandler.extractItem(mealDisplaySlot(), meal.getCount(), false);
                itemHandler.extractItem(containerSlot(), inferred.getCount(), false);
            }
        }
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        recoverFailureInputs(myLevel);
        stopManagedCooking();
        forceChunkLoad(false);
        craftDone = false;
        plannedOperations = 1;
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        if (inputRecovery != null) recoverFailureInputs(null);
        stopManagedCooking();
    }

    @Override
    protected void clearUnavailableMachineState(@Nullable ServerPlayer player) {
        if (inputRecovery != null) recoverFailureInputs(null);
        stopManagedCooking();
    }

    @Override
    public void cleanup(@NotNull ServerPlayer player) {
        stopManagedCooking();
        forceChunkLoad(false);
        clearMachineSlotsAndRefund();
        if (inputRecovery != null) inputRecovery.release();
        inputRecovery = null;
        craftDone = false;
        plannedOperations = 1;
        if (placedScroll.isEmpty()) setStorageEndpoint(null);
    }

    private boolean prepareScrollSlot(ItemStack selectedScroll) {
        var slot = spellPot.rsi$scrollInventory();
        if (!placedScroll.isEmpty()) {
            // 后续批次沿用同一个 worker 已预留的卷轴。
            return ItemStack.matches(placedScroll, selectedScroll)
                    && ItemStack.matches(placedScroll, slot.getStackInSlot(0));
        }
        ItemStack previous = slot.getStackInSlot(0);
        if (!previous.isEmpty()) {
            ItemStack extracted = slot.extractItem(0, previous.getCount(), false);
            if (!extracted.isEmpty()) refundToRSNetwork(extracted);
        }
        return slot.getStackInSlot(0).isEmpty();
    }

    private boolean installScroll(ItemStack selectedScroll, Input scrollInput) {
        if (!placedScroll.isEmpty()) return true;
        var slot = spellPot.rsi$scrollInventory();
        if (!slot.getStackInSlot(0).isEmpty()
                || !placeInput(scrollInput, spellPotEntity, slot, 0, selectedScroll).isEmpty()) return false;
        scrollInput.release();
        placedScroll = selectedScroll.copy();
        return true;
    }

    private void stopManagedCooking() {
        if (spellPot != null) spellPot.rsi$setManagedCooking(false);
    }

    private boolean isSameSpellPotLoaded() {
        return spellPot != null && myLevel.hasChunkAt(myPos) && !spellPotEntity.isRemoved()
                && myLevel.getBlockEntity(myPos) == spellPotEntity;
    }

    private ItemStack takeOwnedScroll() {
        if (placedScroll.isEmpty() || !isSameSpellPotLoaded()) return ItemStack.EMPTY;
        var slot = spellPot.rsi$scrollInventory();
        if (!ItemStack.matches(placedScroll, slot.getStackInSlot(0))) return ItemStack.EMPTY;
        ItemStack recovered = slot.extractItem(0, placedScroll.getCount(), false);
        if (!recovered.isEmpty()) placedScroll = ItemStack.EMPTY;
        return recovered;
    }

    @Override
    public List<ItemStack> collectReusableMaterials(ServerPlayer player) {
        ItemStack scroll = takeOwnedScroll();
        return scroll.isEmpty() ? List.of() : List.of(scroll);
    }

    @Override
    public void releaseReusableMaterials(ServerPlayer player) {
        for (ItemStack scroll : collectReusableMaterials(player)) {
            ItemStack leftover = insertIntoStorage(player, scroll, false);
            if (!leftover.isEmpty()) ItemHandlerHelper.giveItemToPlayer(player, leftover);
        }
    }

    @Override
    public boolean transferReusableMaterialsTo(IBatchDelegate successor) {
        if (placedScroll.isEmpty()) return true;
        if (!(successor instanceof CookingPotBatchDelegate next) || !isSameSpellPotLoaded()
                || next.spellPotEntity != spellPotEntity || !next.placedScroll.isEmpty()) return false;
        next.placedScroll = placedScroll;
        placedScroll = ItemStack.EMPTY;
        return true;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack result = getExpectedRecipeResult(recipe,
                myLevel != null ? myLevel.registryAccess() : null);
        return result.isEmpty() ? null : new ExpectedProduction(result, expectedOutputCount());
    }

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

    private int expectedOutputCount() {
        ItemStack result = getExpectedRecipeResult(recipe,
                myLevel != null ? myLevel.registryAccess() : null);
        if (result.isEmpty()) return 0;
        long count = (long) result.getCount() * Math.max(1, plannedOperations);
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
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

    private static int slotCapacity(IItemHandler handler, int slot, ItemStack prototype) {
        return Math.max(1, Math.min(handler.getSlotLimit(slot), prototype.getMaxStackSize()));
    }

    /** 余留物风险判定；子类可覆写为恒定放行（例如余留物只写回原槽位的锅）。 */
    protected boolean hasIngredientRemainderRisk(BlockEntity machine, IngredientSpec spec) {
        if (spec == null || spec.ingredient() == null) return true;
        Class<?> blockEntityClass = ingredientRemainderOverridesClass(machine);
        if (blockEntityClass == null) return true;
        try {
            Object value = blockEntityClass.getField("INGREDIENT_REMAINDER_OVERRIDES").get(null);
            if (!(value instanceof Map<?, ?> map)) return true;
            ItemStack[] candidates = spec.ingredient().getItems();
            if (candidates.length == 0) return true;
            for (ItemStack candidate : candidates) {
                if (candidate == null || candidate.isEmpty()
                        || candidate.hasCraftingRemainingItem()
                        || map.containsKey(candidate.getItem())) return true;
            }
            return false;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return true;
        }
    }

    protected boolean inputBufferEnabled() {
        return RSIntegrationConfig.ENABLE_FARMERS_DELIGHT_COOKING_POT_INPUT_BUFFER.get();
    }

    /** 衍生锅可指定自己的材料返还规则，避免套用普通烹饪锅的返还表。 */
    @Nullable
    protected Class<?> ingredientRemainderOverridesClass(BlockEntity machine) {
        return FarmersDelightReflection.cookingPotBEClass;
    }

    protected int inputBufferLimit() {
        return Math.max(1, RSIntegrationConfig.FARMERS_DELIGHT_COOKING_POT_INPUT_BUFFER_LIMIT.get());
    }

    protected boolean supportsBufferedMachine(BlockEntity machine) {
        return FarmersDelightReflection.cookingPotBEClass != null
                && FarmersDelightReflection.cookingPotBEClass.isInstance(machine);
    }

    protected boolean supportsBufferedContainer(ItemStack required, ItemStack declared) {
        return required.isEmpty() || ItemStack.isSameItemSameTags(required, declared);
    }

    protected String outputPortId() {
        return "farmersdelight:cooking_pot:output";
    }

    // ── plan helpers ──

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Long> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Long> neededCounts,
                                       int repeatCount) {
        if (!"farmersdelight_cooking_pot".equals(recipeModTypeId)) return;
        int containerNeeded = repeatCount;
        // Container items are added per-craft, accounted via getContainerItem
    }

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        ItemStack container = getContainerItem(recipe, player.level().registryAccess());
        if (!container.isEmpty()) {
            warnings.add(Component.translatable("rsi.farmersdelight.container_needed",
                    container.getHoverName()));
        }
        ResourceLocation school = FarmersSpellPotSupport.requiredSchool(recipe);
        if (school != null) {
            warnings.add(Component.translatable("rsi.farmers_spell.scroll_required", school.toString()));
            warnings.add(Component.translatable("rsi.farmers_spell.heat_warning"));
            return warnings;
        }
        boolean arcaneCombination = false;
        if (pos != null) {
            ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
            if (level != null && level.hasChunkAt(pos) && level.hasChunkAt(pos.below())) {
                BlockEntity pot = level.getBlockEntity(pos);
                if (GoetyDelightCookingPotSupport.usesNecroticCooking(pot, recipe)) {
                    if (!GoetyDelightCookingPotSupport.canPowerNecroticCooking(pot)) {
                        warnings.add(Component.translatable("rsi.goetydelight.soul_required"));
                    }
                    return warnings;
                }
                BlockEntity stove = level.getBlockEntity(pos.below());
                arcaneCombination = ArcaneStoveSupport.isArcaneCookingPot(pot)
                        && ArcaneStoveSupport.isArcaneStove(stove);
            }
        }
        warnings.add(Component.translatable(arcaneCombination
                ? "rsi.farmersdelight.arcane_pot.plan_fuel"
                : "rsi.farmersdelight.heat_warning"));
        return warnings;
    }

    // ── reflection ──

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            inventoryField = FarmersDelightReflection.cookingPotBEClass.getDeclaredField("inventory");
            inventoryField.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Reflection probe failed", e);
        }
    }

    protected IItemHandler getInventory(BlockEntity be) {
        if (be instanceof FarmersSpellPotAccess pot) return pot.rsi$foodInventory();
        if (GoetyDelightCookingPotSupport.isCookingPot(be)) {
            return GoetyDelightCookingPotSupport.getInventory(be);
        }
        probeReflection();
        if (inventoryField != null) {
            try {
                return (IItemHandler) inventoryField.get(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] reflection probe failed", e); }
        }
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }

    @Nullable
    private BlockEntity arcaneStoveBelow() {
        if (!arcaneCookingPot) return null;
        BlockEntity stove = myLevel.getBlockEntity(myPos.below());
        return ArcaneStoveSupport.isArcaneStove(stove) ? stove : null;
    }

    private boolean checkCookingRequirement(ServerPlayer player, BlockEntity pot) {
        Component requirement = missingCookingRequirement(pot);
        if (requirement == null) return true;
        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] Missing cooking requirement at {}: {}",
                myPos, requirement);
        player.sendSystemMessage(requirement);
        return false;
    }

    @Nullable
    private Component missingCookingRequirement(@Nullable BlockEntity pot) {
        if (GoetyDelightCookingPotSupport.usesNecroticCooking(pot, recipe)) {
            return GoetyDelightCookingPotSupport.canPowerNecroticCooking(pot) ? null
                    : Component.translatable("rsi.goetydelight.soul_required");
        }
        return pot != null && isHeated(pot) ? null : Component.translatable("rsi.farmersdelight.no_heat");
    }

    protected boolean isHeated(BlockEntity be) {
        try {
            Method m = be.getClass().getMethod("isHeated");
            return (boolean) m.invoke(be);
        } catch (Exception e) {
            return false;
        }
    }

    private static ItemStack getDeclaredContainerItem(Recipe<?> recipe) {
        ItemStack declared = FarmersDelightRecipeHandler.getOutputContainer(recipe);
        if (!declared.isEmpty()) return declared;
        // Fallback: the recipe declares no explicit container, but the meal item
        // may itself be a bowl/cup food (getCraftingRemainingItem() = the empty
        // bowl). FD's moveMealToOutput() refuses to move the meal to the output
        // slot when doesMealHaveContainer() is true (which includes
        // meal.hasCraftingRemainingItem()), so without supplying that container
        // the meal stays stuck in the display slot and never reaches OUTPUT_SLOT
        // — the product is silently never recovered into RS. Derive the required
        // container from the result item's crafting remainder.
        return ItemStack.EMPTY;
    }

    public static ItemStack getContainerItem(Recipe<?> recipe, @Nullable RegistryAccess access) {
        ItemStack declared = getDeclaredContainerItem(recipe);
        if (!declared.isEmpty()) return declared;
        try {
            ItemStack result = getRecipeResult(recipe, access);
            if (!result.isEmpty() && result.hasCraftingRemainingItem()) {
                ItemStack rem = result.getCraftingRemainingItem();
                if (!rem.isEmpty()) return rem.copyWithCount(Math.max(1, result.getCount()));
            }
        } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] meal-container fallback failed", e); }
        return ItemStack.EMPTY;
    }

    private boolean matchesRecipeOutput(ItemStack stack) {
        ItemStack expected = getExpectedRecipeResult(recipe,
                myLevel != null ? myLevel.registryAccess() : null);
        return goetyDelightCookingPot
                ? GoetyDelightCookingPotSupport.matchesOutputIgnoringNbt(stack, expected)
                : matchesRecipeOutputStack(stack, expected, arcaneCookingPot);
    }

    static boolean matchesRecipeOutputStack(ItemStack stack, ItemStack expected,
                                            boolean arcaneCookingPot) {
        if (stack.isEmpty() || expected.isEmpty()
                || !ItemStack.isSameItem(stack, expected)
                || stack.getCount() < expected.getCount()) return false;
        if (ItemStack.isSameItemSameTags(stack, expected)) return true;
        if (!arcaneCookingPot) return false;

        ItemStack normalized = stack.copy();
        if (normalized.hasTag()) {
            normalized.getTag().remove("IronsSpellsDelightArcaneCooked");
            if (normalized.getTag().isEmpty()) normalized.setTag(null);
        }
        return ItemStack.isSameItemSameTags(normalized, expected);
    }

    /** SRG-safe recipe result using the active level's registry access. */
    protected static ItemStack getRecipeResult(Recipe<?> recipe, @Nullable RegistryAccess access) {
        if (recipe instanceof CraftingRecipe) return ItemStack.EMPTY;
        try {
            return ModRecipeHandlers.tryGetResultItem(recipe, access);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ── cleanup ──

    private void recoverFailureInputs(@Nullable ServerLevel level) {
        List<ItemStack> recovered = new ArrayList<>();
        if (inputRecovery != null) {
            recovered.addAll(inputRecovery.recover(level));
            inputRecovery = null;
        }
        ItemStack scroll = level == null ? ItemStack.EMPTY : takeOwnedScroll();
        if (!scroll.isEmpty()) recovered.add(scroll);
        recordFailureRecoveredInputs(recovered);
        if (!usingSharedLedger) recovered.forEach(this::refundToRSNetwork);
    }

    private void clearMachineSlotsAndRefund() {
        if (!myLevel.hasChunkAt(myPos)) return;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return;
        if (!isSupportedBlockEntity(be)) return;

        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < inventorySize()) return;

        for (int slot = 0; slot < inputSlots(); slot++) {
            ItemStack s = handler.extractItem(slot, 64, false);
            if (!s.isEmpty() && !usingSharedLedger) refundToRSNetwork(s);
        }
        ItemStack meal = handler.extractItem(mealDisplaySlot(), 64, false);
        if (!meal.isEmpty()) refundToRSNetwork(meal);
        // 共享账本负责结算容器，委托不能重复返还。
        ItemStack container = handler.extractItem(containerSlot(), 64, false);
        if (!container.isEmpty() && !usingSharedLedger) refundToRSNetwork(container);
        ItemStack out = handler.extractItem(outputSlot(), 64, false);
        // Output is not part of the shared input ledger. If collection races with
        // cleanup, never discard it merely because this delegate used that ledger.
        if (!out.isEmpty()) refundToRSNetwork(out);
        be.setChanged();
    }

    /** Drain stale machine contents before a new operation owns the slots. */
    private boolean drainExistingContentsToNetwork(IItemHandler handler) {
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
            if (!leftover.isEmpty()) {
                if (player != null) ItemHandlerHelper.giveItemToPlayer(player, leftover);
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Batch-CookingPot] RS rejected {} stale item(s) from {}",
                        leftover.getCount(), myPos);
            }
            if (!handler.getStackInSlot(slot).isEmpty()) drained = false;
        }
        if (changed) {
            BlockEntity be = myLevel.getBlockEntity(myPos);
            if (be != null) be.setChanged();
        }
        return drained;
    }

    private void refundToRSNetwork(ItemStack stack) {
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (!leftover.isEmpty() && player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
        }
    }

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }

    protected boolean isSupportedRecipe(Recipe<?> candidate) {
        return GoetyDelightCookingPotSupport.isWorkstationRecipe(candidate)
                || FarmersDelightReflection.cookingPotRecipeClass != null
                && FarmersDelightReflection.cookingPotRecipeClass.isInstance(candidate);
    }

    protected boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        return blockEntity instanceof FarmersSpellPotAccess || GoetyDelightCookingPotSupport.isCookingPot(blockEntity)
                || FarmersDelightReflection.cookingPotBEClass != null
                && FarmersDelightReflection.cookingPotBEClass.isInstance(blockEntity);
    }

    protected int inputSlots() { return INPUT_SLOTS; }
    protected int mealDisplaySlot() { return MEAL_DISPLAY_SLOT; }
    protected int containerSlot() { return CONTAINER_SLOT; }
    protected int outputSlot() { return OUTPUT_SLOT; }
    protected int inventorySize() { return OUTPUT_SLOT + 1; }

    protected ItemStack getRequiredContainer(Recipe<?> recipe, @Nullable RegistryAccess access) {
        return getContainerItem(recipe, access);
    }

    protected ItemStack getExpectedRecipeResult(Recipe<?> recipe, @Nullable RegistryAccess access) {
        return getRecipeResult(recipe, access);
    }
}
