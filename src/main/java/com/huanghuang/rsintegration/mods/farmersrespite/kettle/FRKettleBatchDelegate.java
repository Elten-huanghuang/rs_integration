package com.huanghuang.rsintegration.mods.farmersrespite.kettle;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.IBatchDelegate;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.ItemStackHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Delegate for the Farmer's Respite Kettle (farmersrespite:kettle).
 * <p>
 * The FR kettle brews solid ingredients with an input fluid to produce
 * an output fluid.  Both fluids use standard Forge fluid handling.
 * <p>
 * Inventory layout (5 slots):
 *   0-1: input ingredients  2: drink display
 *   3: container slot        4: output slot
 */
public final class FRKettleBatchDelegate implements IBatchDelegate {

    private static final String BE_CLASS =
            "umpaz.farmersrespite.common.block.entity.KettleBlockEntity";
    private static final String RECIPE_CLASS =
            "umpaz.farmersrespite.common.crafting.KettleRecipe";

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private FluidStack recipeFluidIn;
    private FluidStack recipeFluidOut;
    private INetwork network;
    private boolean craftDone;
    private boolean usingSharedLedger;

    // ── reflection cache ──
    private static volatile Class<?> beClass;
    private static volatile Class<?> recipeClass;
    private static volatile Method getInventoryMethod;
    private static volatile Method isHeatedMethod;
    private static volatile Method getFluidInMethod;
    private static volatile Method getFluidOutMethod;
    private static volatile Method getBrewTimeMethod;
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
        if (found == null || !found.getClass().getName().equals(RECIPE_CLASS)) {
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
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.isEmpty() && recipeFluidIn.isEmpty()) return null;

        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
        }

        // Include the input fluid as a bucket/bottle ingredient
        if (!recipeFluidIn.isEmpty()) {
            Item bucket = recipeFluidIn.getFluid().getBucket();
            if (bucket != null && bucket != Items.AIR) {
                specs.add(new IngredientSpec(Ingredient.of(bucket), 1));
            }
        }

        return specs.isEmpty() ? null : specs;
    }

    @Override
    public boolean tryStartSingleCraft(ServerPlayer player) {
        List<IngredientSpec> specs = getRequiredMaterials();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        ExtractionLedger ledger = new ExtractionLedger();
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) return false;

        for (IngredientSpec spec : specs) {
            if (spec.isEmpty()) continue;
            ItemStack reserved = CraftPacketUtils.ensureMaterialAvailable(
                    player, myDim, myPos, spec.ingredient(), spec.count(), ledger);
            if (reserved.isEmpty()) {
                ledger.rollback(player);
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

    @Override
    public boolean tryStartWithMaterials(ServerPlayer player, List<ItemStack> materials,
                                         ExtractionLedger sharedLedger) {
        this.player = player;
        this.usingSharedLedger = true;
        this.craftDone = false;

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

        // Drain any leftover fluid from a previous craft
        FluidStack tankFluid = fluidHandler.getFluidInTank(0);
        if (!tankFluid.isEmpty()) {
            fluidHandler.drain(tankFluid, IFluidHandler.FluidAction.EXECUTE);
        }

        // Separate solid ingredients from fluid buckets
        List<ItemStack> solidMats = new ArrayList<>();
        ItemStack fluidBucket = ItemStack.EMPTY;

        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            // Detect fluid containers by checking if the item can hold/deliver fluid
            var fluidCap = mat.getCapability(
                    net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER_ITEM)
                    .resolve().orElse(null);
            if (fluidCap != null && !recipeFluidIn.isEmpty()) {
                // Try to drain the input fluid from this container into the tank
                FluidStack toFill = recipeFluidIn.copy();
                int filled = fluidHandler.fill(toFill, IFluidHandler.FluidAction.EXECUTE);
                if (filled > 0) {
                    // Drain the filled amount from the fluid container item
                    FluidStack drainedFromItem = fluidCap.drain(
                            new FluidStack(recipeFluidIn.getFluid(), filled),
                            IFluidHandler.FluidAction.EXECUTE);
                    // Return the empty container (bucket -> empty bucket)
                    ItemStack container = fluidCap.getContainer();
                    if (!container.isEmpty() && !usingSharedLedger && network != null) {
                        network.insertItem(container.copy(), container.getCount(), Action.PERFORM);
                    }
                    RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] Filled {}mb of {} into tank",
                            filled, recipeFluidIn.getFluid().getFluidType().getDescriptionId());
                    continue;
                }
            }
            solidMats.add(mat);
        }

        // If no fluid container was found among materials but we need fluid, try to fill directly
        if (fluidHandler.getFluidInTank(0).isEmpty() && !recipeFluidIn.isEmpty()) {
            int filled = fluidHandler.fill(recipeFluidIn.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (filled > 0) {
                RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] Direct filled {}mb into tank", filled);
            }
        }

        // Verify tank has the required fluid
        tankFluid = fluidHandler.getFluidInTank(0);
        if (tankFluid.isEmpty() || tankFluid.getFluid() != recipeFluidIn.getFluid()) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Tank fluid mismatch: expected {}, got {}",
                    recipeFluidIn.getFluid().getFluidType().getDescriptionId(),
                    tankFluid.isEmpty() ? "empty" : tankFluid.getFluid().getFluidType().getDescriptionId());
            player.sendSystemMessage(Component.translatable("rsi.farmersdelight.container_needed",
                    Component.translatable(recipeFluidIn.getFluid().getFluidType().getDescriptionId()).getString()));
            clearAndRefund(inventory, be);
            forceChunkLoad(false);
            return false;
        }

        // Place solid ingredients in input slots 0, 1
        int slot = 0;
        for (ItemStack mat : solidMats) {
            if (mat.isEmpty()) continue;
            if (slot >= 2) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Too many ingredients for input slots");
                clearAndRefund(inventory, be);
                forceChunkLoad(false);
                return false;
            }
            if (!inventory.getStackInSlot(slot).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Input slot {} already occupied", slot);
                clearAndRefund(inventory, be);
                forceChunkLoad(false);
                return false;
            }
            inventory.setStackInSlot(slot, mat.copyWithCount(1));
            slot++;
        }

        be.setChanged();
        return true;
    }

    @Override
    public boolean isCraftComplete(ServerLevel level) {
        BlockEntity be = level.getBlockEntity(myPos);
        if (be == null || !isFRKettleBE(be)) return false;

        // Check if output fluid has appeared in the tank
        IFluidHandler fluidHandler = getFluidHandler(be);
        if (fluidHandler == null) return false;
        FluidStack tankFluid = fluidHandler.getFluidInTank(0);

        if (tankFluid.isEmpty() || recipeFluidOut.isEmpty()) return false;
        return tankFluid.getFluid() == recipeFluidOut.getFluid();
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IFluidHandler fluidHandler = getFluidHandler(be);
        if (fluidHandler == null || recipeFluidOut.isEmpty()) return ItemStack.EMPTY;

        // Drain the output fluid
        FluidStack drained = fluidHandler.drain(recipeFluidOut.copy(), IFluidHandler.FluidAction.EXECUTE);
        be.setChanged();
        craftDone = true;

        if (drained.isEmpty()) return ItemStack.EMPTY;

        Item bucketItem = drained.getFluid().getBucket();
        if (bucketItem != null && bucketItem != Items.AIR) {
            return new ItemStack(bucketItem);
        }

        ItemStack filled = net.minecraftforge.fluids.FluidUtil.getFilledBucket(
                new FluidStack(drained.getFluid(), 1000));
        if (!filled.isEmpty()) return filled;

        RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Fluid {} has no bucket item, result lost",
                drained.getFluid().getFluidType().getDescriptionId());
        return ItemStack.EMPTY;
    }

    @Override
    public void onBatchFailed(ServerPlayer player, String reason) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be != null && isFRKettleBE(be)) {
            IFluidHandler fluidHandler = getFluidHandler(be);
            if (fluidHandler != null) {
                FluidStack tankFluid = fluidHandler.getFluidInTank(0);
                if (!tankFluid.isEmpty() && !recipeFluidOut.isEmpty()
                        && tankFluid.getFluid() == recipeFluidOut.getFluid()) {
                    ItemStack result = collectResult(player);
                    if (!result.isEmpty()) {
                        if (network == null)
                            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
                        if (network != null)
                            network.insertItem(result, result.getCount(), Action.PERFORM);
                    }
                }
            }
        }
        ItemStackHandler inventory = getInventory(be);
        if (inventory != null) clearAndRefund(inventory, be);
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        ItemStackHandler inventory = null;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be != null && isFRKettleBE(be)) {
            inventory = getInventory(be);
        }
        if (inventory != null) clearAndRefund(inventory, be);
        forceChunkLoad(false);
        craftDone = false;
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
        warnings.add(Component.translatable("rsi.youkaishomecoming.heat_warning").getString());
        return warnings;
    }

    // ── reflection ──

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        try {
            beClass = Class.forName(BE_CLASS);
            recipeClass = Class.forName(RECIPE_CLASS);

            getInventoryMethod = beClass.getMethod("getInventory");
            isHeatedMethod = beClass.getMethod("isHeated");

            getFluidInMethod = recipeClass.getMethod("getFluidIn");
            getFluidOutMethod = recipeClass.getMethod("getFluidOut");
            getBrewTimeMethod = recipeClass.getMethod("getBrewTime");
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-FRKettle] Reflection probe failed: {}", e.toString());
        }
    }

    private static boolean isFRKettleBE(BlockEntity be) {
        probeReflection();
        if (beClass != null && beClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (clazz.getName().equals(BE_CLASS)) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    @Nullable
    private static IFluidHandler getFluidHandler(BlockEntity be) {
        return be.getCapability(
                net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER)
                .resolve().orElse(null);
    }

    @Nullable
    private static ItemStackHandler getInventory(BlockEntity be) {
        probeReflection();
        if (getInventoryMethod != null) {
            try {
                Object result = getInventoryMethod.invoke(be);
                if (result instanceof ItemStackHandler h) return h;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private boolean isHeated(BlockEntity be) {
        if (isHeatedMethod == null) return true;
        try {
            return (boolean) isHeatedMethod.invoke(be);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] isHeated invoke failed: {}", e.toString());
        }
        return true;
    }

    @Nullable
    private static FluidStack getFluidIn(Recipe<?> recipe) {
        if (getFluidInMethod != null) {
            try {
                return (FluidStack) getFluidInMethod.invoke(recipe);
            } catch (Exception ignored) {}
        }
        return FluidStack.EMPTY;
    }

    @Nullable
    private static FluidStack getFluidOut(Recipe<?> recipe) {
        if (getFluidOutMethod != null) {
            try {
                return (FluidStack) getFluidOutMethod.invoke(recipe);
            } catch (Exception ignored) {}
        }
        return FluidStack.EMPTY;
    }

    // ── cleanup ──

    private void clearAndRefund(ItemStackHandler inventory, BlockEntity be) {
        for (int i = 0; i < 2; i++) {
            ItemStack s = inventory.getStackInSlot(i);
            if (!s.isEmpty()) {
                inventory.setStackInSlot(i, ItemStack.EMPTY);
                if (!usingSharedLedger) refund(s);
            }
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
            RSIntegrationMod.LOGGER.debug("[RSI-FRKettle] Chunk load failed: {}", e.toString());
        }
    }
}
