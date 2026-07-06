package com.huanghuang.rsintegration.mods.youkaishomecoming.kettle;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class KettleBatchDelegate extends AbstractBatchDelegate {

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Recipe<?> recipe;
    private boolean craftDone;

    // -- reflection cache --
    /** KettleBlockEntity.fluids -- public final BaseTank */
    private static volatile Field fluidsField;
    /** KettleRecipe.input -- public final List<Ingredient> */
    private static volatile Field inputField;
    /** KettleRecipe.result -- public FluidStack */
    private static volatile Field resultField;
    /** HeatableBlockEntity.isHeated(Level, BlockPos) */
    private static volatile Method isHeatedMethod;
    /** FluidItemTile.getItemHandler() -> SimpleContainer */
    private static volatile Method getItemHandlerMethod;
    /** FluidItemTile.getFluidHandler() -> BaseTank */
    private static volatile Method getFluidHandlerMethod;
    /** YHFluid.type -> IYHFluidHolder (public final) */
    private static volatile Field yhFluidTypeField;
    /** IYHFluidHolder.amount() -> int */
    private static volatile Method holderAmountMethod;
    /** IYHFluidHolder.asStack(int) -> ItemStack */
    private static volatile Method holderAsStackMethod;
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
        if (found == null || YHKReflection.kettleRecipeClass == null || !YHKReflection.kettleRecipeClass.isInstance(found)) {
            player.sendSystemMessage(Component.translatable("rsi.generic.error.recipe_not_found", recipeId.toString()));
            return false;
        }
        this.recipe = found;
        this.craftDone = false;
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<Ingredient> ingredients = getRecipeInput();
        if (ingredients.isEmpty()) return null;
        List<IngredientSpec> specs = new ArrayList<>();
        for (Ingredient ing : ingredients) {
            if (!ing.isEmpty()) specs.add(new IngredientSpec(ing, 1));
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

        BlockEntity be = findKettleBE();
        if (be == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] BE not found at {}", myPos);
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

        SimpleContainer items = getItemHandler(be);
        if (items == null || items.getContainerSize() < 4) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Item handler missing or too small: {}",
                    items != null ? items.getContainerSize() : -1);
            forceChunkLoad(false);
            return false;
        }

        // Ensure there's water in the fluid tank (needed for heating)
        IFluidHandler fluidHandler = getFluidHandler(be);
        if (fluidHandler == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Fluid handler missing");
            forceChunkLoad(false);
            return false;
        }
        if (!ensureWater(fluidHandler, be)) {
            forceChunkLoad(false);
            return false;
        }

        // Insert ingredients into the 4 slots
        int slot = 0;
        for (ItemStack mat : materials) {
            if (mat.isEmpty()) continue;
            if (slot >= items.getContainerSize()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Too many ingredients for kettle slots");
                clearSlots(items, be);
                forceChunkLoad(false);
                return false;
            }
            ItemStack single = mat.copyWithCount(1);
            if (!items.getItem(slot).isEmpty()) {
                RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Slot {} already occupied", slot);
                clearSlots(items, be);
                forceChunkLoad(false);
                return false;
            }
            items.setItem(slot, single);
            slot++;
        }

        be.setChanged();
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isKettleBE(be)) return false;

        // Check if result fluid is present in the tank.
        // Input items may still be visible in the UI while the kettle
        // processes them -- the fluid appearing is the true signal.
        IFluidHandler fluidHandler = getFluidHandler(be);
        if (fluidHandler == null) return false;
        FluidStack tankFluid = fluidHandler.getFluidInTank(0);
        FluidStack recipeResult = getRecipeResult();
        if (tankFluid.isEmpty() || recipeResult.isEmpty()) return false;
        if (tankFluid.getFluid() != recipeResult.getFluid()) return false;

        // For YHFluid, also verify there's enough for at least one container
        if (YHKReflection.yhFluidClass != null && YHKReflection.yhFluidClass.isInstance(tankFluid.getFluid())) {
            int minAmount = getYHFluidAmountPerContainer(tankFluid.getFluid());
            if (minAmount > 0 && tankFluid.getAmount() < minAmount) return false;
        }
        return true;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        IFluidHandler fluidHandler = getFluidHandler(be);
        if (fluidHandler == null) return ItemStack.EMPTY;

        FluidStack tankFluid = fluidHandler.getFluidInTank(0);
        if (tankFluid.isEmpty()) return ItemStack.EMPTY;

        // YHK custom fluid system (tea, sake, etc.):
        // YHFluid.getBucket() always returns AIR.  Must use
        // IYHFluidHolder.asStack(count) to obtain the filled container.
        if (YHKReflection.yhFluidClass != null && YHKReflection.yhFluidClass.isInstance(tankFluid.getFluid())) {
            return collectYHFluidResult(be, fluidHandler, tankFluid);
        }

        // Standard Forge fluid -- use bucket
        FluidStack recipeResult = getRecipeResult();
        FluidStack drained = fluidHandler.drain(recipeResult, IFluidHandler.FluidAction.EXECUTE);
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

        RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Fluid {} has no bucket item, result lost",
                drained.getFluid().getFluidType().getDescriptionId());
        return ItemStack.EMPTY;
    }

    private ItemStack collectYHFluidResult(BlockEntity be, IFluidHandler fluidHandler,
                                           FluidStack tankFluid) {
        int perContainer = getYHFluidAmountPerContainer(tankFluid.getFluid());
        int total = tankFluid.getAmount();
        int count = total / perContainer;
        if (count == 0) return ItemStack.EMPTY;

        // Validate the fluid -> item conversion BEFORE draining.
        // If asStack fails, the fluid stays in the tank so the player
        // can still extract it manually.
        ItemStack template = getYHFluidAsStack(tankFluid.getFluid(), 1);
        if (template.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] asStack(1) returned empty for {} (holder={}) -- fluid left in tank",
                    tankFluid.getFluid().getFluidType().getDescriptionId(),
                    getYHFluidHolderDesc(tankFluid.getFluid()));
            return ItemStack.EMPTY;
        }

        int toDrain = count * perContainer;
        FluidStack drained = fluidHandler.drain(
                new FluidStack(tankFluid.getFluid(), toDrain),
                IFluidHandler.FluidAction.EXECUTE);
        if (drained.isEmpty()) return ItemStack.EMPTY;

        be.setChanged();
        craftDone = true;

        if (count == 1) return template;

        // For count > 1, prefer asStack(n) so the mod can set its own
        // internal stack data; fall back to multiplying the template.
        ItemStack stacked = getYHFluidAsStack(tankFluid.getFluid(), count);
        if (!stacked.isEmpty()) return stacked;

        ItemStack result = template.copy();
        result.setCount(count);
        return result;
    }

    private static int getYHFluidAmountPerContainer(Object fluid) {
        if (yhFluidTypeField == null || holderAmountMethod == null) return 0;
        try {
            Object holder = yhFluidTypeField.get(fluid);
            if (holder != null) {
                return (int) holderAmountMethod.invoke(holder);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] YHK fluid amount reflection failed", e);
        }
        return 0;
    }

    private static ItemStack getYHFluidAsStack(Object fluid, int count) {
        if (yhFluidTypeField == null || holderAsStackMethod == null) return ItemStack.EMPTY;
        try {
            Object holder = yhFluidTypeField.get(fluid);
            if (holder != null) {
                return (ItemStack) holderAsStackMethod.invoke(holder, count);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] asStack({}) threw", count, e);
        }
        return ItemStack.EMPTY;
    }

    private static String getYHFluidHolderDesc(Object fluid) {
        if (yhFluidTypeField == null) return "no-field";
        try {
            Object holder = yhFluidTypeField.get(fluid);
            return holder != null ? holder.getClass().getSimpleName() : "null";
        } catch (Exception e) {
            return "err:" + e.toString();
        }
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        // If the recipe actually completed (fluid was produced) before we
        // timed out, collect the result rather than throwing it away.
        if (isKettleBE(be)) {
            IFluidHandler fluidHandler = getFluidHandler(be);
            if (fluidHandler != null) {
                FluidStack tankFluid = fluidHandler.getFluidInTank(0);
                FluidStack recipeResult = getRecipeResult();
                if (!tankFluid.isEmpty() && !recipeResult.isEmpty()
                        && tankFluid.getFluid() == recipeResult.getFluid()) {
                    ItemStack result = collectResult(player);
                    if (!result.isEmpty()) {
                        if (network == null)
                            this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, be.getBlockPos());
                        if (network != null)
                            network.insertItem(result, result.getCount(), Action.PERFORM);
                    }
                }
            }
        }
        clearAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    @Override
    public void onBatchFinished(ServerPlayer player) {
        clearAndRefund();
        forceChunkLoad(false);
        craftDone = false;
        network = null;
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // -- plan helpers --

    public static void addFuelIfNeeded(@Nullable String recipeModTypeId,
                                       Map<Item, Integer> itemAvailable,
                                       Map<Item, Ingredient> itemSource,
                                       Map<Item, Integer> neededCounts,
                                       int repeatCount) {}

    public static List<String> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<String> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.youkaishomecoming.kettle_water_warning").getString());
        warnings.add(Component.translatable("rsi.youkaishomecoming.heat_warning").getString());
        warnings.add(Component.translatable("rsi.youkaishomecoming.kettle_fluid_output").getString());
        return warnings;
    }

    // -- BE discovery --

    @Nullable
    private BlockEntity findKettleBE() {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be != null && isKettleBE(be)) return be;

        // Safety net: search nearby
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos check = myPos.offset(dx, dy, dz);
                    be = myLevel.getBlockEntity(check);
                    if (be != null && isKettleBE(be)) {
                        RSIntegrationMod.LOGGER.debug("[RSI-Kettle] Found BE at {} (offset {})",
                                check, check.subtract(myPos));
                        myPos = check.immutable();
                        return be;
                    }
                }
            }
        }
        return null;
    }

    // -- reflection (one-time probe) --

    private static void probeReflection() {
        if (reflectionProbed) return;
        reflectionProbed = true;
        if (YHKReflection.kettleBEClass == null || YHKReflection.kettleRecipeClass == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] YHKReflection kettle probe not ready");
            return;
        }
        try {
            // fluids -- public final field on KettleBlockEntity
            fluidsField = YHKReflection.kettleBEClass.getField("fluids");

            // getItemHandler(), getFluidHandler() -- public on FluidItemTile
            getItemHandlerMethod = YHKReflection.kettleBEClass.getMethod("getItemHandler");
            getFluidHandlerMethod = YHKReflection.kettleBEClass.getMethod("getFluidHandler");

            // isHeated -- default method from HeatableBlockEntity
            isHeatedMethod = YHKReflection.kettleBEClass.getMethod("isHeated", Level.class, BlockPos.class);

            // KettleRecipe public fields
            inputField = YHKReflection.kettleRecipeClass.getField("input");
            resultField = YHKReflection.kettleRecipeClass.getField("result");

            // YHFluid custom container system (used for kettle fluid output)
            if (YHKReflection.yhFluidClass != null && YHKReflection.yhFluidHolderClass != null) {
                yhFluidTypeField = YHKReflection.yhFluidClass.getField("type");
                holderAmountMethod = YHKReflection.yhFluidHolderClass.getMethod("amount");
                holderAsStackMethod = YHKReflection.yhFluidHolderClass.getMethod("asStack", int.class);
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Reflection probe failed", e);
        }
    }

    private static boolean isKettleBE(BlockEntity be) {
        probeReflection();
        if (YHKReflection.kettleBEClass != null && YHKReflection.kettleBEClass.isAssignableFrom(be.getClass())) return true;
        Class<?> clazz = be.getClass();
        while (clazz != null) {
            if (YHKReflection.kettleBEClass != null && YHKReflection.kettleBEClass.getName().equals(clazz.getName())) return true;
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    @Nullable
    private static IFluidHandler getFluidHandler(BlockEntity be) {
        probeReflection();
        if (getFluidHandlerMethod != null) {
            try {
                return (IFluidHandler) getFluidHandlerMethod.invoke(be);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Kettle] getFluidHandler reflection failed", e);
            }
        }
        return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER)
                .resolve().orElse(null);
    }

    @Nullable
    private static SimpleContainer getItemHandler(BlockEntity be) {
        probeReflection();
        if (getItemHandlerMethod != null) {
            try {
                Object result = getItemHandlerMethod.invoke(be);
                if (result instanceof SimpleContainer c) return c;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Kettle] getItemHandler reflection failed", e);
            }
        }
        var cap = be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
                .resolve().orElse(null);
        if (cap != null) {
            // Wrap capability as SimpleContainer compat
            return null;
        }
        return null;
    }

    private boolean isHeated(BlockEntity be) {
        if (isHeatedMethod == null) return true;
        try {
            return (boolean) isHeatedMethod.invoke(be, myLevel, myPos);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.debug("[RSI-Kettle] isHeated invoke failed", e);
        }
        return true;
    }

    /** Ensure the kettle's fluid tank has water. Returns true if water is present. */
    private boolean ensureWater(IFluidHandler fluidHandler, BlockEntity be) {
        FluidStack tankFluid = fluidHandler.getFluidInTank(0);

        // Already has water -- nothing to do
        if (!tankFluid.isEmpty() && tankFluid.getFluid() == Fluids.WATER) {
            return true;
        }

        // Drain any non-water fluid (e.g. previous recipe result) to make room
        if (!tankFluid.isEmpty() && tankFluid.getFluid() != Fluids.WATER) {
            fluidHandler.drain(tankFluid, IFluidHandler.FluidAction.EXECUTE);
        }

        // Try to fill water directly (some tanks accept this)
        int filled = fluidHandler.fill(new FluidStack(Fluids.WATER, 1000),
                IFluidHandler.FluidAction.EXECUTE);
        if (filled > 0) {
            be.setChanged();
            return true;
        }

        // Direct fill failed -- try setting the public fluids field directly
        if (fluidsField != null) {
            try {
                Object tank = fluidsField.get(be);
                if (tank != null) {
                    Method fillMethod = tank.getClass().getMethod("setFluid", FluidStack.class);
                    fillMethod.invoke(tank, new FluidStack(Fluids.WATER, 1000));
                    be.setChanged();
                    return true;
                }
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Water fill via fluidsField failed", e);
            }
        }

        // Last resort: extract water bucket from RS, use it to fill
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] No network, cannot get water");
            return false;
        }

        ItemStack waterBucket = network.extractItem(
                new ItemStack(net.minecraft.world.item.Items.WATER_BUCKET), 1, Action.PERFORM);
        if (waterBucket.isEmpty()) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] No water bucket in RS network");
            player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.kettle_water_warning"));
            return false;
        }

        filled = fluidHandler.fill(new FluidStack(Fluids.WATER, 1000),
                IFluidHandler.FluidAction.EXECUTE);
        if (filled > 0) {
            ItemStack leftover = network.insertItem(
                    new ItemStack(net.minecraft.world.item.Items.BUCKET), 1, Action.PERFORM);
            if (!leftover.isEmpty()) {
                ItemHandlerHelper.giveItemToPlayer(player, leftover);
            }
            be.setChanged();
            return true;
        }

        // Refund the water bucket -- we couldn't use it
        network.insertItem(waterBucket, 1, Action.PERFORM);
        RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Fluid handler rejected water fill");
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Ingredient> getRecipeInput() {
        probeReflection();
        if (inputField != null) {
            try {
                return (List<Ingredient>) inputField.get(recipe);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Kettle] getRecipeInput reflection failed", e);
            }
        }
        return List.of();
    }

    private FluidStack getRecipeResult() {
        probeReflection();
        if (resultField != null) {
            try {
                Object val = resultField.get(recipe);
                if (val instanceof FluidStack fs) return fs;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Kettle] getRecipeResult reflection failed", e);
            }
        }
        return FluidStack.EMPTY;
    }

    // -- cleanup --

    private void clearSlots(SimpleContainer items, BlockEntity be) {
        for (int i = 0; i < items.getContainerSize(); i++) {
            items.setItem(i, ItemStack.EMPTY);
        }
        be.setChanged();
    }

    private void clearAndRefund() {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isKettleBE(be)) return;

        SimpleContainer items = getItemHandler(be);
        if (items != null) {
            for (int i = 0; i < items.getContainerSize(); i++) {
                ItemStack s = items.getItem(i);
                if (!s.isEmpty()) {
                    items.setItem(i, ItemStack.EMPTY);
                    if (!usingSharedLedger) refund(s);
                }
            }
            be.setChanged();
        }
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
            RSIntegrationMod.LOGGER.debug("[RSI-Kettle] Chunk load failed", e);
        }
    }
}
