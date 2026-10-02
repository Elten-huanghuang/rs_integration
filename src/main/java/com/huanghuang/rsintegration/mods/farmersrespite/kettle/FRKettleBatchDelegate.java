package com.huanghuang.rsintegration.mods.farmersrespite.kettle;

import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
import com.huanghuang.rsintegration.reflection.probes.FRReflection;
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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Delegate for the Farmer's Respite Kettle (farmersrespite:brewing).
 * <p>
 * The kettle brews solid ingredients with an input fluid to produce an output
 * fluid ({@code KettleRecipe}). The output fluid is then bottled into an item
 * via the matching {@code KettlePouringRecipe} (container item + N mB → output).
 * <p>
 * Water follows the shared machine water supply policy. Other
 * fluids must be supplied as their native bottled item and are routed through
 * Farmer's Respite's container-slot transfer, preserving the kettle's own
 * empty-bottle and fluid-handling behavior.
 * <p>
 * Kettle inventory (5 slots): 0-1 input ingredients, 2 drink display,
 * 3 container slot, 4 output slot.
 */
public final class FRKettleBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private FluidStack recipeFluidIn;
    private FluidStack recipeFluidOut;
    private boolean craftDone;

    // ── pouring descriptor (fluid → bottled item) ──
    // The kettle brews to a FluidStack; RS can only carry ItemStacks, so the
    // output fluid is converted to a bottled item via the matching
    // KettlePouringRecipe (container item + `amount` mB fluid → output item).
    private ItemStack pourContainer = ItemStack.EMPTY; // e.g. glass bottle
    private ItemStack pourOutput = ItemStack.EMPTY;     // e.g. black tea bottle
    private int pourAmount;                             // mB drained per bottle
    private int bottlesPerCraft;                        // fluidOut.amount / pourAmount
    private ItemStack inputPourContainer = ItemStack.EMPTY;
    private ItemStack inputPourOutput = ItemStack.EMPTY;
    private int inputBottlesPerCraft;
    // Solid input slots we actually wrote this craft (for precise rollback).
    private final List<Integer> filledInputSlots = new ArrayList<>();
    private boolean placedContainerSlot;
    private boolean machineMutated;
    private NativeInputPhase nativeInputPhase = NativeInputPhase.NONE;
    private ItemStack deferredOutputContainers = ItemStack.EMPTY;
    private final List<ItemStack> pendingNativeSolidMats = new ArrayList<>();
    /** Original private-ledger materials consumed by the native container path. */
    private ItemStack nativeInputRefund = ItemStack.EMPTY;
    private ItemStack nativeOutputContainerRefund = ItemStack.EMPTY;

    // ── reflection cache ──
    private static volatile Method getInventoryMethod;
    private static volatile Method isHeatedMethod;
    private static volatile Method getFluidInMethod;
    private static volatile Method getFluidOutMethod;
    private static volatile Method getBrewTimeMethod;
    // KettlePouringRecipe accessors
    private static volatile Method pourGetFluidMethod;
    private static volatile Method pourGetAmountMethod;
    private static volatile Method pourGetContainerMethod;
    private static volatile Method pourGetOutputMethod;
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
        if (found == null || !FRReflection.kettleRecipeClass.isInstance(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        probeReflection();
        this.recipeFluidIn = getFluidIn(found);
        this.recipeFluidOut = getFluidOut(found);
        if (recipeFluidIn == null) recipeFluidIn = FluidStack.EMPTY;
        if (recipeFluidOut == null) recipeFluidOut = FluidStack.EMPTY;
        this.craftDone = false;
        this.filledInputSlots.clear();
        this.placedContainerSlot = false;
        this.machineMutated = false;
        this.nativeInputPhase = NativeInputPhase.NONE;
        this.deferredOutputContainers = ItemStack.EMPTY;
        this.pendingNativeSolidMats.clear();
        this.nativeInputRefund = ItemStack.EMPTY;
        this.nativeOutputContainerRefund = ItemStack.EMPTY;

        // Resolve the pouring recipe that bottles this output fluid.
        resolvePouring(level);
        if (pourOutput.isEmpty() || pourContainer.isEmpty() || bottlesPerCraft <= 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Recipe {} has no usable bottled output", recipeId);
            return false;
        }
        return true;
    }

    /** Find a KettlePouringRecipe whose fluid matches this recipe's output. */
    private void resolvePouring(ServerLevel level) {
        this.pourContainer = ItemStack.EMPTY;
        this.pourOutput = ItemStack.EMPTY;
        this.pourAmount = 0;
        this.bottlesPerCraft = 0;
        this.inputPourContainer = ItemStack.EMPTY;
        this.inputPourOutput = ItemStack.EMPTY;
        this.inputBottlesPerCraft = 0;
        if (FRReflection.kettlePouringRecipeClass == null) return;
        probeReflection();
        if (pourGetFluidMethod == null) return;

        PouringDescriptor output = findPouring(level, recipeFluidOut);
        if (output != null) {
            this.pourAmount = output.amount();
            this.pourContainer = output.container();
            this.pourOutput = output.output();
            this.bottlesPerCraft = output.bottles();
        } else if (!recipeFluidOut.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] No KettlePouringRecipe found for output fluid {}",
                    recipeFluidOut.getFluid().getFluidType().getDescriptionId());
        }

        PouringDescriptor input = findPouring(level, recipeFluidIn);
        if (input != null) {
            this.inputPourContainer = input.container();
            this.inputPourOutput = input.output();
            this.inputBottlesPerCraft = input.bottles();
        }
    }

    @Nullable
    private PouringDescriptor findPouring(ServerLevel level, FluidStack fluidStack) {
        if (fluidStack == null || fluidStack.isEmpty()) return null;
        for (Recipe<?> r : level.getRecipeManager().getRecipes()) {
            if (!FRReflection.kettlePouringRecipeClass.isInstance(r)) continue;
            try {
                Object fluid = pourGetFluidMethod.invoke(r);
                if (fluid != fluidStack.getFluid()) continue;
                int amount = (int) pourGetAmountMethod.invoke(r);
                ItemStack container = (ItemStack) pourGetContainerMethod.invoke(r);
                ItemStack output = (ItemStack) pourGetOutputMethod.invoke(r);
                if (amount <= 0 || output == null || output.isEmpty()) continue;
                return new PouringDescriptor(
                        container == null ? ItemStack.EMPTY : container.copy(),
                        output.copy(), amount, Math.max(1, fluidStack.getAmount() / amount));
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] pouring recipe probe failed", e);
            }
        }
        return null;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<Ingredient> ingredients = recipe.getIngredients();

        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }

        if (isWaterInput()) {
            if (!pourContainer.isEmpty() && bottlesPerCraft > 0) {
                specs.add(new IngredientSpec(Ingredient.of(pourContainer), bottlesPerCraft));
            }
        } else if (!recipeFluidIn.isEmpty()) {
            // Non-water fluid is represented by the bottled item produced by the
            // preceding Kettle recipe. Empty input bottles can be reused for output.
            if (inputPourOutput.isEmpty() || inputBottlesPerCraft <= 0) return null;
            specs.add(new IngredientSpec(Ingredient.of(inputPourOutput), inputBottlesPerCraft));
            int reusable = sameItem(inputPourContainer, pourContainer)
                    ? Math.min(inputBottlesPerCraft, bottlesPerCraft) : 0;
            int extraContainers = Math.max(0, bottlesPerCraft - reusable);
            if (extraContainers > 0 && !pourContainer.isEmpty()) {
                specs.add(new IngredientSpec(Ingredient.of(pourContainer), extraContainers));
            }
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
        this.craftDone = false;
        this.filledInputSlots.clear();
        this.placedContainerSlot = false;
        this.machineMutated = false;
        this.nativeInputPhase = NativeInputPhase.NONE;
        this.deferredOutputContainers = ItemStack.EMPTY;
        this.pendingNativeSolidMats.clear();
        this.nativeInputRefund = ItemStack.EMPTY;
        this.nativeOutputContainerRefund = ItemStack.EMPTY;

        forceChunkLoad(true);
        if (!myLevel.hasChunkAt(myPos)) return false;

        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isFRKettleBE(be)) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] BE not found at {}", myPos);
            player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
            forceChunkLoad(false);
            return false;
        }

        probeReflection();

        if (!isHeated(be)) {
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.no_heat"));
            forceChunkLoad(false);
            return false;
        }

        ItemStackHandler inventory = getInventory(be);
        if (inventory == null || inventory.getSlots() < 3) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Inventory missing or too small: {}",
                    inventory != null ? inventory.getSlots() : -1);
            forceChunkLoad(false);
            return false;
        }

        IFluidHandler fluidHandler = getFluidHandler(be);
        if (fluidHandler == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Fluid handler missing");
            forceChunkLoad(false);
            return false;
        }

        // ── Pre-validation: verify solid input slots are free before mutating ──
        List<ItemStack> remainingMaterials = new ArrayList<>();
        for (ItemStack material : materials) {
            if (material != null && !material.isEmpty()) remainingMaterials.add(material.copy());
        }
        List<ItemStack> solidMats = new ArrayList<>();
        List<ItemStack> containerMats = new ArrayList<>();
        List<ItemStack> inputFluidMats = new ArrayList<>();
        boolean waterInput = isWaterInput();
        int requiredInputBottles = waterInput ? 0 : inputBottlesPerCraft;
        int reusableInputContainers = !waterInput && sameItem(inputPourContainer, pourContainer)
                ? Math.min(inputBottlesPerCraft, bottlesPerCraft) : 0;
        int requiredContainers = Math.max(0, bottlesPerCraft - reusableInputContainers);

        // Assign the real recipe ingredients first and only those stacks to slots
        // 0/1. Bottled fluid and pouring containers are separate material roles;
        // they must never fall through into an ingredient slot.
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            List<ItemStack> matched = takeMatchingMaterials(remainingMaterials, ingredient, 1);
            if (countItems(matched) != 1) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Missing solid ingredient for recipe {}",
                        recipe.getId());
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
            solidMats.add(matched.get(0));
        }

        if (requiredInputBottles > 0 && !inputPourOutput.isEmpty()) {
            inputFluidMats.addAll(takeMatchingMaterials(remainingMaterials,
                    Ingredient.of(inputPourOutput), requiredInputBottles));
            requiredInputBottles -= countItems(inputFluidMats);
        }
        if (requiredContainers > 0 && !pourContainer.isEmpty()) {
            containerMats.addAll(takeMatchingMaterials(remainingMaterials,
                    Ingredient.of(pourContainer), requiredContainers));
            requiredContainers -= countItems(containerMats);
        }
        remainingMaterials.removeIf(ItemStack::isEmpty);
        if (requiredInputBottles > 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Missing {} bottled input fluids for recipe {}",
                    requiredInputBottles, recipe.getId());
            refundAll(materials);
            forceChunkLoad(false);
            return false;
        }
        if (requiredContainers > 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Missing {} pouring containers for recipe {}",
                    requiredContainers, recipe.getId());
            refundAll(materials);
            forceChunkLoad(false);
            return false;
        }
        if (!remainingMaterials.isEmpty()) {
            RSIntegrationMod.LOGGER.warn(
                    "[RSI-FRKettle] Refusing unclassified materials for recipe {}: {}",
                    recipe.getId(), remainingMaterials);
            refundAll(materials);
            forceChunkLoad(false);
            return false;
        }
        if (solidMats.size() > 2) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Too many solid ingredients: {}", solidMats.size());
            refundAll(materials);
            forceChunkLoad(false);
            return false;
        }
        RSIntegrationMod.LOGGER.debug(
                "[RSI-FRKettle] Material slots for {}: solids={} bottledFluid={} containers={}",
                recipe.getId(), solidMats, inputFluidMats, containerMats);
        for (int i = 0; i < 2; i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Input slot {} already occupied", i);
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
        }

        // Put the already-planned containers into the kettle's real container
        // slot. Its tick logic consumes them while moving bottled output to slot 4.
        if (!containerMats.isEmpty() || !inputFluidMats.isEmpty()) {
            if (inventory.getSlots() < 5 || !inventory.getStackInSlot(3).isEmpty()
                    || !inventory.getStackInSlot(4).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Container/output slot occupied at {}", myPos);
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
        }

        FluidStack tankFluid = fluidHandler.getFluidInTank(0);
        FluidStack fluidToFill = FluidStack.EMPTY;
        if (requiresNativeBottledInput(recipeFluidIn)) {
            // Non-water inputs must pass through Farmer's Respite's own container
            // slot. Starting from a non-empty tank would make ownership and the
            // number of consumed bottles ambiguous, so preserve it and fail closed.
            if (!tankFluid.isEmpty()) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FRKettle] Native bottled input requires an empty tank; preserving {}",
                        tankFluid);
                player.sendSystemMessage(Component.translatable(
                        "rsi.farmersrespite.kettle.native_input_requires_empty"));
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
        } else if (!recipeFluidIn.isEmpty()) {
            if (!tankFluid.isEmpty() && tankFluid.getFluid() != recipeFluidIn.getFluid()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Tank contains {}, recipe requires {}; preserving tank",
                        tankFluid.getFluid(), recipeFluidIn.getFluid());
                player.sendSystemMessage(Component.translatable("rsi.farmersrespite.kettle.wrong_fluid"));
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
            int missing = Math.max(0, recipeFluidIn.getAmount() - tankFluid.getAmount());
            if (missing > 0) {
                fluidToFill = new FluidStack(recipeFluidIn.getFluid(), missing);
                int accepted = fluidHandler.fill(fluidToFill.copy(), IFluidHandler.FluidAction.SIMULATE);
                if (accepted < missing) {
                    RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Tank cannot accept required fluid: {} of {}",
                            accepted, missing);
                    refundAll(materials);
                    forceChunkLoad(false);
                    return false;
                }
            }
        }

        int filled = 0;
        if (!fluidToFill.isEmpty()) {
            filled = MachineWaterSupply.fill("farmersrespite_kettle", fluidHandler, fluidToFill.getAmount(),
                    storageEndpoint(), player);
            if (filled < fluidToFill.getAmount()) {
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
            machineMutated = true;
        }

        if (!inputFluidMats.isEmpty()) {
            // Farmer's Respite only starts after the native bottled input has
            // emptied into the tank. Keep ingredients out of slots 0/1 until
            // that transition is observed; otherwise the kettle sees no fluid
            // on its first tick and does not start.
            pendingNativeSolidMats.addAll(solidMats.stream().map(ItemStack::copy).toList());
            ItemStack bottledInput = mergeStacks(inputFluidMats);
            if (bottledInput.isEmpty() || bottledInput.getCount() != inputBottlesPerCraft) {
                RSIntegrationMod.LOGGER.warn(
                        "[RSI-FRKettle] Bottled input variants cannot share the native container slot: {}",
                        inputFluidMats);
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
            inventory.setStackInSlot(3, bottledInput);
            deferredOutputContainers = mergeStacks(containerMats);
            nativeInputRefund = bottledInput.copy();
            nativeOutputContainerRefund = deferredOutputContainers.copy();
            nativeInputPhase = NativeInputPhase.EMPTYING_INPUT;
            placedContainerSlot = true;
            machineMutated = true;
        } else if (!containerMats.isEmpty()) {
            placeSolidIngredients(inventory, solidMats);
            inventory.setStackInSlot(3, mergeStacks(containerMats));
            placedContainerSlot = true;
            machineMutated = true;
        } else {
            placeSolidIngredients(inventory, solidMats);
        }

        be.setChanged();
        markCraftStarted();
        return true;
    }

    @Override
    protected IBatchDelegate.CraftObservation observeMachineCraft(
            @NotNull ServerLevel level, @NotNull BlockEntity be) {
        if (nativeInputPhase == NativeInputPhase.NONE
                || nativeInputPhase == NativeInputPhase.BOTTLING_OUTPUT) {
            return super.observeMachineCraft(level, be);
        }
        if (!isFRKettleBE(be)) return failObservation("kettle block entity changed");
        ItemStackHandler inventory = getInventory(be);
        IFluidHandler fluidHandler = getFluidHandler(be);
        if (inventory == null || inventory.getSlots() < 5 || fluidHandler == null) {
            return failObservation("kettle inventory or fluid tank unavailable");
        }

        FluidStack tank = fluidHandler.getFluidInTank(0);
        if (nativeInputPhase == NativeInputPhase.EMPTYING_INPUT) {
            ItemStack bottledInput = inventory.getStackInSlot(3);
            ItemStack returnedContainers = inventory.getStackInSlot(4);
            boolean filled = !tank.isEmpty()
                    && tank.getFluid() == recipeFluidIn.getFluid()
                    && tank.getAmount() >= recipeFluidIn.getAmount();
            boolean returned = bottledInput.isEmpty()
                    && matchesPouringItem(inputPourContainer, returnedContainers)
                    && returnedContainers.getCount() >= inputBottlesPerCraft;
            if (filled && returned) {
                if (!placePendingNativeSolids(inventory, be)) {
                    return failObservation("kettle ingredient slot became occupied during native input");
                }
                nativeInputPhase = NativeInputPhase.BREWING;
                RSIntegrationMod.LOGGER.debug(
                        "[RSI-FRKettle] Native input emptied for {}; tank={} returned={}",
                        recipe.getId(), tank, returnedContainers);
            }
            return workingObservation();
        }

        boolean brewed = !tank.isEmpty()
                && tank.getFluid() == recipeFluidOut.getFluid()
                && tank.getAmount() >= recipeFluidOut.getAmount();
        if (!brewed) return workingObservation();

        ItemStack returnedContainers = takeOwnedSlot(inventory, 4);
        ItemStack outputContainers = deferredOutputContainers.copy();
        int deferredCount = matchesPouringItem(pourContainer, outputContainers)
                ? Math.min(bottlesPerCraft, outputContainers.getCount()) : 0;
        int required = bottlesPerCraft - deferredCount;
        if (matchesPouringItem(pourContainer, returnedContainers)) {
            int reused = Math.min(required, returnedContainers.getCount());
            ItemStack reusable = returnedContainers.copyWithCount(reused);
            returnedContainers.shrink(reused);
            outputContainers = appendSameStack(outputContainers, reusable);
            required -= reused;
        }
        if (outputContainers.isEmpty() || outputContainers.getCount() < bottlesPerCraft
                || !matchesPouringItem(pourContainer, outputContainers) || required > 0) {
            return failObservation("native bottled input did not return enough output containers");
        }
        deferredOutputContainers = ItemStack.EMPTY;
        inventory.setStackInSlot(3, outputContainers.copyWithCount(bottlesPerCraft));
        nativeInputPhase = NativeInputPhase.BOTTLING_OUTPUT;
        be.setChanged();
        RSIntegrationMod.LOGGER.debug(
                "[RSI-FRKettle] Native brew finished for {}; moved {} to output-container slot",
                recipe.getId(), outputContainers);
        return workingObservation();
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isFRKettleBE(be)) return false;

        ItemStackHandler inventory = getInventory(be);
        if (inventory == null || inventory.getSlots() < 5) return false;
        ItemStack output = inventory.getStackInSlot(4);
        int expected = pourOutput.isEmpty() ? 1 : pourOutput.getCount() * bottlesPerCraft;
        return !output.isEmpty() && matchesPouringItem(pourOutput, output)
                && output.getCount() >= expected;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        ItemStackHandler inventory = getInventory(be);
        if (inventory == null || inventory.getSlots() < 5) return ItemStack.EMPTY;

        // KettleItemHandler rejects generic extraction from its result slot. The
        // slot was verified empty before this operation started, so every item
        // now present in it belongs to this craft and can be transferred directly.
        ItemStack result = takeOwnedSlot(inventory, 4);
        if (!result.isEmpty()) {
            be.setChanged();
            craftDone = true;
            RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] Collected {} x{} tag={}",
                    result.getItem(), result.getCount(), result.getTag());
        }
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        ItemStackHandler inventory = getInventory(be);
        if (inventory != null) {
            boolean nativePath = nativeInputPhase != NativeInputPhase.NONE;
            if (nativePath) clearNativeTank(be);
            clearAndRefund(inventory, be, true);
        }
        forceChunkLoad(false);
        craftDone = false;
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        forceChunkLoad(false);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        ItemStackHandler inventory = (be != null && isFRKettleBE(be)) ? getInventory(be) : null;
        if (inventory != null) clearAndRefund(inventory, be, false);
        craftDone = false;
        filledInputSlots.clear();
        placedContainerSlot = false;
        machineMutated = false;
        nativeInputPhase = NativeInputPhase.NONE;
        deferredOutputContainers = ItemStack.EMPTY;
        pendingNativeSolidMats.clear();
        nativeInputRefund = ItemStack.EMPTY;
        nativeOutputContainerRefund = ItemStack.EMPTY;
        network = null;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // ── plan helpers ──

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {}

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.farmersrespite.kettle.heat_warning"));
        warnings.add(Component.translatable("rsi.farmersrespite.kettle.container_warning"));
        return warnings;
    }

    // ── reflection ──

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            getInventoryMethod = FRReflection.kettleBEClass.getMethod("getInventory");
            isHeatedMethod = FRReflection.kettleBEClass.getMethod("isHeated");

            getFluidInMethod = FRReflection.kettleRecipeClass.getMethod("getFluidIn");
            getFluidOutMethod = FRReflection.kettleRecipeClass.getMethod("getFluidOut");
            getBrewTimeMethod = FRReflection.kettleRecipeClass.getMethod("getBrewTime");

            if (FRReflection.kettlePouringRecipeClass != null) {
                pourGetFluidMethod = FRReflection.kettlePouringRecipeClass.getMethod("getFluid");
                pourGetAmountMethod = FRReflection.kettlePouringRecipeClass.getMethod("getAmount");
                pourGetContainerMethod = FRReflection.kettlePouringRecipeClass.getMethod("getContainer");
                pourGetOutputMethod = FRReflection.kettlePouringRecipeClass.getMethod("getOutput");
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Reflection probe failed", e);
        }
    }

    private static boolean isFRKettleBE(BlockEntity be) {
        return FRReflection.kettleBEClass != null
                && FRReflection.kettleBEClass.isInstance(be);
    }

    private static IFluidHandler getFluidHandler(BlockEntity be) {
        return be.getCapability(
                ForgeCapabilities.FLUID_HANDLER)
                .resolve().orElse(null);
    }

    private boolean isWaterInput() {
        return recipeFluidIn != null && !recipeFluidIn.isEmpty()
                && (recipeFluidIn.getFluid() == Fluids.WATER
                || recipeFluidIn.getFluid() == Fluids.FLOWING_WATER);
    }

    static boolean requiresNativeBottledInput(FluidStack fluid) {
        return fluid != null && !fluid.isEmpty()
                && fluid.getFluid() != Fluids.WATER
                && fluid.getFluid() != Fluids.FLOWING_WATER;
    }

    private static boolean sameItem(ItemStack first, ItemStack second) {
        return first != null && second != null && !first.isEmpty() && !second.isEmpty()
                && ItemStack.isSameItemSameTags(first, second);
    }

    static boolean matchesPouringItem(ItemStack expected, ItemStack actual) {
        return expected != null && !expected.isEmpty() && actual != null && !actual.isEmpty()
                && Ingredient.of(expected).test(actual);
    }

    static ItemStack takeOwnedSlot(ItemStackHandler inventory, int slot) {
        if (inventory == null || slot < 0 || slot >= inventory.getSlots()) return ItemStack.EMPTY;
        ItemStack result = inventory.getStackInSlot(slot).copy();
        if (!result.isEmpty()) inventory.setStackInSlot(slot, ItemStack.EMPTY);
        return result;
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
        for (ItemStack stack : stacks) count += stack.getCount();
        return count;
    }

    private static ItemStack mergeStacks(List<ItemStack> stacks) {
        ItemStack merged = ItemStack.EMPTY;
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) continue;
            if (merged.isEmpty()) merged = stack.copy();
            else if (sameItem(merged, stack)) merged.grow(stack.getCount());
        }
        return merged;
    }

    private static ItemStack appendSameStack(ItemStack first, ItemStack second) {
        if (first == null || first.isEmpty()) return second == null ? ItemStack.EMPTY : second.copy();
        if (second == null || second.isEmpty()) return first.copy();
        if (!sameItem(first, second)) return ItemStack.EMPTY;
        ItemStack result = first.copy();
        result.grow(second.getCount());
        return result;
    }

    private static ItemStackHandler getInventory(BlockEntity be) {
        probeReflection();
        if (getInventoryMethod != null) {
            try {
                Object result = getInventoryMethod.invoke(be);
                if (result instanceof ItemStackHandler h) return h;
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] reflection invoke failed", e); }
        }
        return null;
    }

    private boolean isHeated(BlockEntity be) {
        if (isHeatedMethod == null) return true;
        try {
            return (boolean) isHeatedMethod.invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] isHeated invoke failed", e);
        }
        return true;
    }

    private static FluidStack getFluidIn(Recipe<?> recipe) {
        if (getFluidInMethod != null) {
            try {
                return (FluidStack) getFluidInMethod.invoke(recipe);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] reflection invoke failed", e); }
        }
        return FluidStack.EMPTY;
    }

    private static FluidStack getFluidOut(Recipe<?> recipe) {
        if (getFluidOutMethod != null) {
            try {
                return (FluidStack) getFluidOutMethod.invoke(recipe);
            } catch (Exception e) { RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] reflection invoke failed", e); }
        }
        return FluidStack.EMPTY;
    }

    // ── cleanup ──

    /** Refund an entire material list (used on start failure). */
    private void refundAll(List<ItemStack> materials) {
        if (usingSharedLedger) return; // shared ledger refunds on abort
        for (ItemStack mat : materials) {
            if (!mat.isEmpty()) refund(mat);
        }
    }

    private void clearAndRefund(ItemStackHandler inventory, BlockEntity be, boolean failed) {
        if (!machineMutated) return;
        boolean nativePath = nativeInputPhase != NativeInputPhase.NONE;
        for (int i : new ArrayList<>(filledInputSlots)) {
            if (i < 0 || i >= inventory.getSlots()) continue;
            ItemStack s = inventory.getStackInSlot(i);
            if (!s.isEmpty()) {
                inventory.setStackInSlot(i, ItemStack.EMPTY);
                if (!usingSharedLedger) refund(s);
            }
        }
        if (placedContainerSlot && inventory.getSlots() > 3) {
            ItemStack containers = inventory.getStackInSlot(3);
            if (!containers.isEmpty()) {
                inventory.setStackInSlot(3, ItemStack.EMPTY);
                if (!nativePath && !usingSharedLedger) refund(containers);
            }
        }
        if (inventory.getSlots() > 4) {
            ItemStack output = inventory.getStackInSlot(4);
            if (!output.isEmpty()) {
                inventory.setStackInSlot(4, ItemStack.EMPTY);
                if (!nativePath && !usingSharedLedger) refund(output);
            }
        }
        if (nativePath && failed && !usingSharedLedger) {
            if (!nativeInputRefund.isEmpty()) refund(nativeInputRefund);
            if (!nativeOutputContainerRefund.isEmpty()) refund(nativeOutputContainerRefund);
            for (ItemStack pending : pendingNativeSolidMats) {
                if (!pending.isEmpty()) refund(pending);
            }
        }
        be.setChanged();
        filledInputSlots.clear();
        placedContainerSlot = false;
        machineMutated = false;
        nativeInputPhase = NativeInputPhase.NONE;
        deferredOutputContainers = ItemStack.EMPTY;
        pendingNativeSolidMats.clear();
        nativeInputRefund = ItemStack.EMPTY;
        nativeOutputContainerRefund = ItemStack.EMPTY;
    }

    private void clearNativeTank(BlockEntity be) {
        IFluidHandler fluid = getFluidHandler(be);
        if (fluid == null) return;
        FluidStack current = fluid.getFluidInTank(0);
        if (!current.isEmpty()) {
            fluid.drain(current.copy(), IFluidHandler.FluidAction.EXECUTE);
        }
    }

    private void placeSolidIngredients(ItemStackHandler inventory, List<ItemStack> solids) {
        for (int i = 0; i < solids.size(); i++) {
            inventory.setStackInSlot(i, solids.get(i).copyWithCount(1));
            filledInputSlots.add(i);
            machineMutated = true;
        }
    }

    private boolean placePendingNativeSolids(ItemStackHandler inventory, BlockEntity be) {
        if (pendingNativeSolidMats.isEmpty()) return true;
        for (int i = 0; i < pendingNativeSolidMats.size(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) return false;
        }
        placeSolidIngredients(inventory, pendingNativeSolidMats);
        pendingNativeSolidMats.clear();
        be.setChanged();
        return true;
    }

    private void refund(ItemStack stack) {
        ItemStack leftover = insertIntoStorage(player, stack, false);
        if (!leftover.isEmpty() && player != null)
            ItemHandlerHelper.giveItemToPlayer(player, leftover);
    }

    private void forceChunkLoad(boolean load) {
        forceMachineChunk(myLevel, myPos, load);
    }

    private record PouringDescriptor(ItemStack container, ItemStack output, int amount, int bottles) {}

    private enum NativeInputPhase {
        NONE,
        EMPTYING_INPUT,
        BREWING,
        BOTTLING_OUTPUT
    }
}
