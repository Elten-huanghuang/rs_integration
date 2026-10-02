package com.huanghuang.rsintegration.mods.youkaishomecoming.moka;

import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;

import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import java.util.Arrays;
import java.lang.reflect.Modifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import com.huanghuang.rsintegration.RSIntegrationMod;
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
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.mods.common.IdleInventoryEvacuator;
import com.huanghuang.rsintegration.mods.youkaishomecoming.YoukaisHomecomingRecipeHandler;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Batch delegate for Youkais Homecoming Moka Pot. */
public final class MokaPotBatchDelegate extends AbstractBatchDelegate {

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.machineSlot();
    }

    // BasePotBlockEntity layout (INVENTORY_SIZE = 7):
    //   0-3: input ingredients  4: meal display
    //   5: CONTAINER_SLOT       6: OUTPUT_SLOT
    private static final int INPUT_SLOTS = 4;
    private static final int MEAL_DISPLAY_SLOT = 4;
    private static final int CONTAINER_SLOT = 5;
    private static final int OUTPUT_SLOT = 6;
    private static final int INVENTORY_SIZE = 7;

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;
    private int plannedOperations = 1;

    private static volatile Method inventoryMethod;
    private static volatile Method isHeatedMethod;
    private static volatile Method addItemMethod;
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

        Recipe<?> found = level.getRecipeManager().byKey(recipeId).orElse(null);
        if (found == null || YHKReflection.mokaRecipeClass == null || !YHKReflection.mokaRecipeClass.isInstance(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.plannedOperations = 1;
        BlockEntity existing = level.getBlockEntity(pos);
        IItemHandler existingInventory = existing != null && isMokaBE(existing)
                ? getInventory(existing) : null;
        if (existingInventory == null || existingInventory.getSlots() <= OUTPUT_SLOT) return false;
        return getCookTime(existing) <= 0;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        ItemStack container = getOutputContainer(recipe);
        if (!container.isEmpty()) {
            specs.add(new IngredientSpec(Ingredient.of(container.copyWithCount(1)),
                    container.getCount()));
        }
        return specs.isEmpty() ? null : specs;
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
                ? Math.max(Math.max(1, configuredLimit), mokaInputBufferLimit())
                : Math.max(1, configuredLimit);
    }

    @Override
    public boolean expandsFlatBatchOperationLimit() {
        return supportsInputBuffer();
    }

    @Override
    public boolean supportsInputBuffer() {
        if (!mokaInputBufferEnabled() || recipe == null || myLevel == null || myPos == null
                || !myLevel.hasChunkAt(myPos)) return false;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isMokaBE(be)) return false;
        List<IngredientSpec> specs = getRequiredMaterials();
        ItemStack container = getOutputContainer(recipe);
        int ingredientCount = specs == null ? 0 : specs.size() - (container.isEmpty() ? 0 : 1);
        if (ingredientCount <= 0 || ingredientCount > INPUT_SLOTS) return false;
        for (int i = 0; i < ingredientCount; i++) {
            IngredientSpec spec = specs.get(i);
            if (spec == null || spec.isEmpty() || spec.role() != DemandRole.CONSUMED
                    || spec.count() <= 0 || ingredientPrototype(spec).isEmpty()
                    || hasCraftingRemainder(spec)) return false;
        }
        return !expectedResult().isEmpty();
    }

    @Override
    public InputBufferContract inputBufferContract() {
        if (!supportsInputBuffer()) return InputBufferContract.none();
        BlockEntity be = myLevel.getBlockEntity(myPos);
        IItemHandler handler = be == null ? null : getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) return InputBufferContract.none();
        List<IngredientSpec> specs = getRequiredMaterials();
        ItemStack container = getOutputContainer(recipe);
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
            int index = specs.size() - 1;
            IngredientSpec spec = specs.get(index);
            ItemStack prototype = ingredientPrototype(spec);
            inputs.add(new InputBufferContract.InputSlot(
                    "legacy:material:" + index, CONTAINER_SLOT, prototype,
                    spec.count(), false, slotCapacity(handler, CONTAINER_SLOT, prototype)));
        }
        ItemStack output = expectedResult();
        int operations = bufferedOperationCapacity(mokaInputBufferLimit(), inputs,
                output.getCount(), slotCapacity(handler, OUTPUT_SLOT, output));
        if (operations <= 0) return InputBufferContract.none();
        return new InputBufferContract(operations, inputs,
                List.of(new OutputContract.Port("youkaishomecoming:moka:output",
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
        ItemStack container = getOutputContainer(recipe);
        if (specs == null || plan.inputs().size() != specs.size()) return false;
        List<ItemStack> ordered = new ArrayList<>(specs.size());
        for (int i = 0; i < specs.size(); i++) {
            InputBufferPlan.InputSlot input = inputById(plan, "legacy:material:" + i);
            IngredientSpec spec = specs.get(i);
            int expectedSlot = !container.isEmpty() && i == specs.size() - 1
                    ? CONTAINER_SLOT : i;
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
        ItemStack output = expectedResult();
        return output.isEmpty() ? OutputContract.none() : new OutputContract(List.of(
                new OutputContract.Port("youkaishomecoming:moka:output", OUTPUT_SLOT,
                        output, output.getCount(), InputBufferPlan.OutputPort.Kind.PRIMARY,
                        OutputContract.Source.SLOT)));
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = new ExtractionLedger()) {
            if (storageEndpoint() == null) this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network == null && !hasStorageAccess()) return false;
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
        this.sharedLedger = sharedLedger;
        if (sharedLedger.storageEndpoint() != null) setStorageEndpoint(sharedLedger.storageEndpoint());
        this.usingSharedLedger = true;
        this.craftDone = false;

        forceChunkLoad(true);
        if (!myLevel.hasChunkAt(myPos)) return false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isMokaBE(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Moka] BE not found or not MokaBE at {} class={}",
                    myPos, be != null ? be.getClass().getName() : "null");
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        if (!isHeated(be)) {
            RSIntegrationMod.LOGGER.debug("[RSI-Moka] Not heated at {}", myPos);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.no_heat"));
            forceChunkLoad(false);
            return false;
        }

        if (!hasWater() && !MachineWaterSupply.fillProperty("youkaishomecoming_moka", 1000,
                storageEndpoint(), player, () -> setWaterProperty(myLevel.getBlockState(myPos), true))) {
            forceChunkLoad(false);
            return false;
        }

        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) {
            RSIntegrationMod.LOGGER.warn("[RSI-Moka] Inventory not found or too small: handler={} slots={}",
                    handler != null ? handler.getClass().getName() : "null",
                    handler != null ? handler.getSlots() : -1);
            forceChunkLoad(false);
            return false;
        }
        if (!hasStorageAccess()) {
            forceChunkLoad(false);
            return false;
        }
        int evacuated = evacuateIdleInventory(handler, getCookTime(be), this::refund);
        if (evacuated < 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-Moka] Pot is busy or could not be cleared at {}", myPos);
            forceChunkLoad(false);
            return false;
        }
        if (evacuated > 0) be.setChanged();

        List<IngredientSpec> specs = getRequiredMaterials();
        ItemStack container = getOutputContainer(recipe);
        int ingredientCount = specs == null ? 0 : specs.size() - (container.isEmpty() ? 0 : 1);
        if (specs == null || materials.size() != specs.size() || ingredientCount <= 0) return false;

        // The output container is part of the same graph/storage transaction as
        // the recipe ingredients. This keeps RS and BD accounting identical.
        if (!container.isEmpty()) {
            ItemStack supplied = materials.get(materials.size() - 1);
            IngredientSpec spec = specs.get(specs.size() - 1);
            long required = (long) spec.count() * plannedOperations;
            if (required > Integer.MAX_VALUE || supplied.getCount() != (int) required
                    || !spec.ingredient().test(supplied)
                    || !handler.getStackInSlot(CONTAINER_SLOT).isEmpty()
                    || !handler.insertItem(CONTAINER_SLOT, supplied.copy(), true).isEmpty()) {
                return false;
            }
            ItemStack remainder = handler.insertItem(CONTAINER_SLOT, supplied.copy(), false);
            if (!remainder.isEmpty()) {
                rollbackPlacedInputs(handler, 0, true);
                return false;
            }
        }

        // Use one stable physical slot per recipe entry. This also keeps recipes
        // containing duplicate item ingredients unambiguous across queued cycles.
        for (int i = 0; i < ingredientCount; i++) {
            ItemStack mat = materials.get(i);
            IngredientSpec spec = specs.get(i);
            long required = (long) spec.count() * plannedOperations;
            if (mat.isEmpty() || required > Integer.MAX_VALUE
                    || mat.getCount() != (int) required || !spec.ingredient().test(mat)) {
                rollbackPlacedInputs(handler, i, !container.isEmpty());
                return false;
            }
            ItemStack remainder = handler.insertItem(i, mat.copy(), false);
            if (!remainder.isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Moka] addItem rejected {} -- remainder={}",
                        mat.getHoverName().getString(), remainder.getHoverName().getString());
                rollbackPlacedInputs(handler, i + 1, !container.isEmpty());
                be.setChanged();
                forceChunkLoad(false);
                return false;
            }
        }
        be.setChanged();
        markCraftStarted();
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isMokaBE(be)) return false;

        IItemHandler handler = getInventory(be);
        if (handler == null) return false;

        ItemStack output = handler.getStackInSlot(OUTPUT_SLOT);
        return matchesExpectedOutput(output) && output.getCount() >= expectedOutputCount();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IItemHandler handler = getInventory(be);
        if (handler == null) return ItemStack.EMPTY;

        ItemStack result = handler.extractItem(OUTPUT_SLOT, 64, false);
        be.setChanged();
        craftDone = true;
        return result;
    }

    @Override
    public List<OutputAccounting.CollectedOutput> collectStructuredResults(ServerPlayer player) {
        ItemStack result = collectResult(player);
        return result.isEmpty() ? List.of() : List.of(new OutputAccounting.CollectedOutput(
                "youkaishomecoming:moka:output", OutputContract.Source.SLOT, result));
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        recordFailureRecoveredInputs(clearAndRefund());
        forceChunkLoad(false);
        craftDone = false;
        plannedOperations = 1;
        network = null;
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        forceChunkLoad(false);
        clearAndRefund();
        craftDone = false;
        plannedOperations = 1;
        network = null;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        ItemStack result = expectedResult();
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

    private ItemStack expectedResult() {
        return recipe == null || myLevel == null ? ItemStack.EMPTY
                : ModRecipeHandlers.tryGetResultItem(recipe, myLevel.registryAccess());
    }

    private int expectedOutputCount() {
        ItemStack result = expectedResult();
        if (result.isEmpty()) return 0;
        long count = (long) result.getCount() * Math.max(1, plannedOperations);
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private boolean matchesExpectedOutput(ItemStack stack) {
        ItemStack expected = expectedResult();
        return !stack.isEmpty() && !expected.isEmpty()
                && ItemStack.isSameItemSameTags(stack, expected);
    }

    private static ItemStack ingredientPrototype(IngredientSpec spec) {
        if (spec == null || spec.ingredient() == null) return ItemStack.EMPTY;
        return Arrays.stream(spec.ingredient().getItems())
                .filter(stack -> stack != null && !stack.isEmpty())
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
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

    private static int slotCapacity(IItemHandler handler, int slot, ItemStack prototype) {
        return Math.max(1, Math.min(handler.getSlotLimit(slot), prototype.getMaxStackSize()));
    }

    @Nullable
    private static InputBufferPlan.InputSlot inputById(InputBufferPlan plan, String id) {
        return plan.inputs().stream().filter(input -> id.equals(input.entryId()))
                .findFirst().orElse(null);
    }

    private static void rollbackPlacedInputs(IItemHandler handler, int insertedInputs,
                                             boolean containerPlaced) {
        for (int slot = 0; slot < insertedInputs; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.isEmpty()) handler.extractItem(slot, stack.getCount(), false);
        }
        if (containerPlaced) {
            ItemStack stack = handler.getStackInSlot(CONTAINER_SLOT);
            if (!stack.isEmpty()) handler.extractItem(CONTAINER_SLOT, stack.getCount(), false);
        }
    }

    private static boolean mokaInputBufferEnabled() {
        return RSIntegrationConfig.ENABLE_MOKA_POT_INPUT_BUFFER.get();
    }

    private static int mokaInputBufferLimit() {
        return Math.max(1, RSIntegrationConfig.MOKA_POT_INPUT_BUFFER_LIMIT.get());
    }

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
        ItemStack container = getOutputContainer(recipe);
        if (!container.isEmpty()) {
            warnings.add(Component.translatable("rsi.farmersdelight.container_needed",
                    container.getHoverName()));
        }
        warnings.add(Component.translatable("rsi.youkaishomecoming.heat_warning"));
        return warnings;
    }

    // -- reflection (one-time probe) --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.mokaMakerBEClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Moka] YHKReflection moka probe not ready");
            return;
        }
        try {
            // try getMethod first (finds public inherited), then declared walk
            try {
                inventoryMethod = YHKReflection.mokaMakerBEClass.getMethod("getInventory");
            } catch (NoSuchMethodException e) {
                inventoryMethod = findMethodInHierarchy(YHKReflection.mokaMakerBEClass, "getInventory");
            }
            if (inventoryMethod != null) inventoryMethod.setAccessible(true);

            // isHeated may be on the BE class itself or a parent class/interface.
            // Try multiple signatures: no-arg first, then (Level, BlockPos).
            isHeatedMethod = findMethodInHierarchy(YHKReflection.mokaMakerBEClass, "isHeated");
            if (isHeatedMethod == null) {
                isHeatedMethod = findMethodInHierarchy(YHKReflection.mokaMakerBEClass,
                        "isHeated", Level.class, BlockPos.class);
            }
            if (isHeatedMethod == null) {
                // Last resort: scan for any method named isHeated
                for (Method m : YHKReflection.mokaMakerBEClass.getMethods()) {
                    if (m.getName().equals("isHeated")) {
                        isHeatedMethod = m;
                        break;
                    }
                }
            }
            if (isHeatedMethod != null) {
                isHeatedMethod.setAccessible(true);
            } else {
                RSIntegrationMod.LOGGER.warn("[RSI-Moka] isHeated method not found on {} hierarchy",
                        YHKReflection.mokaMakerBEClass.getName());
            }

            // addItem() is on BasePotBlockEntity
            addItemMethod = findMethodInHierarchy(YHKReflection.mokaMakerBEClass, "addItem", ItemStack.class);
            if (addItemMethod != null) addItemMethod.setAccessible(true);

            // WATER property on MokaMakerBlock
            if (YHKReflection.mokaMakerBlockClass != null) {
                try {
                    waterPropertyField = findFieldInHierarchy(YHKReflection.mokaMakerBlockClass, "WATER");
                    if (waterPropertyField != null) waterPropertyField.setAccessible(true);
                } catch (Exception e) {
                    RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] WATER property reflection failed", e);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Moka] Reflection probe failed", e);
        }
    }

    private static boolean isMokaBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.mokaMakerBEClass != null && YHKReflection.mokaMakerBEClass.isAssignableFrom(be.getClass())) return true;
        // Fallback: class name walk
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.mokaMakerBEClass != null && YHKReflection.mokaMakerBEClass.getName().equals(clazz.getName())) return true;
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

    /** Cache for BasePotBlockEntity.inventory field -- the full ItemStackHandler. */
    @Nullable
    private static volatile Field inventoryField;

    private static IItemHandler getInventory(BlockEntity be) {
        probeReflection();
        // Primary: direct field access to BasePotBlockEntity.inventory (private).
        // The public getInventory() method returns a capability-wrapped handler
        // with only 7 slots; the private ItemStackHandler field has all 9.
        if (inventoryField == null && YHKReflection.mokaMakerBEClass != null) {
            try {
                inventoryField = YHKReflection.mokaMakerBEClass.getSuperclass().getDeclaredField("inventory");
                inventoryField.setAccessible(true);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] inventoryField access failed", e);
            }
        }
        if (inventoryField != null) {
            try {
                Object val = inventoryField.get(be);
                if (val instanceof IItemHandler h) return h;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] inventoryField get failed", e);
            }
        }
        // Fallback: getInventory() public method (capability wrapper, ~7 slots)
        if (inventoryMethod != null) {
            try {
                return (IItemHandler) inventoryMethod.invoke(be);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] inventoryMethod invoke failed", e);
            }
        }
        // Last resort: capability
        return be.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
    }

    private boolean isHeated(BlockEntity be) {
        if (isHeatedMethod == null) return true; // can't check, assume heated
        try {
            int paramCount = isHeatedMethod.getParameterCount();
            if (paramCount == 0) {
                return (boolean) isHeatedMethod.invoke(be);
            } else if (paramCount == 2) {
                return (boolean) isHeatedMethod.invoke(
                        Modifier.isStatic(isHeatedMethod.getModifiers()) ? null : be,
                        myLevel, myPos);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Moka] isHeated invoke failed", e);
        }
        return true; // can't check, assume heated so we don't block
    }

    private static ItemStack addItem(BlockEntity be, ItemStack stack) {
        probeReflection();
        if (addItemMethod != null) {
            try {
                return (ItemStack) addItemMethod.invoke(be, stack);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] addItemMethod invoke failed", e);
            }
        }
        // Fallback: insert into inventory directly
        IItemHandler handler = getInventory(be);
        if (handler != null) {
            return ItemHandlerHelper.insertItem(handler, stack, false);
        }
        return stack;
    }

    private boolean hasWater() {
        var state = myLevel.getBlockState(myPos);
        if (waterPropertyField != null) {
            try {
                Object prop = waterPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp))
                    return state.getValue(bp);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] hasWater reflection failed", e);
            }
        }
        for (var prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase("water") && prop instanceof BooleanProperty bp)
                return state.getValue(bp);
        }
        return true; // assume water present if uncheckable
    }

    private boolean setWaterProperty(BlockState state, boolean value) {
        if (waterPropertyField != null) {
            try {
                Object prop = waterPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp)) {
                    return myLevel.setBlock(myPos, state.setValue(bp, value), 3);
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-MokaPot] setWaterProperty reflection failed", e);
            }
        }
        for (var prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase("water") && prop instanceof BooleanProperty bp) {
                return myLevel.setBlock(myPos, state.setValue(bp, value), 3);
            }
        }
        return false;
    }

    private static ItemStack getOutputContainer(Recipe<?> recipe) {
        return YoukaisHomecomingRecipeHandler.getMokaOutputContainer(recipe);
    }

    static int evacuateIdleInventory(IItemHandler handler, int cookTime,
                                     Consumer<ItemStack> returnItem) {
        if (handler == null || handler.getSlots() < INVENTORY_SIZE || cookTime > 0) return -1;
        IdleInventoryEvacuator.Result result = IdleInventoryEvacuator.evacuate(
                handler, true, ignored -> IdleInventoryEvacuator.SlotPolicy.RETURN, returnItem);
        return result.cleared() ? result.returnedCount() : -1;
    }

    private static int getCookTime(BlockEntity be) {
        return be instanceof ContainerData data ? data.get(0) : 1;
    }

    // -- cleanup --

    private List<ItemStack> clearAndRefund() {
        List<ItemStack> recoveredInputs = new ArrayList<>();
        if (!myLevel.hasChunkAt(myPos)) return recoveredInputs;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isMokaBE(be)) return recoveredInputs;

        IItemHandler handler = getInventory(be);
        if (handler == null || handler.getSlots() < INVENTORY_SIZE) return recoveredInputs;

        for (int slot = 0; slot < INPUT_SLOTS; slot++) {
            ItemStack s = handler.extractItem(slot, 64, false);
            if (!s.isEmpty()) recoveredInputs.add(s.copy());
            if (!s.isEmpty() && !usingSharedLedger) refund(s);
        }
        ItemStack container = handler.extractItem(CONTAINER_SLOT, 64, false);
        if (!container.isEmpty()) recoveredInputs.add(container.copy());
        if (!container.isEmpty() && !usingSharedLedger) refund(container);
        ItemStack meal = handler.extractItem(MEAL_DISPLAY_SLOT, 64, false);
        if (!meal.isEmpty()) refund(meal);
        ItemStack out = handler.extractItem(OUTPUT_SLOT, 64, false);
        // Produced output is never part of the input ledger. Preserve it even
        // when a shared graph operation fails after completing some cycles.
        if (!out.isEmpty()) refund(out);
        be.setChanged();
        return recoveredInputs;
    }

    private void refund(ItemStack stack) {
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (!leftover.isEmpty() && player != null)
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
    }

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }
}
