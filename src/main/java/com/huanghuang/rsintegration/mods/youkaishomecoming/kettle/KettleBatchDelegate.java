package com.huanghuang.rsintegration.mods.youkaishomecoming.kettle;

import com.huanghuang.rsintegration.crafting.batch.OperationOutputSource;
import com.huanghuang.rsintegration.crafting.batch.MaterialPlan;
import com.huanghuang.rsintegration.mods.common.MachineWaterSupply;
import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.ExtractionLedger;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.AbstractBatchDelegate;
import com.huanghuang.rsintegration.crafting.batch.OperationInputRecovery;
import com.huanghuang.rsintegration.mods.youkaishomecoming.kettle.KettleRecipeSupport.Brewing;
import com.huanghuang.rsintegration.util.Reflect;
import com.huanghuang.rsintegration.crafting.batch.BatchConcurrencyCapabilities;
import com.huanghuang.rsintegration.mods.common.IdleInventoryEvacuator;
import com.huanghuang.rsintegration.reflection.probes.YHKReflection;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.items.ItemHandlerHelper;
import org.jetbrains.annotations.NotNull;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Batch delegate for Youkais Homecoming Kettle. */
public final class KettleBatchDelegate extends AbstractBatchDelegate {

    @Override
    public BatchConcurrencyCapabilities concurrencyCapabilities() {
        // The result is drained from this kettle and converted to an item by
        // the delegate; no shared output slot or world capture is involved.
        return BatchConcurrencyCapabilities.delegateResult();
    }

    private ServerPlayer player;
    private ServerLevel myLevel;
    private ResourceKey<Level> myDim;
    private BlockPos myPos;
    private Brewing brewing;
    private OperationInputRecovery inputRecovery;

