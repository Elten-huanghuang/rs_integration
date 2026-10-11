package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.batch.ParallelBatchSizing;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.rekindled.embers.blockentity.MiniBoilerBlockEntity;
import com.rekindled.embers.recipe.FluidHandlerContext;
import com.rekindled.embers.recipe.IBoilingRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import javax.annotation.Nullable;
import java.util.List;

/** 灌液和收取气体由递归任务管理，沸腾仍由连接设备的原生余热事件驱动。 */
public final class EmbersMiniBoilerBatchDelegate extends EmbersFluidBatchDelegate {
    private MiniBoilerBlockEntity machine;
    private FluidStack placedFluid = FluidStack.EMPTY;
    private FluidStack singleOutput = FluidStack.EMPTY;
    private int plannedOperations = 1;
    private int observedGasAmount;

    @Override public PreparationResult prepare(ServerPlayer player, ResourceLocation recipeId,
                                               @Nullable ResourceLocation dim, BlockPos target) {
        PreparationResult result = super.prepare(player, recipeId, dim, target);
        if (result.state() == PreparationState.FATAL
                && result.detail().equals("余烬机器结构不完整或绑定位置不正确")) {
            return PreparationResult.fatal(result.detail(),
                    Component.translatable("rsi.embers_machine.error.boiler_binding"));
        }
        if (result.state() == PreparationState.READY
                && required.get(0).count() > machine.getFluidTank().getCapacity()) {
            return PreparationResult.fatal("微型锅炉液体原料超过输入罐容量",
                    Component.translatable("rsi.embers_machine.error.boiler_input_capacity"));
        }
        if (result.state() == PreparationState.READY) {
            singleOutput = expected.copy();
            plannedOperations = 1;
        }
        return result;
    }

    @Override public int prepareFlatBatch(int remainingOperations) {
        int executions = Math.min(Math.max(0, remainingOperations), batchCapacity());
        if (executions > 0) prepareGraphBatch(executions);
        return executions;
    }

    @Override public void prepareGraphBatch(int executions) {
        if (started || executions <= 0 || executions > batchCapacity()) {
            throw new IllegalArgumentException("微型锅炉批量执行次数超过当前容量或已经启动");
        }
        plannedOperations = executions;
        expected = singleOutput.copy();
        expected.setAmount(Math.multiplyExact(singleOutput.getAmount(), executions));
    }

    @Override public int preferredParallelBatchSize(int totalOperations, int workerCount) {
        return ParallelBatchSizing.boundedEvenShare(totalOperations, workerCount, batchCapacity());
    }

    @Override public int flatBatchOperationLimit(int configuredLimit) {
        return Math.max(Math.max(1, configuredLimit), batchCapacity());
    }

    @Override public boolean expandsFlatBatchOperationLimit() { return batchCapacity() > 1; }

    @Override protected int requiredMaterialCount(IngredientSpec spec) {
        return Math.multiplyExact(spec.count(), plannedOperations);
    }

    @Override public ExpectedProduction getExpectedProduction() {
        return expected.isEmpty() ? null
                : new ExpectedProduction(InkFluidSupport.token(expected), expected.getAmount());
    }

    @Override public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return super.validateExecutionContext(player) && (started
                || plannedOperations <= batchCapacity()
                && machine.getGasTank().fill(expected.copy(), IFluidHandler.FluidAction.SIMULATE)
                == expected.getAmount());
    }

    @Override protected boolean hasProcessingProgress() {
        FluidStack gas = machine.getGasTank().getFluid();
        if (!started || !gas.isFluidEqual(expected) || gas.getAmount() <= observedGasAmount) return false;
        observedGasAmount = gas.getAmount();
        return true;
    }

    private int batchCapacity() {
        if (machine == null || required.size() != 1 || required.get(0).count() <= 0
                || singleOutput.isEmpty()) return 0;
        // 原生 boil 可能一次消耗全部可加工输入，整批气体必须放得下，避免溢出爆炸。
        return Math.min(machine.getFluidTank().getCapacity() / required.get(0).count(),
                machine.getGasTank().getCapacity() / singleOutput.getAmount());
    }

    @Override protected boolean isRecipe(Recipe<?> candidate) { return candidate instanceof IBoilingRecipe; }
    @Override protected boolean bindMachine(BlockEntity root) {
        if (!(root instanceof MiniBoilerBlockEntity boiler)) return false;
        machine = boiler;
        return true;
    }
    @Override protected boolean structureValid() {
        return machine != null && !machine.isRemoved() && level != null
                && level.hasChunkAt(machine.getBlockPos())
                && level.getBlockEntity(machine.getBlockPos()) == machine;
    }
    @Override protected IFluidHandler outputTank() { return machine.getGasTank(); }
    @Override protected boolean inputsIdle() { return machine.getFluidTank().isEmpty(); }
    @Override protected boolean hasPower() {
        // 锅炉自身没有余烬能量仓，先灌液，再等待工作设备的原生余热事件。
        return true;
    }
    @Override protected boolean placeInputs(List<ItemStack> materials) {
        FluidStack fluid = InkFluidSupport.fluid(materials.get(0));
        if (fluid.isEmpty() || machine.getFluidTank().fill(fluid.copy(), IFluidHandler.FluidAction.SIMULATE)
                != fluid.getAmount()) return false;
        int filled = machine.getFluidTank().fill(fluid.copy(), IFluidHandler.FluidAction.EXECUTE);
        if (filled > 0) {
            placedFluid = fluid.copy();
            placedFluid.setAmount(filled);
            remaining.set(0, materials.get(0).copyWithCount(fluid.getAmount() - filled));
        }
        if (filled != fluid.getAmount()) return false;
        placed(0);
        observedGasAmount = 0;
        return true;
    }
    @Override protected boolean nativeRecipeMatches() {
        IBoilingRecipe boiling = (IBoilingRecipe) recipe;
        if (!boiling.matches(new FluidHandlerContext(machine.getFluidTank()), level)) return false;
        machine.cachedRecipe = boiling;
        machine.setChanged();
        return true;
    }
    @Override protected List<ItemStack> recoverInputs() {
        if (placedFluid.isEmpty()) return List.of();
        FluidStack recovered = machine.getFluidTank().drain(placedFluid.copy(), IFluidHandler.FluidAction.EXECUTE);
        placedFluid = FluidStack.EMPTY;
        if (machine.cachedRecipe == recipe) machine.cachedRecipe = null;
        machine.setChanged();
        return recovered.isEmpty() ? List.of() : List.of(InkFluidSupport.token(recovered));
    }
    @Override public Component craftFailureMessage(CraftObservation observation) {
        if (observation.phase() == CraftPhase.FAILED && structureValid() && !inputsIdle()) {
            return Component.translatable("rsi.embers_machine.error.boiler_stalled");
        }
        return super.craftFailureMessage(observation);
    }
}
