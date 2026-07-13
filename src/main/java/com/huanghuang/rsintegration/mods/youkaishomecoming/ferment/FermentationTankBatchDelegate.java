package com.huanghuang.rsintegration.mods.youkaishomecoming.ferment;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
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
import net.minecraftforge.common.world.ForgeChunkManager;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

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

    private int insertedCount;
    private ItemStack insertedSample = ItemStack.EMPTY;

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
        probeReflection();
        this.requiredWater = readRecipeWater(found);
        this.recipeTime = readRecipeTime(found);
        RSIntegrationMod.LOGGER.debug("[RSI-Ferment] validateAndInit: recipe={} water={}mb time={}ticks inputFluidF={} outFluidF={}",
                recipeId, requiredWater, recipeTime,
                recipeInputFluidField != null, recipeOutputFluidField != null);
        return true;
    }

    @Nullable
    @Override
    public List<IngredientSpec> getRequiredMaterials() {
        List<Ingredient> ingredients = getRecipeIngredients();
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
        // usingSharedLedger already set by caller — don't overwrite
        this.craftDone = false;

        forceChunkLoad(true);
        myLevel.getChunk(myPos);

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

        // ── Auto-clean: refund any leftover items / non-water fluid ──
        // If the player manually put stuff in or a previous craft left debris,
        // clear it so the new recipe can start without slot conflicts.
        boolean hasLeftoverItems = false;
        for (int i = 0; i < itemHandler.getContainerSize(); i++) {
            if (!itemHandler.getItem(i).isEmpty()) { hasLeftoverItems = true; break; }
        }
        IFluidHandler fluidHandler = getFluidHandler(be);
        boolean hasNonWaterFluid = false;
        if (fluidHandler != null) {
            FluidStack tf = fluidHandler.getFluidInTank(0);
            if (!tf.isEmpty() && tf.getFluid() != Fluids.WATER
                    && tf.getFluid() != net.minecraft.world.level.material.Fluids.EMPTY) {
                hasNonWaterFluid = true;
            }
        }
        if (hasLeftoverItems || hasNonWaterFluid) {
            RSIntegrationMod.LOGGER.debug("[RSI-Ferment] Auto-cleaning machine before craft: items={} nonWaterFluid={}",
                    hasLeftoverItems, hasNonWaterFluid);
            // Temporarily allow refund; restore after cleaning
            boolean saved = this.usingSharedLedger;
            this.usingSharedLedger = false;
            clearAndRefund();
            this.usingSharedLedger = saved;
            // Re-fetch handler after clearAndRefund (BE may have changed)
            itemHandler = getItemHandler(be);
            if (itemHandler == null) {
                player.sendSystemMessage(Component.translatable("rsi.generic.error.machine_not_found"));
                forceChunkLoad(false);
                return false;
            }
            fluidHandler = getFluidHandler(be);
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

        // ── Place ingredients into slots ──
        ItemStack[] snapshot = new ItemStack[itemHandler.getContainerSize()];
        for (int i = 0; i < snapshot.length; i++) {
            snapshot[i] = itemHandler.getItem(i).copy();
        }

        insertedCount = 0;
        insertedSample = ItemStack.EMPTY;
        for (ItemStack mat : materials) {
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
                notifyTile(be);
                return false;
            }
            insertedCount++;
            if (insertedSample.isEmpty()) insertedSample = single.copy();
        }
        setLidOpen(false); // close lid to start fermentation
        notifyTile(be);
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (!isFermentationBE(be)) return false;

        // Check progress using the recipe field or BE field
        int total = recipeTime > 0 ? recipeTime
                : Reflect.<Integer>getField(be, "totalTime").orElse(-1);
        int progress = Reflect.<Integer>getField(be, "recipeProgress").orElse(-1);
        if (total > 0 && progress >= total) return true;

        // Fallback: check if result item is present
        SimpleContainer handler = getItemHandler(be);
        if (handler == null) return false;

        ItemStack expected = getExpectedResult();
        if (!expected.isEmpty()) {
            for (int i = 0; i < handler.getContainerSize(); i++) {
                ItemStack s = handler.getItem(i);
                if (!s.isEmpty() && ItemStack.isSameItem(s, expected)) return true;
            }
        }
        return false;
    }

    @Override
    public ItemStack collectResult(ServerPlayer player) {
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null) return ItemStack.EMPTY;

        ItemStack collected = ItemStack.EMPTY;

        // ── Collect fluid output (e.g. mead, sake) ──
        ItemStack fluidResult = collectFluidOutput(be);
        if (!fluidResult.isEmpty()) {
            collected = fluidResult;
            notifyTile(be);
        }

        // ── Collect item output from handler slots ──
        SimpleContainer handler = getItemHandler(be);
        if (handler != null) {
            for (int i = 0; i < handler.getContainerSize(); i++) {
                ItemStack s = handler.getItem(i);
                if (s.isEmpty()) continue;
                // collectResult can only return ONE stack. Only remove a slot's
                // items when we can actually deliver them: as the primary (first
                // non-empty) or merged with an already-collected same-type stack.
                // A DIFFERENT item type is left in the machine (not removed) so it
                // is never physically destroyed — the player can retrieve it.
                if (collected.isEmpty()) {
                    ItemStack extracted = handler.removeItem(i, s.getCount());
                    if (!extracted.isEmpty()) collected = extracted;
                } else if (ItemStack.isSameItemSameTags(collected, s)) {
                    ItemStack extracted = handler.removeItem(i, s.getCount());
                    if (!extracted.isEmpty()) collected.grow(extracted.getCount());
                }
                // else: leave the differing stack in the slot (do not remove).
            }
            notifyTile(be);
        }

        craftDone = true;
        if (collected.isEmpty()) {
            ItemStack expected = getExpectedResult();
            if (!expected.isEmpty()) return expected.copy();
        }
        return collected;
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
            int totalAmount = tankFluid.getAmount();
            int bottles = totalAmount / perBottle;
            if (bottles <= 0) return ItemStack.EMPTY;

            int drainAmount = bottles * perBottle;
            FluidStack drained = fh.drain(new FluidStack(tankFluid.getFluid(), drainAmount),
                    IFluidHandler.FluidAction.EXECUTE);
            if (drained.isEmpty() || drained.getAmount() < perBottle) return ItemStack.EMPTY;

            be.setChanged();
            ItemStack result = (ItemStack) holderAsStackMethod.invoke(typeHolder,
                    drained.getAmount() / perBottle);
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
    public void onBatchFinished(ServerPlayer player) {
        clearAndRefund();
        forceChunkLoad(false);
        resetFermentState();
    }

    private void resetFermentState() {
        craftDone = false;
        network = null;
        insertedCount = 0;
        insertedSample = ItemStack.EMPTY;
        requiredWater = 0;
        recipeTime = 0;
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
        int water = readRecipeWater(recipe);
        if (water > 0) {
            int perBottle = getYHWaterBottleAmount();
            if (perBottle > 0) {
                int bottles = (water + perBottle - 1) / perBottle;
                warnings.add(Component.translatable("rsi.youkaishomecoming.ferment_water_needed",
                        bottles, water).getString());
            } else {
                warnings.add(Component.translatable("rsi.youkaishomecoming.ferment_water_needed_mb",
                        water).getString());
            }
        }
        warnings.add(Component.translatable("rsi.youkaishomecoming.ferment_lid_warning").getString());
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

    @SuppressWarnings("unchecked")
    private ItemStack getExpectedResult() {
        try {
            Field f = findFieldInHierarchy(recipe.getClass(), "results");
            if (f != null) {
                f.setAccessible(true);
                Object val = f.get(recipe);
                if (val instanceof List<?> list && !list.isEmpty()) {
                    if (list.get(0) instanceof ItemStack s && !s.isEmpty())
                        return s.copy();
                }
            }
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Ferment] Expected result reflection failed", e);
        }
        // For fluid-output recipes, create bottled result from outputFluid
        ItemStack fluidResult = buildFluidOutputItem();
        if (!fluidResult.isEmpty()) return fluidResult;
        return ItemStack.EMPTY;
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
                net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER)
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
        return be.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER)
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
                    ResourceLocation key = net.minecraft.core.registries.BuiltInRegistries.FLUID.getKey(fluid.getFluid());
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
        this.network = CraftPacketUtils.resolveNetworkForCraft(player, myDim, myPos);
        if (this.network == null) {
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
            ItemStack waterBottle = findWaterHolder(network);
            if (waterBottle.isEmpty()) {
                // Drain whatever we managed to fill and refund
                player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.ferment_water_warning"));
                return false;
            }

            // Extract the fluid holder from RS
            ItemStack extracted = network.extractItem(waterBottle.copyWithCount(1), 1, Action.PERFORM);
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
                network.insertItem(extracted, 1, Action.PERFORM);
                RSIntegrationMod.LOGGER.warn("[RSI-Ferment] Fluid handler rejected water fill (bottle {})", i);
                player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.ferment_water_warning"));
                return false;
            }

            // Return the empty container (if any) to RS
            ItemStack emptyContainer = getYHWaterEmptyContainer(extracted);
            if (!emptyContainer.isEmpty()) {
                ItemStack leftover = network.insertItem(emptyContainer, 1, Action.PERFORM);
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
        net.minecraft.world.level.material.Fluid waterFluid =
                net.minecraft.core.registries.BuiltInRegistries.FLUID.get(
                        new net.minecraft.resources.ResourceLocation("water"));
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
            if (stack.is(net.minecraft.world.item.Items.WATER_BUCKET)
                    || stack.is(net.minecraft.world.item.Items.POTION)) // water bottle
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

    /** Get the empty container returned when using a fluid holder. */
    private static ItemStack getYHWaterEmptyContainer(ItemStack holder) {
        if (holder.is(net.minecraft.world.item.Items.WATER_BUCKET))
            return new ItemStack(net.minecraft.world.item.Items.BUCKET);
        if (holder.is(net.minecraft.world.item.Items.POTION))
            return new ItemStack(net.minecraft.world.item.Items.GLASS_BOTTLE);
        // YHK fluid holders — read fluid's type holder, call asStack(0) for empty container
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
                        return (ItemStack) holderAsStackMethod.invoke(typeHolder, 0);
                    }
                }
            } catch (Exception ignored) { /* fall through */ }
        }
        return ItemStack.EMPTY;
    }

    // -- cleanup --

    private void clearAndRefund() {
        myLevel.getChunk(myPos);
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isFermentationBE(be)) return;

        // Refund fluid left in the tank (always — fluid is independent of the extraction ledger)
        {
            IFluidHandler fh = getFluidHandler(be);
            if (fh != null) {
                FluidStack tankFluid = fh.getFluidInTank(0);
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
        net.minecraft.world.level.material.Fluid waterFluid =
                net.minecraft.core.registries.BuiltInRegistries.FLUID.get(
                        new net.minecraft.resources.ResourceLocation("water"));
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
        return new ItemStack(net.minecraft.world.item.Items.POTION);
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
            RSIntegrationMod.LOGGER.debug("[RSI-Ferment] Chunk load failed", e);
        }
    }
}