    // -- reflection cache --
    /** KettleBlockEntity.fluids -- public final BaseTank */
    private static volatile Field fluidsField;
    /** HeatableBlockEntity.isHeated(Level, BlockPos) */
    private static volatile Method isHeatedMethod;
    /** FluidItemTile.getItemHandler() -> SimpleContainer */
    private static volatile Method getItemHandlerMethod;
    /** FluidItemTile.getFluidHandler() -> BaseTank */
    private static volatile Method getFluidHandlerMethod;
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
        this.brewing = KettleRecipeSupport.brewing(found);
        return brewing != null;
    }

    @Override
    public MaterialPlan materialPlan() {
        return MaterialPlan.graphSlots(recipeSlots());
    }

    List<IngredientSpec> recipeSlots() {
        return brewing == null ? List.of() : brewing.ingredients();
    }

    @Override
    public boolean startSingleOperation(ServerPlayer player) {
        List<IngredientSpec> specs = recipeSlots();
        if (specs == null || specs.isEmpty()) return false;

        List<ItemStack> materials = new ArrayList<>();
        try (ExtractionLedger ledger = materialAccess().newLedger()) {
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
                        insertIntoStorage(player, mat.copy(), false);
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
        if (brewing == null || !matchesMaterials(brewing, materials)) return false;

        forceChunkLoad(true);
        if (!myLevel.hasChunkAt(myPos)) return false;

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
        if (!hasStorageAccess()) {
            forceChunkLoad(false);
            return false;
        }
        int evacuated = evacuateIdleItems(items, getProcessingProgress(be), this::refund);
        if (evacuated < 0) {
            RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Kettle is busy or could not be cleared at {}", myPos);
            forceChunkLoad(false);
            return false;
        }
        if (evacuated > 0) be.setChanged();

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

        inputRecovery = new OperationInputRecovery();
        // 空容器由执行器保管，只在成功收取流体时消耗。
        inputRecovery.hold(materials.get(brewing.solids().size()));
        for (int slot = 0; slot < brewing.solids().size(); slot++) {
            ItemStack solid = materials.get(slot);
            inputRecovery.hold(solid).place(be, items, slot);
            items.setItem(slot, solid.copy());
        }

        be.setChanged();
        markCraftStarted();
        return true;
    }

    static boolean matchesMaterials(Brewing brewing, List<ItemStack> materials) {
        List<IngredientSpec> specs = brewing.ingredients();
        if (materials.size() != specs.size()) return false;
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            ItemStack material = materials.get(i);
            if (material.getCount() != spec.count() || !spec.ingredient().test(material)) return false;
        }
        return true;
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        if (inputRecovery == null || !isKettleBE(be)) return false;
        IFluidHandler fluidHandler = getFluidHandler(be);
        return fluidHandler != null && canCollect(brewing, fluidHandler);
    }

    @Override public OperationOutputSource outputSource() {
        return OperationOutputSource.single(this::takeResult, outputContract());
    }

    private ItemStack takeResult(ServerPlayer player) {
        if (inputRecovery == null) return ItemStack.EMPTY;
        BlockEntity be = myLevel.getBlockEntity(myPos);
        if (be == null || !isKettleBE(be)) return ItemStack.EMPTY;

        IFluidHandler fluidHandler = getFluidHandler(be);
        if (fluidHandler == null) return ItemStack.EMPTY;
        ItemStack result = collectBrewed(brewing, fluidHandler);
        if (result.isEmpty()) return result;
        inputRecovery.release();
        inputRecovery = null;
        be.setChanged();
        return result;
    }

    private static boolean canCollect(Brewing brewing, IFluidHandler tank) {
        return tank.drain(brewing.fluid().copy(), FluidAction.SIMULATE).isFluidStackIdentical(brewing.fluid());
    }

    static ItemStack collectBrewed(Brewing brewing, IFluidHandler tank) {
        if (!canCollect(brewing, tank)) return ItemStack.EMPTY;
        FluidStack drained = tank.drain(brewing.fluid().copy(), FluidAction.EXECUTE);
        return drained.isFluidStackIdentical(brewing.fluid()) ? brewing.result().copy() : ItemStack.EMPTY;
    }

    @Override
    protected void clearMachineState(BlockEntity be, ServerPlayer player) {
        if (player != null) {
            ItemStack result = takeResult(player);
            if (!result.isEmpty()) refund(result);
        }
        recoverInputs(myLevel);
    }

    @Override
    protected void clearMissingMachineState(@Nullable ServerPlayer player) {
        recoverInputs(null);
    }

    @Override
    protected void clearUnavailableMachineState(@Nullable ServerPlayer player) {
        recoverInputs(null);
    }

    private void recoverInputs(@Nullable ServerLevel level) {
        List<ItemStack> recovered = inputRecovery == null ? List.of() : inputRecovery.recover(level);
        inputRecovery = null;
        if (usingSharedLedger) recordFailureRecoveredInputs(recovered);
        else recovered.forEach(this::refund);
    }

    @Override
    public void cleanup(@NotNull ServerPlayer player) {
        forceChunkLoad(false);
        setStorageEndpoint(null);
    }

    @Override
    public BlockPos getMachinePos() { return myPos; }

    // -- plan helpers --

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                                @Nullable ResourceLocation dim,
                                                @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        warnings.add(Component.translatable("rsi.youkaishomecoming.kettle_water_warning"));
        warnings.add(Component.translatable("rsi.youkaishomecoming.heat_warning"));
        return warnings;
    }

    // -- BE discovery --

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

    private static IFluidHandler getFluidHandler(BlockEntity be) {
        probeReflection();
        if (getFluidHandlerMethod != null) {
            try {
                return (IFluidHandler) getFluidHandlerMethod.invoke(be);
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Kettle] getFluidHandler reflection failed", e);
            }
        }
        return be.getCapability(ForgeCapabilities.FLUID_HANDLER)
                .resolve().orElse(null);
    }

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
        var cap = be.getCapability(ForgeCapabilities.ITEM_HANDLER)
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

        if (!tankFluid.isEmpty() && tankFluid.getFluid() == Fluids.WATER && tankFluid.getAmount() >= 1000) {
            return true;
        }

        if (!tankFluid.isEmpty() && tankFluid.getFluid() != Fluids.WATER) return false;

        IFluidHandler tank = fluidHandler;
        if (fluidsField != null) {
            try {
                if (fluidsField.get(be) instanceof IFluidHandler internal) tank = internal;
            } catch (Exception e) {
                RSIntegrationMod.LOGGER.warn("[RSI-Kettle] Water fill via fluidsField failed", e);
            }
        }
        int amount = Math.max(0, Math.min(1000, tank.getTankCapacity(0)) - tankFluid.getAmount());
        if (amount == 0) return false;
        if (amount > 0 && MachineWaterSupply.fill(materialAccess(), "youkaishomecoming_kettle", tank, amount,
                storageEndpoint(), player) == amount) {
            be.setChanged();
            return true;
        }
        player.sendSystemMessage(Component.translatable("rsi.youkaishomecoming.kettle_water_warning"));
        return false;
    }

    static int evacuateIdleItems(SimpleContainer items, float progress,
                                 Consumer<ItemStack> returnItem) {
        if (items == null || progress > 0.0F) return -1;
        IdleInventoryEvacuator.Result result = IdleInventoryEvacuator.evacuate(
                items, true, ignored -> IdleInventoryEvacuator.SlotPolicy.RETURN, returnItem);
        return result.cleared() ? result.returnedCount() : -1;
    }

    private static float getProcessingProgress(BlockEntity be) {
        return Reflect.<Number>invoke(be, "inProgress")
                .map(Number::floatValue).orElse(1.0F);
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
