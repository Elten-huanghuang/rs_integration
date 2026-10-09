package com.huanghuang.rsintegration.mods.embers;

import com.rekindled.embers.blockentity.MelterBottomBlockEntity;
import com.rekindled.embers.blockentity.MelterTopBlockEntity;
import com.rekindled.embers.ConfigManager;
import com.rekindled.embers.api.upgrades.UpgradeUtil;
import com.rekindled.embers.recipe.IMeltingRecipe;
import com.rekindled.embers.util.Misc;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.wrapper.RecipeWrapper;

import java.util.List;

/** 下半部供能、上半部投料与收取熔融液体。 */
public final class EmbersMelterBatchDelegate extends EmbersFluidBatchDelegate {
    private MelterBottomBlockEntity bottom;
    private MelterTopBlockEntity top;

    @Override protected boolean isRecipe(Recipe<?> candidate) { return candidate instanceof IMeltingRecipe; }
    @Override protected boolean bindMachine(BlockEntity root) {
        if (!(root instanceof MelterBottomBlockEntity machine)) return false;
        BlockEntity upper = level.getBlockEntity(root.getBlockPos().above());
        if (!(upper instanceof MelterTopBlockEntity tank)) return false;
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
    @Override protected boolean inputsIdle() { return top.inventory.getStackInSlot(0).isEmpty(); }
    @Override protected boolean hasPower() {
        double cost = UpgradeUtil.getTotalEmberConsumption(bottom,
                ConfigManager.MELTER_EMBER_COST.get(),
                UpgradeUtil.getUpgrades(level, pos, Misc.horizontals));
        return bottom.capability.getEmber() >= cost;
    }
    @Override protected boolean placeInputs(List<ItemStack> materials) {
        ItemStack stack = materials.get(0);
        if (!top.inventory.insertItem(0, stack.copy(), true).isEmpty()) return false;
        ItemStack rest = top.inventory.insertItem(0, stack.copy(), false);
        if (!rest.isEmpty()) return false;
        placed(0);
        return true;
    }
    @Override protected boolean nativeRecipeMatches() {
        return ((IMeltingRecipe) recipe).matches(new RecipeWrapper(top.inventory), level);
    }
    @Override protected List<ItemStack> recoverInputs() {
        ItemStack stored = top.inventory.getStackInSlot(0);
        return stored.isEmpty() ? List.of() : List.of(top.inventory.extractItem(0, stored.getCount(), false));
    }
}
