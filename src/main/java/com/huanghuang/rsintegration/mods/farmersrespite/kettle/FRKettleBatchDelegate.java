package com.huanghuang.rsintegration.mods.farmersrespite.kettle;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
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
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;

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
 * The input fluid (water) is treated as free: it is filled directly into the
 * kettle's tank, so the player is not required to supply a water container from
 * RS. Only the solid ingredients and the pouring containers (e.g. glass bottles)
 * are drawn from the network.
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
    // Solid input slots we actually wrote this craft (for precise rollback).
    private final List<Integer> filledInputSlots = new ArrayList<>();

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

        // Resolve the pouring recipe that bottles this output fluid.
        resolvePouring(level);
        return true;
    }

    /** Find a KettlePouringRecipe whose fluid matches this recipe's output. */
    private void resolvePouring(ServerLevel level) {
        this.pourContainer = ItemStack.EMPTY;
        this.pourOutput = ItemStack.EMPTY;
        this.pourAmount = 0;
        this.bottlesPerCraft = 0;
        if (recipeFluidOut.isEmpty() || FRReflection.kettlePouringRecipeClass == null) return;
        probeReflection();
        if (pourGetFluidMethod == null) return;

        for (Recipe<?> r : level.getRecipeManager().getRecipes()) {
            if (!FRReflection.kettlePouringRecipeClass.isInstance(r)) continue;
            try {
                Object fluid = pourGetFluidMethod.invoke(r);
                if (fluid != recipeFluidOut.getFluid()) continue;
                int amount = (int) pourGetAmountMethod.invoke(r);
                ItemStack container = (ItemStack) pourGetContainerMethod.invoke(r);
                ItemStack output = (ItemStack) pourGetOutputMethod.invoke(r);
                if (amount <= 0 || output == null || output.isEmpty()) continue;
                this.pourAmount = amount;
                this.pourContainer = container == null ? ItemStack.EMPTY : container.copy();
                this.pourOutput = output.copy();
                this.bottlesPerCraft = Math.max(1, recipeFluidOut.getAmount() / amount);
                return;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] pouring recipe probe failed", e);
            }
        }
        RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] No KettlePouringRecipe found for output fluid {}",
                recipeFluidOut.getFluid().getFluidType().getDescriptionId());
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<Ingredient> ingredients = recipe.getIngredients();

        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }

        // Input fluid (water) is free — no container drawn from RS. Only the
        // pouring container(s) needed to bottle the output are required.
        if (!pourContainer.isEmpty() && bottlesPerCraft > 0) {
            specs.add(new IngredientSpec(Ingredient.of(pourContainer), bottlesPerCraft));
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
            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
            if (this.network == null) return false;

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
                        network.insertItem(mat.copy(), mat.getCount(), Action.PERFORM);
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

        forceChunkLoad(true);
        myLevel.getChunk(myPos);

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
        List<ItemStack> solidMats = new ArrayList<>();
        List<ItemStack> containerMats = new ArrayList<>();
        int requiredContainers = bottlesPerCraft;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            if (requiredContainers > 0 && !pourContainer.isEmpty()
                    && ItemStack.isSameItemSameTags(mat, pourContainer)) {
                int containerCount = Math.min(requiredContainers, mat.getCount());
                containerMats.add(mat.copyWithCount(containerCount));
                requiredContainers -= containerCount;
                if (mat.getCount() > containerCount) {
                    solidMats.add(mat.copyWithCount(mat.getCount() - containerCount));
                }
            } else {
                solidMats.add(mat);
            }
        }
        if (requiredContainers > 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Missing {} pouring containers for recipe {}",
                    requiredContainers, recipe.getId());
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
        for (int i = 0; i < solidMats.size(); i++) {
            if (!inventory.getStackInSlot(i).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Input slot {} already occupied", i);
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
        }

        // Put the already-planned containers into the kettle's real container
        // slot. Its tick logic consumes them while moving bottled output to slot 4.
        if (!containerMats.isEmpty()) {
            if (inventory.getSlots() < 5 || !inventory.getStackInSlot(3).isEmpty()
                    || !inventory.getStackInSlot(4).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Container/output slot occupied at {}", myPos);
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
        }

        // Drain any leftover fluid from a previous craft.
        FluidStack tankFluid = fluidHandler.getFluidInTank(0);
        if (!tankFluid.isEmpty()) {
            fluidHandler.drain(tankFluid, IFluidHandler.FluidAction.EXECUTE);
        }

        // Input fluid is FREE **only when it is water** (the kettle's default,
        // player-fillable-from-any-water-source input). Recipes whose input is a
        // non-water fluid (milk, etc.) must NOT get it for free — that would let
        // the player conjure arbitrary fluids. For those we fail with a clear
        // message rather than silently gifting the fluid.
        if (!recipeFluidIn.isEmpty()) {
            boolean isWater = recipeFluidIn.getFluid() == net.minecraft.world.level.material.Fluids.WATER
                    || recipeFluidIn.getFluid() == net.minecraft.world.level.material.Fluids.FLOWING_WATER;
            if (!isWater) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Non-water input fluid {} is not free; aborting",
                        recipeFluidIn.getFluid());
                player.sendSystemMessage(Component.translatable("rsi.farmersrespite.kettle.non_water_input"));
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
            int filled = fluidHandler.fill(recipeFluidIn.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (filled < recipeFluidIn.getAmount()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Tank rejected free input fluid: filled {} of {}",
                        filled, recipeFluidIn.getAmount());
                if (filled > 0) {
                    fluidHandler.drain(new FluidStack(recipeFluidIn.getFluid(), filled),
                            IFluidHandler.FluidAction.EXECUTE);
                }
                refundAll(materials);
                forceChunkLoad(false);
                return false;
            }
        }

        // Place solid ingredients in input slots 0, 1.
        for (int i = 0; i < solidMats.size(); i++) {
            inventory.setStackInSlot(i, solidMats.get(i).copyWithCount(1));
            filledInputSlots.add(i);
        }

        if (!containerMats.isEmpty()) {
            ItemStack containers = containerMats.get(0).copy();
            for (int i = 1; i < containerMats.size(); i++) {
                containers.grow(containerMats.get(i).getCount());
            }
            inventory.setStackInSlot(3, containers);
        }

        be.setChanged();
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isFRKettleBE(be)) return false;

        ItemStackHandler inventory = getInventory(be);
        if (inventory == null || inventory.getSlots() < 5) return false;
        ItemStack output = inventory.getStackInSlot(4);
        int expected = pourOutput.isEmpty() ? 1 : pourOutput.getCount() * bottlesPerCraft;
        return !output.isEmpty() && output.getCount() >= expected;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        ItemStackHandler inventory = getInventory(be);
        if (inventory == null || inventory.getSlots() < 5) return ItemStack.EMPTY;

        ItemStack result = inventory.extractItem(4, 64, false);
        if (!result.isEmpty()) {
            be.setChanged();
            craftDone = true;
        }
        return result;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (isFRKettleBE(be)) {
            ItemStackHandler inventory = getInventory(be);
            if (inventory != null && inventory.getSlots() >= 5) {
                ItemStack result = inventory.extractItem(4, 64, false);
                if (!result.isEmpty()) refund(result);
            }
        }
        ItemStackHandler inventory = getInventory(be);
        if (inventory != null) clearAndRefund(inventory, be);
        forceChunkLoad(false);
        craftDone = false;
    }

    @Override
    public void onBatchFinished(@NotNull ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        ItemStackHandler inventory = (be != null && isFRKettleBE(be)) ? getInventory(be) : null;
        if (inventory != null) clearAndRefund(inventory, be);
        forceChunkLoad(false);
        craftDone = false;
        filledInputSlots.clear();
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

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.farmersrespite.kettle.heat_warning").getString());
        warnings.add(Component.translatable("rsi.farmersrespite.kettle.container_warning").getString());
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
                net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER)
                .resolve().orElse(null);
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

    private void clearAndRefund(ItemStackHandler inventory, BlockEntity be) {
        for (int i = 0; i < 2; i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!s.isEmpty()) {
                inventory.setStackInSlot(i, ItemStack.EMPTY);
                if (!usingSharedLedger) refund(s);
            }
        }
        ItemStack containers = inventory.getStackInSlot(3);
        if (!containers.isEmpty()) {
            inventory.setStackInSlot(3, ItemStack.EMPTY);
            if (!usingSharedLedger) refund(containers);
        }
        ItemStack output = inventory.getStackInSlot(4);
        if (!output.isEmpty()) {
            inventory.setStackInSlot(4, ItemStack.EMPTY);
            refund(output);
        }
        be.setChanged();
    }

    private void refund(ItemStack stack) {
        if (network != null) {
            ItemStack leftover = network.insertItem(stack.copy(), stack.getCount(), Action.PERFORM);
            if (!leftover.isEmpty() && player != null)
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
        } else if (player != null) {
            ItemHandlerHelper.giveItemToPlayer(player, stack.copy());
        }
    }

    private void forceChunkLoad(boolean load) {
        try {
            int cx = myPos.getX() >> 4;
            int cz = myPos.getZ() >> 4;
            ForgeChunkManager.forceChunk(myLevel, RSIntegrationMod.MOD_ID, myPos, cx, cz, load, true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] Chunk load failed", e);
        }
    }
}
