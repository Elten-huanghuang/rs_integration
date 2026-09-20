package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.InputBufferContract;
import com.huanghuang.rsintegration.crafting.batch.InputBufferPlan;
import com.huanghuang.rsintegration.crafting.batch.OutputAccounting;
import com.huanghuang.rsintegration.crafting.batch.OutputContract;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.recipe.FarmersDelightRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.reflection.probes.FarmersDelightReflection;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
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
    private Recipe<?> recipe;
    private boolean craftDone;
    private boolean arcaneCookingPot;
    private int plannedOperations = 1;
    private final List<ItemStack> placedInputs = new ArrayList<>();
    private final List<ItemStack> detachedRecoveredInputs = new ArrayList<>();
    private ItemStack placedContainer = ItemStack.EMPTY;

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
        if (FarmersDelightReflection.cookingPotRecipeClass == null || !FarmersDelightReflection.cookingPotRecipeClass.isInstance(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.arcaneCookingPot = false;
        this.plannedOperations = 1;
        this.placedInputs.clear();
        this.detachedRecoveredInputs.clear();
        this.placedContainer = ItemStack.EMPTY;
        BlockEntity existing = level.getBlockEntity(pos);
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

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
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
        } else if (declared.isEmpty() && !container.isEmpty()) {
            specs.add(new IngredientSpec(Ingredient.of(container.copyWithCount(1)),
                    container.getCount()));
        }
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public List<IngredientSpec> getGraphSpecs() {
        // The serving container is a real per-operation input. Keep it in the
        // graph demand so a missing bowl blocks planning instead of producing a
        // retry loop in supplemental reservation.
        List<IngredientSpec> specs = getRequiredMaterials();
        return specs != null ? specs : List.of();
    }

    @Override
    public List<IngredientSpec> getSupplementalSpecs() {
        return List.of();
    }

    @Nullable
    private List<IngredientSpec> getRecipeIngredientSpecs() {
        var handler = ModRecipeHandlers.handlerFor(recipe);
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
                ? Math.max(Math.max(1, configuredLimit), inputBufferLimit())
                : Math.max(1, configuredLimit);
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return supportsInputBuffer();
    }

    @Override
    public boolean supportsInputBuffer() {
        if (!inputBufferEnabled() || arcaneCookingPot || recipe == null
                || myLevel == null || myPos == null || !myLevel.hasChunkAt(myPos)) return false;
        BlockEntity machine = myLevel.getBlockEntity(myPos);
        if (machine == null || !supportsBufferedMachine(machine)) return false;
        List<IngredientSpec> specs = getRequiredMaterials();
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
            if (i < ingredientCount && hasIngredientRemainderRisk(spec)) return false;
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
        List<IngredientSpec> specs = getRequiredMaterials();
        ItemStack container = getRequiredContainer(recipe, myLevel.registryAccess());
        int ingredientCount = specs.size() - (container.isEmpty() ? 0 : 1);
        List<InputBufferContract.InputSlot> inputs = new ArrayList<>(specs.size());
        for (int i = 0; i < ingredientCount; i++) {
            IngredientSpec spec = specs.get(i);
            ItemStack prototype = ingredientPrototype(spec);
            inputs.add(new InputBufferContract.InputSlot(
                    "legacy:material:" + i, i, prototype, spec.count(), false,
                    slotCapacity(handler, i, prototype)));
        }
        if (!container.isEmpty()) {
            IngredientSpec spec = specs.get(specs.size() - 1);
            ItemStack prototype = ingredientPrototype(spec);
            inputs.add(new InputBufferContract.InputSlot(
                    "legacy:material:" + (specs.size() - 1), containerSlot(), prototype,
                    spec.count(), false, slotCapacity(handler, containerSlot(), prototype)));
        }
        ItemStack output = getExpectedRecipeResult(recipe, myLevel.registryAccess());
        int outputCapacity = slotCapacity(handler, outputSlot(), output);
        int operations = bufferedOperationCapacity(inputBufferLimit(), inputs,
                output.getCount(), outputCapacity);
        if (operations <= 0) return InputBufferContract.none();
        return new InputBufferContract(operations, inputs,
                List.of(new OutputContract.Port(
                        outputPortId(), outputSlot(), output,
                        output.getCount(), InputBufferPlan.OutputPort.Kind.PRIMARY,
                        OutputContract.Source.SLOT)));
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
        if (plan.inputs().size() != specs.size()) return false;
        List<ItemStack> ordered = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            InputBufferPlan.InputSlot input = inputByEntryId(plan, "legacy:material:" + i);
            IngredientSpec spec = specs.get(i);
            int expectedSlot = i == specs.size() - 1
                    && !getRequiredContainer(recipe, myLevel.registryAccess()).isEmpty()
                    ? containerSlot() : i;
            long required = (long) spec.count() * plan.operations();
            if (input == null || input.slot() != expectedSlot || input.reusable()
                    || input.perOperation() != spec.count() || required > Integer.MAX_VALUE
                    || input.stack().getCount() != (int) required
                    || !spec.ingredient().test(input.stack())) return false;
            ordered.add(input.stack().copy());
        }
        InputBufferPlan expected = inputBufferPlan(plan.operations());
        if (!expected.enabled() || expected.operations() != plan.operations()) return false;
        plannedOperations = plan.operations();
        return tryStartWithMaterials(player, ordered, sharedLedger);
    }

    @Override
    public OutputContract outputContract() {
        if (!supportsInputBuffer()) return OutputContract.none();
        ItemStack output = getExpectedRecipeResult(recipe, myLevel.registryAccess());
        return output.isEmpty() ? OutputContract.none() : new OutputContract(List.of(
                new OutputContract.Port(outputPortId(), outputSlot(),
                        output, output.getCount(), InputBufferPlan.OutputPort.Kind.PRIMARY,
                        OutputContract.Source.SLOT)));
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
            if (!tryStartWithMaterials(player, materials, ledger)) {
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
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.usingSharedLedger = true;
        this.craftDone = false;

        if (!myLevel.hasChunkAt(myPos)) return false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] BlockEntity missing at {}", myPos);
            return false;
        }
        if (!isSupportedBlockEntity(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Wrong BE type: {}", be.getClass().getName());
            return false;
        }

        IItemHandler itemHandler = getInventory(be);
        if (itemHandler == null || itemHandler.getSlots() < inventorySize()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Cannot access item handler");
            return false;
        }

        // A stale/partially completed pot must not block the scheduler. Drain
        // every slot before reserving this operation's inputs; the old contents
        // are not owned by the new shared ledger.
        if (storageEndpoint() == null || !drainExistingContentsToNetwork(itemHandler)) return false;
        placedInputs.clear();
        detachedRecoveredInputs.clear();
        placedContainer = ItemStack.EMPTY;

        forceChunkLoad(true);

        // The bound machine is the pot, but Arcane Stove fuel state lives one
        // block below it. Invoke the stove's native interaction before checking heat.
        BlockEntity heatSource = myLevel.getBlockEntity(myPos.below());
        if (arcaneCookingPot && ArcaneStoveSupport.isArcaneStove(heatSource)
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

        // Ordinary cooking pots still use their original heat-source behavior.
        if (!isHeated(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] No heat source under cooking pot at {}", myPos);
            player.sendSystemMessage(Component.translatable("rsi.farmersdelight.no_heat"));
            return false;
        }

        ItemStack requiredContainer = getRequiredContainer(recipe, myLevel.registryAccess());
        List<ItemStack> inputMaterials = new ArrayList<>();
        ItemStack containerMaterial = ItemStack.EMPTY;
        int inputEnd = materials.size();
        if (!requiredContainer.isEmpty()) {
            if (materials.isEmpty()) return false;
            ItemStack suppliedContainer = materials.get(materials.size() - 1);
            if (suppliedContainer.isEmpty()
                    || !ItemStack.isSameItemSameTags(suppliedContainer, requiredContainer)
                    || suppliedContainer.getCount() < requiredContainer.getCount()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Planned container missing for recipe {}: {}",
                        recipe.getId(), requiredContainer);
                return false;
            }
            long requiredCount = (long) requiredContainer.getCount() * plannedOperations;
            if (requiredCount > Integer.MAX_VALUE || suppliedContainer.getCount() != (int) requiredCount) {
                return false;
            }
            containerMaterial = suppliedContainer.copy();
            inputEnd--;
        }
        for (int i = 0; i < inputEnd; i++) {
            ItemStack material = materials.get(i);
            if (!material.isEmpty()) inputMaterials.add(material);
        }

        long materialCount = inputMaterials.stream().filter(s -> !s.isEmpty()).count();
        if (materialCount > inputSlots()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Recipe {} has {} ingredients but only {} input slots",
                    recipe.getId(), materialCount, inputSlots());
            forceChunkLoad(false);
            return false;
        }

        List<IngredientSpec> requiredSpecs = getRequiredMaterials();
        int requiredInputCount = requiredSpecs == null ? 0
                : requiredSpecs.size() - (requiredContainer.isEmpty() ? 0 : 1);
        if (requiredInputCount != inputMaterials.size()) return false;

        // Insert one stack per recipe ingredient. The native pot consumes one
        // item from every occupied input slot after each completed cycle.
        int slot = 0;
        for (ItemStack mat : inputMaterials) {
            if (mat.isEmpty()) continue;
            IngredientSpec spec = requiredSpecs.get(slot);
            long requiredCount = (long) spec.count() * plannedOperations;
            if (requiredCount > Integer.MAX_VALUE || mat.getCount() != (int) requiredCount
                    || !spec.ingredient().test(mat)) return false;
            ItemStack placed = mat.copy();
            ItemStack remainder = itemHandler.insertItem(slot, placed, false);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Failed to insert into slot {}: {}",
                        slot, remainder.getHoverName().getString());
                for (int back = 0; back < slot; back++) {
                    ItemStack refund = itemHandler.extractItem(back, 64, false);
                    if (!refund.isEmpty()) {
                        detachedRecoveredInputs.add(refund.copy());
                        if (!usingSharedLedger) insertIntoStorage(player, refund, false);
                    }
                }
                be.setChanged();
                return false;
            }
            placedInputs.add(placed.copy());
            slot++;
        }
        be.setChanged();

        // Insert the container that was reserved by the same shared ledger as the
        // ingredients. Never extract it from RS out-of-band after commit.
        if (!requiredContainer.isEmpty()) {
            ItemStack existingContainer = itemHandler.getStackInSlot(containerSlot());
            if (!existingContainer.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Batch-CookingPot] Container slot occupied at {}", myPos);
                rollbackInputs(itemHandler, slot);
                be.setChanged();
                return false;
            }
            ItemStack simulated = itemHandler.insertItem(containerSlot(), containerMaterial, true);
            if (!simulated.isEmpty()) {
                rollbackInputs(itemHandler, slot);
                be.setChanged();
                return false;
            }
            ItemStack remainder = itemHandler.insertItem(containerSlot(), containerMaterial, false);
            if (!remainder.isEmpty()) {
                rollbackInputs(itemHandler, slot);
                be.setChanged();
                return false;
            }
            be.setChanged();
            placedContainer = containerMaterial.copy();
        }

        RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] Materials inserted, cooking should start next tick");
        markCraftStarted();
        return true;
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
        return !inferred.isEmpty()
                && ItemStack.isSameItemSameTags(inferred, storedContainer)
                && matchesRecipeOutput(meal) && meal.getCount() >= expectedCount;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
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
            if (declared.isEmpty() && !inferred.isEmpty()
                    && ItemStack.isSameItemSameTags(inferred, storedContainer)
                    && matchesRecipeOutput(meal)) {
                result = itemHandler.extractItem(mealDisplaySlot(), meal.getCount(), false);
                itemHandler.extractItem(containerSlot(), 1, false);
            }
        }
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    public List<OutputAccounting.CollectedOutput> collectStructuredResults(ServerPlayer player) {
        OutputContract contract = outputContract();
        if (contract.ports().size() != 1) return List.of();
        ItemStack result = collectResult(player);
        if (result.isEmpty()) return List.of();
        OutputContract.Port port = contract.ports().get(0);
        return List.of(new OutputAccounting.CollectedOutput(port.portId(), port.source(), result));
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        List<ItemStack> recovered = recoverFailureInputs(be);
        recordFailureRecoveredInputs(recovered);
        forceChunkLoad(false);
        craftDone = false;
        plannedOperations = 1;
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        forceChunkLoad(false);
        clearMachineSlotsAndRefund();
        craftDone = false;
        plannedOperations = 1;
        network = null;
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
        return java.util.Arrays.stream(spec.ingredient().getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    private static int slotCapacity(IItemHandler handler, int slot, ItemStack prototype) {
        return Math.max(1, Math.min(handler.getSlotLimit(slot), prototype.getMaxStackSize()));
    }

    private static boolean hasIngredientRemainderRisk(IngredientSpec spec) {
        if (spec == null || spec.ingredient() == null) return true;
        Class<?> blockEntityClass = FarmersDelightReflection.cookingPotBEClass;
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
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
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
        boolean arcaneCombination = false;
        if (pos != null) {
            ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
            if (level != null && level.hasChunkAt(pos) && level.hasChunkAt(pos.below())) {
                BlockEntity pot = level.getBlockEntity(pos);
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
        probeReflection();
        if (inventoryField != null) {
            try {
                return (IItemHandler) inventoryField.get(be);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Batch-CookingPot] reflection probe failed", e); }
        }
        return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
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
        return matchesRecipeOutputStack(stack, expected, arcaneCookingPot);
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
        if (recipe instanceof net.minecraft.world.item.crafting.CraftingRecipe) return ItemStack.EMPTY;
        try {
            return ModRecipeHandlers.tryGetResultItem(recipe, access);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    // ── cleanup ──

    private void rollbackInputs(IItemHandler handler, int insertedSlots) {
        for (int back = 0; back < insertedSlots; back++) {
            ItemStack refund = handler.extractItem(back, 64, false);
            if (!refund.isEmpty()) {
                detachedRecoveredInputs.add(refund.copy());
                if (!usingSharedLedger) refundToRSNetwork(refund);
            }
        }
    }

    private List<ItemStack> recoverFailureInputs(BlockEntity be) {
        List<ItemStack> recovered = new ArrayList<>(detachedRecoveredInputs);
        detachedRecoveredInputs.clear();
        if (!isSupportedBlockEntity(be)) return recovered;
        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < inventorySize()) return recovered;
        for (int slot = 0; slot < placedInputs.size() && slot < inputSlots(); slot++) {
            ItemStack expected = placedInputs.get(slot);
            ItemStack visible = handler.getStackInSlot(slot);
            if (visible.isEmpty() || !ItemStack.isSameItemSameTags(visible, expected)) continue;
            ItemStack removed = handler.extractItem(slot,
                    Math.min(visible.getCount(), expected.getCount()), false);
            if (!removed.isEmpty()) {
                recovered.add(removed.copy());
                if (!usingSharedLedger) refundToRSNetwork(removed);
            }
        }
        if (!placedContainer.isEmpty()) {
            ItemStack visible = handler.getStackInSlot(containerSlot());
            if (!visible.isEmpty() && ItemStack.isSameItemSameTags(visible, placedContainer)) {
                ItemStack removed = handler.extractItem(containerSlot(),
                        Math.min(visible.getCount(), placedContainer.getCount()), false);
                if (!removed.isEmpty()) {
                    recovered.add(removed.copy());
                    if (!usingSharedLedger) refundToRSNetwork(removed);
                }
            }
        }
        placedInputs.clear();
        placedContainer = ItemStack.EMPTY;
        be.setChanged();
        return recovered;
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
        // Container was reserved via the shared ledger (separated from materials in
        // tryStartWithMaterials L204-216). When usingSharedLedger=true, the ledger's
        // refundCommitted() will restore it; delegate must not double-refund.
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

    protected boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        return FarmersDelightReflection.cookingPotBEClass.isInstance(blockEntity);
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
