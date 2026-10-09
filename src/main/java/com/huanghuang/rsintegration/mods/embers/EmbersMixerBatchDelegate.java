package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import com.rekindled.embers.api.upgrades.UpgradeUtil;
import com.rekindled.embers.blockentity.MixerCentrifugeBottomBlockEntity;
import com.rekindled.embers.blockentity.MixerCentrifugeTopBlockEntity;
import com.rekindled.embers.recipe.IMixingRecipe;
import com.rekindled.embers.recipe.MixingContext;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;

import java.util.ArrayList;
import java.util.List;

/** 四个侧向液罐投料，上半部液罐收取混合产物。 */
public final class EmbersMixerBatchDelegate extends EmbersFluidBatchDelegate {
    private MixerCentrifugeBottomBlockEntity bottom;
    private MixerCentrifugeTopBlockEntity top;
    private final List<FluidStack> placedFluids = new ArrayList<>();

    @Override protected boolean isRecipe(Recipe<?> candidate) { return candidate instanceof IMixingRecipe; }
    @Override protected boolean bindMachine(BlockEntity root) {
        if (!(root instanceof MixerCentrifugeBottomBlockEntity machine)) return false;
        BlockEntity upper = level.getBlockEntity(root.getBlockPos().above());
        if (!(upper instanceof MixerCentrifugeTopBlockEntity tank)) return false;
        bottom = machine;
        top = tank;
        return true;
    }
    @Override protected boolean structureValid() {
        return bottom != null && top != null && !bottom.isRemoved() && !top.isRemoved()
                && level.hasChunkAt(bottom.getBlockPos()) && level.hasChunkAt(top.getBlockPos())
                && level.getBlockEntity(bottom.getBlockPos()) == bottom
                && level.getBlockEntity(top.getBlockPos()) == top;
    }
    @Override protected IFluidHandler outputTank() { return top.getTank(); }
    @Override protected boolean inputsIdle() {
        for (IFluidHandler tank : bottom.getTanks()) {
            if (!tank.getFluidInTank(0).isEmpty()) return false;
        }
        return true;
    }
    @Override protected boolean hasPower() {
        double cost = UpgradeUtil.getTotalEmberConsumption(bottom, 2.0D,
                UpgradeUtil.getUpgrades(level, pos.above(), Direction.values()));
        return top.capability.getEmber() >= cost;
    }
    @Override protected boolean placeInputs(List<ItemStack> materials) {
        IFluidHandler[] tanks = bottom.getTanks();
        if (materials.size() > tanks.length) return false;
        for (int i = 0; i < materials.size(); i++) {
            FluidStack fluid = InkFluidSupport.fluid(materials.get(i));
            if (fluid.isEmpty() || tanks[i].fill(fluid.copy(), IFluidHandler.FluidAction.SIMULATE)
                    != fluid.getAmount()) return false;
        }
        placedFluids.clear();
        for (int i = 0; i < materials.size(); i++) {
            FluidStack fluid = InkFluidSupport.fluid(materials.get(i));
            int filled = tanks[i].fill(fluid.copy(), IFluidHandler.FluidAction.EXECUTE);
            if (filled > 0) {
                FluidStack placed = fluid.copy();
                placed.setAmount(filled);
                placedFluids.add(placed);
                remaining.set(i, materials.get(i).copyWithCount(fluid.getAmount() - filled));
            }
            if (filled != fluid.getAmount()) return false;
            placed(i);
        }
        return true;
    }
    @Override protected boolean nativeRecipeMatches() {
        return ((IMixingRecipe) recipe).matches(new MixingContext(bottom.getTanks()), level);
    }
    @Override protected List<ItemStack> recoverInputs() {
        List<ItemStack> recovered = new ArrayList<>();
        IFluidHandler[] tanks = bottom.getTanks();
        for (int i = 0; i < placedFluids.size(); i++) {
            FluidStack drained = tanks[i].drain(placedFluids.get(i), IFluidHandler.FluidAction.EXECUTE);
            if (!drained.isEmpty()) recovered.add(InkFluidSupport.token(drained));
        }
        return recovered;
    }
}
