package com.huanghuang.rsintegration.mods.youkaishomecoming.ferment;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import com.huanghuang.rsintegration.util.Reflect;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.util.Action;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.NotNull;
import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Batch delegate for Youkais Homecoming Fermentation Tank. */
public final class FermentationTankBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;

    // Water / fluid state
    private int requiredWater; // mb, from recipe
    private int recipeTime;    // ticks, from recipe
    private FermentationRecipeOutputs.FluidMaterial inputFluidMaterial =
            new FermentationRecipeOutputs.FluidMaterial(
                    FluidStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, true);
    private FluidStack ownedInputFluid = FluidStack.EMPTY;
    private ItemStack pendingInputContainers = ItemStack.EMPTY;

    private int insertedCount;
    private ItemStack insertedSample = ItemStack.EMPTY;
    private final List<ItemStack> placedInputs = new ArrayList<>();
    @Nullable
    private ExpectedProduction expectedProduction;
    private boolean sawSealedCraft;
    private boolean progressStarted;
    private int maxObservedProgress;
    private boolean completionLatched;
    private boolean previousLidOpen;

    private static volatile Field itemsField;
    private static volatile Method notifyTileMethod;
    private static volatile Field openPropertyField;
    private static volatile boolean reflectionProbed;

    // Fluid handler (from FluidItemTile base class)
    private static volatile Method getFluidHandlerMethod;
    // Recipe fields
    private static volatile Field recipeWaterField;
    private static volatile Field recipeInputFluidField;
    private static volatile Field recipeOutputFluidField;
    private static volatile Field recipeTimeField;
    // YHK fluid system (shared with kettle)
    private static volatile Field yhFluidTypeField;
    private static volatile Method holderAmountMethod;
    private static volatile Method holderAsStackMethod;

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
        if (found == null || YHKReflection.simpleFermentationRecipeClass == null || !YHKReflection.simpleFermentationRecipeClass.isInstance(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        this.expectedProduction = null;
        this.sawSealedCraft = false;
        this.progressStarted = false;
        this.maxObservedProgress = 0;
        this.completionLatched = false;
        this.previousLidOpen = true;
        this.placedInputs.clear();
        probeReflection();
        this.requiredWater = readRecipeWater(found);
        this.recipeTime = readRecipeTime(found);
        this.inputFluidMaterial = FermentationRecipeOutputs.inputFluidMaterial(found);
        this.ownedInputFluid = FluidStack.EMPTY;
        this.pendingInputContainers = ItemStack.EMPTY;
        RSIntegrationMod.LOGGER.debug("[RSI-Ferment] validateAndInit: recipe={} water={}mb time={}ticks inputFluid={} holders={} supported={} inputFluidF={} outFluidF={}",
                recipeId, requiredWater, recipeTime, inputFluidMaterial.fluid(),
                inputFluidMaterial.filledContainers(), inputFluidMaterial.supported(),
                recipeInputFluidField != null, recipeOutputFluidField != null);
        return inputFluidMaterial.supported();
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<Ingredient> ingredients = getRecipeIngredients();
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }
        if (inputFluidMaterial.requiresPlannedContainers()) {
            ItemStack holders = inputFluidMaterial.filledContainers();
            specs.add(new IngredientSpec(Ingredient.of(holders), holders.getCount()));
        }
        return specs.isEmpty() ? null : specs;
    }

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        return BatchConcurrencyCapabilities.machineSlot();
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
                        insertIntoStorage(player, mat.copy(), false);
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
        this.craftDone = false;

        forceChunkLoad(true);
        if (!myLevel.hasChunkAt(myPos)) return false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isFermentationBE(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] BE not found or not FermentBE at {} class={}",
                    myPos, be != null ? be.getClass().getName() : "null");
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        SimpleContainer itemHandler = getItemHandler(be);
        if (itemHandler == null) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        // ── Ensure lid is OPEN first ──
        // If the lid was closed (manual use or previous craft), opening it
        // resets any partial recipe progress and unseals the inventory.
        if (!isLidOpen()) {
            setLidOpen(true);
        }

        // The chain owns only an empty fermentation inventory. Existing contents
        // cannot be distinguished from this craft's later physical results.
        for (int i = 0; i < itemHandler.getContainerSize(); i++) {
            if (!itemHandler.getItem(i).isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_valid_failed", recipe.getId()));
                forceChunkLoad(false);
                return false;
            }
        }

        List<ItemStack> remainingMaterials = new ArrayList<>();
        for (ItemStack material : materials) {
            if (material != null && !material.isEmpty()) {
                remainingMaterials.add(material.copy());
            }
        }
        List<ItemStack> solidMaterials = new ArrayList<>();
        for (Ingredient ingredient : getRecipeIngredients()) {
            if (ingredient.isEmpty()) continue;
            List<ItemStack> matched = takeMatchingMaterials(remainingMaterials, ingredient, 1);
            if (countItems(matched) != 1) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Ferment] Missing solid ingredient for recipe {}", recipe.getId());
                forceChunkLoad(false);
                return false;
            }
            solidMaterials.addAll(matched);
        }
        List<ItemStack> fluidMaterials = List.of();
        if (inputFluidMaterial.requiresPlannedContainers()) {
            ItemStack holders = inputFluidMaterial.filledContainers();
            fluidMaterials = takeMatchingMaterials(remainingMaterials,
                    Ingredient.of(holders), holders.getCount());
            if (countItems(fluidMaterials) != holders.getCount()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Ferment] Missing bottled input fluid for recipe {}: need={} found={}",
                        recipe.getId(), holders.getCount(), countItems(fluidMaterials));
                forceChunkLoad(false);
                return false;
            }
        }
        remainingMaterials.removeIf(ItemStack::isEmpty);
        if (!remainingMaterials.isEmpty()) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-Ferment] Refusing unclassified materials for recipe {}: {}",
                    recipe.getId(), remainingMaterials);
            forceChunkLoad(false);
            return false;
        }

        IFluidHandler fluidHandler = getFluidHandler(be);
        FluidStack requiredInputFluid = inputFluidMaterial.fluid();
        if (fluidHandler == null && !requiredInputFluid.isEmpty()) {
            player.sendSystemMessage(Component.translatable(
                    "rsi.generic.error.machine_valid_failed", recipe.getId()));
            forceChunkLoad(false);
            return false;
        }
        if (fluidHandler != null) {
            FluidStack tankFluid = fluidHandler.getFluidInTank(0);
            boolean wrongExistingFluid = requiredInputFluid.isEmpty()
                    ? !tankFluid.isEmpty()
                    : requiredInputFluid.getFluid() == Fluids.WATER
                    ? !tankFluid.isEmpty() && tankFluid.getFluid() != Fluids.WATER
                    : !tankFluid.isEmpty();
            if (wrongExistingFluid) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_valid_failed", recipe.getId()));
                forceChunkLoad(false);
                return false;
            }
        }

        // ── Ensure water is filled ──
        if (fluidHandler == null) fluidHandler = getFluidHandler(be);
        if (fluidHandler != null && requiredWater > 0) {
            int current = getWaterAmount(fluidHandler);
            if (current < requiredWater) {
                if (!ensureWater(be, fluidHandler, requiredWater - current)) {
                    forceChunkLoad(false);
                    return false;
                }
            }
        }

        ownedInputFluid = FluidStack.EMPTY;
        pendingInputContainers = ItemStack.EMPTY;
        if (fluidHandler != null && inputFluidMaterial.requiresPlannedContainers()) {
            int accepted = fluidHandler.fill(requiredInputFluid.copy(),
                    IFluidHandler.FluidAction.SIMULATE);
            if (accepted != requiredInputFluid.getAmount()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-Ferment] Tank rejected input fluid for recipe {}: accepted={} required={}",
                        recipe.getId(), accepted, requiredInputFluid.getAmount());
                forceChunkLoad(false);
                return false;
            }
            int filled = fluidHandler.fill(requiredInputFluid.copy(),
                    IFluidHandler.FluidAction.EXECUTE);
            if (filled != requiredInputFluid.getAmount()) {
                if (filled > 0) {
                    fluidHandler.drain(new FluidStack(requiredInputFluid.getFluid(), filled),
                            IFluidHandler.FluidAction.EXECUTE);
                }
                forceChunkLoad(false);
                return false;
            }
            ownedInputFluid = requiredInputFluid.copy();
            pendingInputContainers = inputFluidMaterial.emptyContainers();
            be.setChanged();
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Ferment] Filled planned input fluid: recipe={} fluid={} materials={} emptyContainers={}",
                    recipe.getId(), requiredInputFluid, fluidMaterials, pendingInputContainers);
        }

        // ── Place ingredients into slots ──
        ItemStack[] snapshot = new ItemStack[itemHandler.getContainerSize()];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = itemHandler.getItem(i).copy();
        }

        insertedCount = 0;
        insertedSample = ItemStack.EMPTY;
        placedInputs.clear();
        for (ItemStack mat : solidMaterials) {
            if (mat.isEmpty()) continue;
            ItemStack single = mat.copyWithCount(1);
            boolean inserted = false;
            for (int i = 0; i < itemHandler.getContainerSize(); i++) {
                if (itemHandler.getItem(i).isEmpty()) {
                    itemHandler.setItem(i, single);
                    inserted = true;
                    break;
                }
            }
            if (!inserted) {
                // Restore snapshot
                for (int i = 0; i < snapshot.length; i++) {
                    itemHandler.setItem(i, snapshot[i]);
                }
                rollbackOwnedInputFluid(fluidHandler);
                notifyTile(be);
                return false;
            }
            insertedCount++;
            placedInputs.add(single.copy());
            if (insertedSample.isEmpty()) insertedSample = single.copy();
        }
        FermentationRecipeOutputs.Production production = FermentationRecipeOutputs.fromRecipe(
                recipe, placedInputs.size());
        expectedProduction = !production.secondary().isEmpty() || production.primary().isEmpty()
                ? null
                : new ExpectedProduction(production.primary(), production.primary().getCount());
        if (expectedProduction == null) {
            for (int i = 0; i < snapshot.length; i++) itemHandler.setItem(i, snapshot[i]);
            rollbackOwnedInputFluid(fluidHandler);
            notifyTile(be);
            player.sendSystemMessage(Component.translatable(
                    "rsi.youkaishomecoming.ferment_unsafe_output", recipe.getId()));
            forceChunkLoad(false);
            return false;
        }
        setLidOpen(false); // close lid to start fermentation
        previousLidOpen = isLidOpen();
        sawSealedCraft = !previousLidOpen;
        progressStarted = false;
        maxObservedProgress = 0;
        completionLatched = false;
        markCraftStarted();
        notifyTile(be);
        RSIntegrationMod.LOGGER.debug("[RSI-Ferment] Started: recipe={} lidOpen={} inputs={} expected={}x{} time={}",
                recipe.getId(), previousLidOpen, describeStacks(placedInputs),
                expectedProduction.item().getHoverName().getString(), expectedProduction.count(), recipeTime);
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return observeMachineCraft(level, be).phase() == CraftPhase.DONE;
    }

    @Override
    protected CraftObservation observeMachineCraft(ServerLevel level, BlockEntity be) {
        if (completionLatched) return doneObservation();
        if (!isFermentationBE(be)) return failObservation("wrong fermentation block entity");
        SimpleContainer handler = getItemHandler(be);
        if (handler == null) return failObservation("fermentation inventory unavailable");

        boolean lidOpen = isLidOpen();
        if (!lidOpen) sawSealedCraft = true;

        int total = recipeTime > 0 ? recipeTime
                : Reflect.<Integer>getField(be, "totalTime").orElse(-1);
        int progress = Reflect.<Integer>getField(be, "recipeProgress").orElse(-1);
        if (progress > 0) {
            progressStarted = true;
            maxObservedProgress = Math.max(maxObservedProgress, progress);
        }

        boolean inputsRemain = containsPlacedInputs(handler, placedInputs);
        boolean completeOutputPresent = hasCompleteExpectedOutput(handler)
                || hasExpectedOutputFluid(be);
        boolean reachedTotal = total > 0 && progress >= total;
        boolean resetAfterFullRun = progressStarted && total > 0
                && maxObservedProgress >= Math.max(1, total - 1)
                && progress == 0 && !inputsRemain;
        boolean transformedWhileRunning = progressStarted && !inputsRemain
                && completeOutputPresent;
        boolean openedWithCompleteTransform = sawSealedCraft && !previousLidOpen
                && lidOpen && !inputsRemain && completeOutputPresent;

        if (shouldLatchCompletion(reachedTotal, resetAfterFullRun,
                transformedWhileRunning, openedWithCompleteTransform)) {
            completionLatched = true;
            String reason = reachedTotal ? "progress-total"
                    : resetAfterFullRun ? "progress-reset"
                    : transformedWhileRunning ? "running-transform"
                    : "sealed-opened-transform";
            RSIntegrationMod.LOGGER.debug(
                    "[RSI-Ferment] Completion latched: recipe={} reason={} lidOpen={} progress={}/{} max={} slots={}",
                    recipe.getId(), reason, lidOpen, progress, total, maxObservedProgress,
                    describeContainer(handler));
            previousLidOpen = lidOpen;
            return doneObservation();
        }

        previousLidOpen = lidOpen;
        return workingObservation();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        List<ItemStack> all = collectPhysicalResults();
        if (all.isEmpty()) return ItemStack.EMPTY;
        for (int i = 1; i < all.size(); i++) refund(all.get(i));
        return all.get(0);
    }

    @Override
    public List<ItemStack> collectAllResults(ServerPlayer player) {
        return collectPhysicalResults();
    }

    @Override
    public boolean collectsPhysicalSecondaryOutputs() {
        return true;
    }

    private List<ItemStack> collectPhysicalResults() {
        if (!completionLatched) {
            RSIntegrationMod.LOGGER.error("[RSI-Ferment] Refusing to collect before completion: recipe={}",
                    recipe != null ? recipe.getId() : "unknown");
            return List.of();
        }
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return List.of();
        List<ItemStack> collected = new ArrayList<>();

        ItemStack fluidResult = collectFluidOutput(be);
        if (!fluidResult.isEmpty()) collected.add(fluidResult);

        SimpleContainer handler = getItemHandler(be);
        if (handler != null) {
            for (int i = 0; i < handler.getContainerSize(); i++) {
                ItemStack stack = handler.getItem(i);
                if (stack.isEmpty()) continue;
                ItemStack extracted = handler.removeItem(i, stack.getCount());
                if (!extracted.isEmpty()) collected.add(extracted);
            }
        }
        if (!collected.isEmpty() && !pendingInputContainers.isEmpty()) {
            collected.add(pendingInputContainers.copy());
            pendingInputContainers = ItemStack.EMPTY;
        }
        notifyTile(be);
        craftDone = true;
        return List.copyOf(collected);
    }

    /**
     * Drain the output fluid from the tank and convert to bottled item form
     * using YHK's fluid system (type.asStack()).
     */
    private ItemStack collectFluidOutput(BlockEntity be) {
        if (recipeOutputFluidField == null) return ItemStack.EMPTY;
        try {
            FluidStack outputFluid = (FluidStack) recipeOutputFluidField.get(recipe);
            if (outputFluid == null || outputFluid.isEmpty()) return ItemStack.EMPTY;
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }

        IFluidHandler fh = getFluidHandler(be);
        if (fh == null) return ItemStack.EMPTY;

        FluidStack tankFluid = fh.getFluidInTank(0);
        if (tankFluid.isEmpty()) return ItemStack.EMPTY;

        // Check if it's a YHFluid and can be bottled
        if (YHKReflection.yhFluidClass == null
                || !YHKReflection.yhFluidClass.isInstance(tankFluid.getFluid())
                || yhFluidTypeField == null || holderAsStackMethod == null)
            return ItemStack.EMPTY;

        try {
            Object typeHolder = yhFluidTypeField.get(tankFluid.getFluid());
            if (typeHolder == null) return ItemStack.EMPTY;

            int perBottle = holderAmountMethod != null
                    ? (int) holderAmountMethod.invoke(typeHolder) : 250;
            if (perBottle <= 0) return ItemStack.EMPTY;
            int totalAmount = tankFluid.getAmount();
            int bottles = totalAmount / perBottle;
            if (bottles <= 0) return ItemStack.EMPTY;

            ItemStack result = (ItemStack) holderAsStackMethod.invoke(typeHolder, bottles);
            if (result == null || result.isEmpty()) return ItemStack.EMPTY;

            int drainAmount = bottles * perBottle;
            FluidStack simulated = fh.drain(new FluidStack(tankFluid.getFluid(), drainAmount),
                    IFluidHandler.FluidAction.SIMULATE);
            if (simulated.isEmpty() || simulated.getAmount() != drainAmount
                    || simulated.getFluid() != tankFluid.getFluid()) return ItemStack.EMPTY;
            FluidStack drained = fh.drain(new FluidStack(tankFluid.getFluid(), drainAmount),
                    IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty() || drained.getAmount() != drainAmount) return ItemStack.EMPTY;

            be.setChanged();
            RSIntegrationMod.LOGGER.debug("[RSI-Ferment] Collected fluid output: {}mb → {}",
                    drained.getAmount(), result.getHoverName().getString());
            return result;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] collectFluidOutput failed", e);
            return ItemStack.EMPTY;
        }
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        clearAndRefund();
        forceChunkLoad(false);
        resetFermentState();
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        forceChunkLoad(false);
        clearAndRefund();
        resetFermentState();
    }

    private void resetFermentState() {
        craftDone = false;
        insertedCount = 0;
        insertedSample = ItemStack.EMPTY;
        placedInputs.clear();
        expectedProduction = null;
        sawSealedCraft = false;
        progressStarted = false;
        maxObservedProgress = 0;
        completionLatched = false;
        previousLidOpen = true;
        requiredWater = 0;
        recipeTime = 0;
        inputFluidMaterial = new FermentationRecipeOutputs.FluidMaterial(
                FluidStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, true);
        ownedInputFluid = FluidStack.EMPTY;
        pendingInputContainers = ItemStack.EMPTY;
        resetState();
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    @Nullable
    @Override
    public ExpectedProduction getExpectedProduction() {
        return expectedProduction;
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
        int water = readRecipeWater(recipe);
        if (water > 0) {
            int perBottle = getYHWaterBottleAmount();
            if (perBottle > 0) {
                int bottles = (water + perBottle - 1) / perBottle;
                warnings.add(Component.translatable("rsi.youkaishomecoming.ferment_water_needed",
                        bottles, water));
            } else {
                warnings.add(Component.translatable("rsi.youkaishomecoming.ferment_water_needed_mb",
                        water));
            }
        }
        warnings.add(Component.translatable("rsi.youkaishomecoming.ferment_lid_warning"));
        return warnings;
    }

    // -- recipe accessors via public fields --

    @SuppressWarnings("unchecked")
    private List<Ingredient> getRecipeIngredients() {
        try {
            Field f = findFieldInHierarchy(recipe.getClass(), "ingredients");
            if (f == null) return List.of();
            f.setAccessible(true);
            Object val = f.get(recipe);
            if (val instanceof List) return (List<Ingredient>) val;
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] Recipe ingredients reflection failed", e);
        }
        return List.of();
    }

    private boolean hasRecipeOutputFluid() {
        if (recipeOutputFluidField == null || recipe == null) return false;
        try {
            Object value = recipeOutputFluidField.get(recipe);
            return value instanceof FluidStack fluid && !fluid.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean shouldLatchCompletion(boolean reachedTotal, boolean resetAfterFullRun,
                                         boolean transformedWhileRunning,
                                         boolean openedWithCompleteTransform) {
        return reachedTotal || resetAfterFullRun || transformedWhileRunning
                || openedWithCompleteTransform;
    }

    static boolean hasCompleteExpectedOutput(SimpleContainer handler,
                                             ExpectedProduction expectedProduction) {
        if (handler == null || expectedProduction == null) return false;
        int count = 0;
        for (int i = 0; i < handler.getContainerSize(); i++) {
            ItemStack stack = handler.getItem(i);
            if (!stack.isEmpty() && ItemStack.isSameItem(stack, expectedProduction.item())) {
                count += stack.getCount();
                if (count >= expectedProduction.count()) return true;
            }
        }
        return false;
    }

    private boolean hasCompleteExpectedOutput(SimpleContainer handler) {
        return hasCompleteExpectedOutput(handler, expectedProduction);
    }

    private static String describeStacks(List<ItemStack> stacks) {
        List<String> parts = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                parts.add(stack.getHoverName().getString() + "x" + stack.getCount());
            }
        }
        return parts.toString();
    }

    private static String describeContainer(SimpleContainer handler) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int i = 0; i < handler.getContainerSize(); i++) {
            ItemStack stack = handler.getItem(i);
            if (!stack.isEmpty()) stacks.add(stack.copy());
        }
        return describeStacks(stacks);
    }

    private boolean hasExpectedOutputFluid(BlockEntity be) {
        if (!hasRecipeOutputFluid()) return false;
        IFluidHandler handler = getFluidHandler(be);
        if (handler == null) return false;
        FluidStack actual = handler.getFluidInTank(0);
        try {
            FluidStack expected = (FluidStack) recipeOutputFluidField.get(recipe);
            return expected != null && !expected.isEmpty() && !actual.isEmpty()
                    && actual.getFluid() == expected.getFluid();
        } catch (Exception e) {
            return false;
        }
    }

    static boolean containsPlacedInputs(SimpleContainer handler, List<ItemStack> placedInputs) {
        if (handler == null || placedInputs == null || placedInputs.isEmpty()) return false;
        List<ItemStack> remaining = new ArrayList<>();
        for (int i = 0; i < handler.getContainerSize(); i++) {
            ItemStack stack = handler.getItem(i);
            if (!stack.isEmpty()) remaining.add(stack.copy());
        }
        for (ItemStack placed : placedInputs) {
            boolean matched = false;
            for (int i = 0; i < remaining.size(); i++) {
                ItemStack current = remaining.get(i);
                if (ItemStack.isSameItemSameTags(placed, current)) {
                    remaining.remove(i);
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    static List<ItemStack> takeMatchingMaterials(
            List<ItemStack> pool, Ingredient ingredient, int count) {
        if (pool == null || ingredient == null || ingredient.isEmpty() || count <= 0) {
            return List.of();
        }
        int remaining = count;
        List<ItemStack> taken = new ArrayList<>();
        for (ItemStack stack : pool) {
            if (remaining <= 0) break;
            if (stack == null || stack.isEmpty()
                    || !IngredientMatcher.test(ingredient, stack)) continue;
            int amount = Math.min(remaining, stack.getCount());
            taken.add(stack.copyWithCount(amount));
            stack.shrink(amount);
            remaining -= amount;
        }
        return List.copyOf(taken);
    }

    private static int countItems(List<ItemStack> stacks) {
        int count = 0;
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) count += stack.getCount();
        }
        return count;
    }

    private void rollbackOwnedInputFluid(@Nullable IFluidHandler fluidHandler) {
        if (fluidHandler != null && !ownedInputFluid.isEmpty()) {
            FluidStack current = fluidHandler.getFluidInTank(0);
            if (!current.isEmpty() && current.getFluid() == ownedInputFluid.getFluid()) {
                int amount = Math.min(current.getAmount(), ownedInputFluid.getAmount());
                if (amount > 0) {
                    fluidHandler.drain(new FluidStack(current.getFluid(), amount),
                            IFluidHandler.FluidAction.EXECUTE);
                }
            }
        }
        ownedInputFluid = FluidStack.EMPTY;
        pendingInputContainers = ItemStack.EMPTY;
    }

    /** If the recipe has an outputFluid field, convert it to the bottled item form. */
    @Nullable
    private ItemStack buildFluidOutputItem() {
        if (recipeOutputFluidField == null) return ItemStack.EMPTY;
        try {
            FluidStack fs = (FluidStack) recipeOutputFluidField.get(recipe);
            if (fs == null || fs.isEmpty()) return ItemStack.EMPTY;
            // If it's a YHFluid, use type.asStack() for bottled form
            if (YHKReflection.yhFluidClass != null
                    && YHKReflection.yhFluidClass.isInstance(fs.getFluid())
                    && yhFluidTypeField != null && holderAsStackMethod != null) {
                Object holder = yhFluidTypeField.get(fs.getFluid());
                if (holder != null) {
                    int perBottle = holderAmountMethod != null
                            ? (int) holderAmountMethod.invoke(holder) : 250;
                    int bottles = fs.getAmount() / perBottle;
                    if (bottles > 0)
                        return (ItemStack) holderAsStackMethod.invoke(holder, bottles);
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] buildFluidOutputItem failed", e);
        }
        return ItemStack.EMPTY;
    }

    /** Find a field by name, traversing the class hierarchy. */
    @Nullable
    private static Field findFieldInHierarchy(Class<?> clazz, String name) {
        Class<?> scan = clazz;
        while (scan != null && scan != Object.class) {
            try {
                return scan.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                scan = scan.getSuperclass();
            }
        }
        return null;
    }

    // -- reflection --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.fermentationTankBEClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] YHKReflection ferment probe not ready");
            return;
        }
        try {
            // FermentationTankBlockEntity.items
            itemsField = findFieldInHierarchy(YHKReflection.fermentationTankBEClass, "items");
            if (itemsField != null) itemsField.setAccessible(true);

            // FluidItemTile.notifyTile()
            if (YHKReflection.fluidItemTileClass != null) {
                notifyTileMethod = YHKReflection.fluidItemTileClass.getMethod("notifyTile");
                notifyTileMethod.setAccessible(true);
                // getFluidHandler() — public on FluidItemTile
                getFluidHandlerMethod = YHKReflection.fluidItemTileClass.getMethod("getFluidHandler");
                getFluidHandlerMethod.setAccessible(true);
            }

            // FermentationTankBlock.OPEN -- public static BooleanProperty
            if (YHKReflection.fermentationTankBlockClass != null) {
                openPropertyField = YHKReflection.fermentationTankBlockClass.getDeclaredField("OPEN");
                openPropertyField.setAccessible(true);
            }

            // SimpleFermentationRecipe fields
            if (YHKReflection.simpleFermentationRecipeClass != null) {
                recipeWaterField = findFieldInHierarchy(YHKReflection.simpleFermentationRecipeClass, "water");
                if (recipeWaterField == null)
                    recipeWaterField = findFieldInHierarchy(YHKReflection.simpleFermentationRecipeClass, "waterAmount");
                if (recipeWaterField != null) recipeWaterField.setAccessible(true);

                // SimpleFermentationRecipe uses inputFluid/outputFluid (FluidStack)
                recipeInputFluidField = findFieldInHierarchy(YHKReflection.simpleFermentationRecipeClass, "inputFluid");
                if (recipeInputFluidField != null) recipeInputFluidField.setAccessible(true);
                recipeOutputFluidField = findFieldInHierarchy(YHKReflection.simpleFermentationRecipeClass, "outputFluid");
                if (recipeOutputFluidField != null) recipeOutputFluidField.setAccessible(true);

                recipeTimeField = findFieldInHierarchy(YHKReflection.simpleFermentationRecipeClass, "time");
                if (recipeTimeField == null)
                    recipeTimeField = findFieldInHierarchy(YHKReflection.simpleFermentationRecipeClass, "duration");
                if (recipeTimeField != null) recipeTimeField.setAccessible(true);
            }

            RSIntegrationMod.LOGGER.debug("[RSI-Ferment] probeReflection: inputFluidField={} outputFluidField={} timeField={} waterField={}",
                    recipeInputFluidField != null, recipeOutputFluidField != null,
                    recipeTimeField != null, recipeWaterField != null);

            // YHFluid custom container system
            if (YHKReflection.yhFluidClass != null && YHKReflection.yhFluidHolderClass != null) {
                yhFluidTypeField = YHKReflection.yhFluidClass.getField("type");
                holderAmountMethod = YHKReflection.yhFluidHolderClass.getMethod("amount");
                holderAsStackMethod = YHKReflection.yhFluidHolderClass.getMethod("asStack", int.class);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] Reflection probe failed", e);
        }
    }

    private static boolean isFermentationBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.fermentationTankBEClass != null && YHKReflection.fermentationTankBEClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.fermentationTankBEClass != null && YHKReflection.fermentationTankBEClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    private static SimpleContainer getItemHandler(BlockEntity be) {
        probeReflection();
        if (itemsField != null) {
            try {
                Object val = itemsField.get(be);
                if (val instanceof SimpleContainer sc) return sc;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] getItemHandler reflection failed", e);
            }
        }
        // Fallback: getCapability
        IItemHandler cap = be.getCapability(
                ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        if (cap instanceof SimpleContainer sc) return sc;
        return null;
    }

    private static void notifyTile(BlockEntity be) {
        if (notifyTileMethod != null) {
            try {
                notifyTileMethod.invoke(be);
                return;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] notifyTile reflection failed", e);
            }
        }
        be.setChanged();
    }

    private boolean isLidOpen() {
        var state = myLevel.getBlockState(myPos);
        if (openPropertyField != null) {
            try {
                Object prop = openPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp))
                    return state.getValue(bp);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] isLidOpen reflection failed", e);
            }
        }
        for (var prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase("open") && prop instanceof BooleanProperty bp)
                return state.getValue(bp);
        }
        return false;
    }

    private void setLidOpen(boolean open) {
        var state = myLevel.getBlockState(myPos);
        if (openPropertyField != null) {
            try {
                Object prop = openPropertyField.get(null);
                if (prop instanceof BooleanProperty bp && state.hasProperty(bp)) {
                    myLevel.setBlock(myPos, state.setValue(bp, open), 3);
                    return;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Fermentation] setLidOpen reflection failed", e);
            }
        }
        for (var prop : state.getProperties()) {
            if (prop.getName().equalsIgnoreCase("open") && prop instanceof BooleanProperty bp) {
                myLevel.setBlock(myPos, state.setValue(bp, open), 3);
                return;
            }
        }
    }

    // -- water / fluid helpers --

    private static int readRecipeWater(Recipe<?> recipe) {
        probeReflection();
        if (recipeWaterField != null) {
            try { return (int) recipeWaterField.get(recipe); } catch (Exception e) { /* fall through */ }
        }
        // SimpleFermentationRecipe: inputFluid (FluidStack)
        if (recipeInputFluidField != null) {
            try {
                FluidStack fs = (FluidStack) recipeInputFluidField.get(recipe);
                if (fs != null && !fs.isEmpty()) {
                    Fluid fluid = fs.getFluid();
                    // Check vanilla water first, then name-based for YHFluid
                    if (fluid == Fluids.WATER || (fluid != null && (
                            fluid.getFluidType().toString().contains("water")
                            || fluid.getFluidType().toString().contains("WATER")))) {
                        return fs.getAmount();
                    }
                    RSIntegrationMod.LOGGER.debug("[RSI-Ferment] readRecipeWater: inputFluid present but not water: fluid={} amount={}",
                            fluid != null ? fluid.getFluidType().toString() : "null", fs.getAmount());
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Ferment] readRecipeWater: inputFluid read failed", e);
            }
        }
        // Fallback: try via getter method
        try {
            Method m = recipe.getClass().getMethod("getWater");
            return (int) m.invoke(recipe);
        } catch (Exception e2) { /* fall through */ }
        try {
            Method m = recipe.getClass().getMethod("getWaterAmount");
            return (int) m.invoke(recipe);
        } catch (Exception e3) { /* fall through */ }
        return 0;
    }

    private static int readRecipeTime(Recipe<?> recipe) {
        probeReflection();
        if (recipeTimeField != null) {
            try { return (int) recipeTimeField.get(recipe); } catch (Exception e) { /* fall through */ }
        }
        try {
            Method m = recipe.getClass().getMethod("getTime");
            return (int) m.invoke(recipe);
        } catch (Exception e2) { /* fall through */ }
        return 0;
    }

    /** Get fluid handler from a FluidItemTile BE. */
    private static IFluidHandler getFluidHandler(BlockEntity be) {
        probeReflection();
        if (getFluidHandlerMethod != null) {
            try { return (IFluidHandler) getFluidHandlerMethod.invoke(be); }
            catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-Ferment] getFluidHandler reflection failed", e); }
        }
        return be.getCapability(ForgeCapabilities.FLUID_HANDLER)
                .resolve().orElse(null);
    }

    /** Get current water amount in the fluid tank (mb). */
    private static int getWaterAmount(IFluidHandler fluidHandler) {
        FluidStack fluid = fluidHandler.getFluidInTank(0);
        if (fluid.isEmpty()) return 0;
        if (fluid.getFluid() == Fluids.WATER) return fluid.getAmount();
        // YHFluid check — YHFluid has a 'type' field (IYHFluidHolder)
        if (YHKReflection.yhFluidClass != null && YHKReflection.yhFluidClass.isInstance(fluid.getFluid())
                && yhFluidTypeField != null) {
            try {
                Object typeHolder = yhFluidTypeField.get(fluid.getFluid());
                if (typeHolder != null) {
                    // Check if this fluid type represents water by registry name
                    ResourceLocation key = BuiltInRegistries.FLUID.getKey(fluid.getFluid());
                    if (key != null && key.getPath().contains("water")) return fluid.getAmount();
                }
            } catch (Exception ignored) { /* fall through */ }
        }
        return 0; // non-water fluid
    }

    /**
     * Ensure the fermentation tank has enough water by filling directly
     * or by extracting water bottles from RS network.
     */
    private boolean ensureWater(BlockEntity be, IFluidHandler fluidHandler, int deficitMb) {
        if (storageEndpoint() == null) {
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        }
        if (this.network == null && storageEndpoint() == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] No network, cannot get water");
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.ferment_water_warning"));
            return false;
        }

        // Phase 1: try direct Forge fluid fill (YHK tanks usually accept this)
        int filled = fluidHandler.fill(new FluidStack(Fluids.WATER, deficitMb),
                IFluidHandler.FluidAction.EXECUTE);
        if (filled > 0) {
            be.setChanged();
            RSIntegrationMod.LOGGER.debug("[RSI-Ferment] Direct water fill: {}mb", filled);
            // If fully filled, done
            if (filled >= deficitMb) return true;
            deficitMb -= filled;
        }

        // Phase 2: use YHK fluid system — extract water bottles and fill tank
        // YHFluid.WATER.type.amount() = 250mb per bottle
        int perBottle = getYHWaterBottleAmount();
        if (perBottle <= 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] Cannot determine water bottle amount, perBottle={}", perBottle);
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.ferment_water_warning"));
            return false;
        }
        int bottlesNeeded = (deficitMb + perBottle - 1) / perBottle;
        RSIntegrationMod.LOGGER.debug("[RSI-Ferment] Need {} water bottles ({}mb each, deficit={}mb)",
                bottlesNeeded, perBottle, deficitMb);

        for (int i = 0; i < bottlesNeeded; i++) {
            ItemStack waterBottle = storageEndpoint() != null
                    ? findWaterHolder(storageEndpoint(), player)
                    : findWaterHolder(network);
            if (waterBottle.isEmpty()) {
                // Drain whatever we managed to fill and refund
                player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.ferment_water_warning"));
                return false;
            }

            // Extract the fluid holder from RS
            ItemStack extracted = extractExactFromStorage(player, waterBottle.copyWithCount(1), 1, false);
            if (extracted.isEmpty()) {
                player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.ferment_water_warning"));
                return false;
            }

            // Fill the tank using the fluid handler
            int bottleFilled = fluidHandler.fill(
                    new FluidStack(Fluids.WATER, perBottle),
                    IFluidHandler.FluidAction.EXECUTE);
            if (bottleFilled <= 0) {
                // Can't fill — refund the water bottle to RS
                insertIntoStorage(player, extracted, false);
                RSIntegrationMod.LOGGER.warn("[RSI-Ferment] Fluid handler rejected water fill (bottle {})", i);
                player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.ferment_water_warning"));
                return false;
            }

            // Return the empty container (if any) to RS
            ItemStack emptyContainer = getYHWaterEmptyContainer(extracted);
            if (!emptyContainer.isEmpty()) {
                ItemStack leftover = insertIntoStorage(player, emptyContainer, false);
                if (!leftover.isEmpty())
                    ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
            be.setChanged();
        }

        return true;
    }

    /** Get the mb amount per water bottle from YHFluid system. */
    private static int getYHWaterBottleAmount() {
        // Look up vanilla water from the Forge fluid registry; if YHK wraps it
        // in a YHFluid, read type.amount() from there.
        Fluid waterFluid =
                BuiltInRegistries.FLUID.get(
                        new ResourceLocation("water"));
        if (waterFluid == null || waterFluid == Fluids.EMPTY) return 0;
        if (YHKReflection.yhFluidClass != null
                && YHKReflection.yhFluidClass.isInstance(waterFluid)
                && yhFluidTypeField != null && holderAmountMethod != null) {
            try {
                Object holder = yhFluidTypeField.get(waterFluid);
                if (holder != null) return (int) holderAmountMethod.invoke(holder);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Ferment] getYHWaterBottleAmount failed", e);
            }
        }
        return 250; // default: YHK bottles hold 250mb
    }

    /** Find a water-compatible fluid holder in the RS network. */
    private static ItemStack findWaterHolder(INetwork network) {
        for (var entry : network.getItemStorageCache().getList().getStacks()) {
            ItemStack stack = entry.getStack();
            if (stack.isEmpty()) continue;
            // Check if it's a water bottle or water bucket
            if (stack.is(Items.WATER_BUCKET)
                    || stack.is(Items.POTION)) // water bottle
                return stack;
            // Check for YHK fluid holders (getFluid() returns a YHFluid, not an enum)
            if (YHKReflection.yhFluidHolderClass != null
                    && YHKReflection.yhFluidHolderClass.isInstance(stack.getItem())) {
                try {
                    Method getFluid = stack.getItem().getClass().getMethod("getFluid");
                    Object fluid = getFluid.invoke(stack.getItem());
                    if (fluid != null && YHKReflection.yhFluidClass != null
                            && YHKReflection.yhFluidClass.isInstance(fluid)) {
                        // Check if this YHFluid represents water by reading its type
                        if (yhFluidTypeField != null) {
                            Object typeHolder = yhFluidTypeField.get(fluid);
                            if (typeHolder != null && holderAmountMethod != null) {
                                // It's a fluid holder — accept it as a water source
                                return stack;
                            }
                        }
                    }
                } catch (Exception ignored) { /* not a fluid holder or wrong type */ }
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack findWaterHolder(CraftStorageEndpoint endpoint,
                                             ServerPlayer player) {
        var snapshot = endpoint.snapshot(player).snapshot().orElse(null);
        if (snapshot == null) return ItemStack.EMPTY;
        for (var entry : snapshot.items()) {
            ItemStack stack = entry.stack();
            if (stack.isEmpty()) continue;
            if (stack.is(Items.WATER_BUCKET)
                    || stack.is(Items.POTION)) return stack;
            if (YHKReflection.yhFluidHolderClass != null
                    && YHKReflection.yhFluidHolderClass.isInstance(stack.getItem())) {
                try {
                    Method getFluid = stack.getItem().getClass().getMethod("getFluid");
                    Object fluid = getFluid.invoke(stack.getItem());
                    if (fluid != null && YHKReflection.yhFluidClass != null
                            && YHKReflection.yhFluidClass.isInstance(fluid)
                            && yhFluidTypeField != null && yhFluidTypeField.get(fluid) != null) {
                        return stack;
                    }
                } catch (Exception ignored) { }
            }
        }
        return ItemStack.EMPTY;
    }

    /** Get the empty container returned when using a fluid holder. */
    private static ItemStack getYHWaterEmptyContainer(ItemStack holder) {
        if (holder.is(Items.WATER_BUCKET))
            return new ItemStack(Items.BUCKET);
        if (holder.is(Items.POTION))
            return new ItemStack(Items.GLASS_BOTTLE);
        // YHK fluid holders expose their real empty container directly.
        if (yhFluidTypeField != null && holderAsStackMethod != null
                && YHKReflection.yhFluidHolderClass != null
                && YHKReflection.yhFluidHolderClass.isInstance(holder.getItem())) {
            try {
                Method getFluid = holder.getItem().getClass().getMethod("getFluid");
                Object fluid = getFluid.invoke(holder.getItem());
                if (fluid != null && YHKReflection.yhFluidClass != null
                        && YHKReflection.yhFluidClass.isInstance(fluid)) {
                    Object typeHolder = yhFluidTypeField.get(fluid);
                    if (typeHolder != null) {
                        Method containerMethod = YHKReflection.yhFluidHolderClass.getMethod(
                                "getContainer");
                        Object container = containerMethod.invoke(typeHolder);
                        if (container instanceof Item item
                                && item != Items.AIR) {
                            return new ItemStack(item);
                        }
                    }
                }
            } catch (Exception ignored) { /* fall through */ }
        }
        return ItemStack.EMPTY;
    }

    // -- cleanup --

    private void clearAndRefund() {
        if (!myLevel.hasChunkAt(myPos)) return;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isFermentationBE(be)) return;

        // Planned non-water input belongs to the shared graph ledger. On graph
        // rollback, drain machine state and let that ledger restore the original
        // filled containers; refunding both would duplicate them.
        {
            IFluidHandler fh = getFluidHandler(be);
            if (fh != null) {
                FluidStack tankFluid = fh.getFluidInTank(0);
                if (!tankFluid.isEmpty()) {
                    if (usingSharedLedger && !ownedInputFluid.isEmpty()) {
                        fh.drain(tankFluid.copy(), IFluidHandler.FluidAction.EXECUTE);
                        be.setChanged();
                        tankFluid = FluidStack.EMPTY;
                    }
                }
                if (!tankFluid.isEmpty()) {
                    boolean isYH = YHKReflection.yhFluidClass != null
                            && YHKReflection.yhFluidClass.isInstance(tankFluid.getFluid());

                    if (isYH && yhFluidTypeField != null && holderAsStackMethod != null) {
                        // YHFluid — refund as bottled items via type.asStack()
                        try {
                            Object typeHolder = yhFluidTypeField.get(tankFluid.getFluid());
                            if (typeHolder != null) {
                                int holderAmt = holderAmountMethod != null
                                        ? (int) holderAmountMethod.invoke(typeHolder) : 250;
                                if (tankFluid.getAmount() >= holderAmt) {
                                    int bottles = tankFluid.getAmount() / holderAmt;
                                    FluidStack drained = fh.drain(
                                            new FluidStack(tankFluid.getFluid(), bottles * holderAmt),
                                            IFluidHandler.FluidAction.EXECUTE);
                                    if (!drained.isEmpty()) {
                                        ItemStack refundStack = (ItemStack) holderAsStackMethod.invoke(
                                                typeHolder, drained.getAmount() / holderAmt);
                                        if (!refundStack.isEmpty()) refund(refundStack);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] Fluid refund failed", e);
                        }
                    } else if (tankFluid.getFluid() == Fluids.WATER) {
                        // Vanilla water — refund as water bottles
                        int perBottle = getYHWaterBottleAmount();
                        if (perBottle <= 0) perBottle = 250;
                        int amount = tankFluid.getAmount();
                        if (amount >= perBottle) {
                            int bottles = amount / perBottle;
                            FluidStack drained = fh.drain(
                                    new FluidStack(Fluids.WATER, bottles * perBottle),
                                    IFluidHandler.FluidAction.EXECUTE);
                            if (!drained.isEmpty()) {
                                ItemStack refundStack = getYHWaterAsStack(bottles);
                                if (!refundStack.isEmpty()) refund(refundStack);
                            }
                        }
                    }
                    be.setChanged();
                }
            }
        }

        // Refund items from handler slots
        {
            SimpleContainer handler = getItemHandler(be);
            if (handler != null) {
                for (int i = 0; i < handler.getContainerSize(); i++) {
                    ItemStack s = handler.removeItem(i, 64);
                    if (!s.isEmpty() && !usingSharedLedger) refund(s);
                }
            }
        }
        notifyTile(be);
    }

    /** Get a water bottle stack using YHK fluid system. */
    private static ItemStack getYHWaterAsStack(int count) {
        Fluid waterFluid =
                BuiltInRegistries.FLUID.get(
                        new ResourceLocation("water"));
        if (waterFluid != null && waterFluid != Fluids.EMPTY
                && YHKReflection.yhFluidClass != null
                && YHKReflection.yhFluidClass.isInstance(waterFluid)
                && yhFluidTypeField != null && holderAsStackMethod != null) {
            try {
                Object holder = yhFluidTypeField.get(waterFluid);
                if (holder != null)
                    return (ItemStack) holderAsStackMethod.invoke(holder, count);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-Ferment] getYHWaterAsStack failed", e);
            }
        }
        return new ItemStack(Items.POTION);
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
