package com.huanghuang.rsintegration.mods.farmandcharm;

import com.huanghuang.rsintegration.mods.farmandcharm.LetsDoRoasterRecipeHandler;
import com.huanghuang.rsintegration.reflection.probes.LetsDoReflection;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.BlockEntity;

/** farm_and_charm 烘焙机：与烹饪锅同套槽位，但配方类型不同，因此单独一个委托。 */
public final class RoasterBlockBatchDelegate extends LetsDoCookingPotBatchDelegate {

    @Override
    protected boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        return LetsDoReflection.isFarmAndCharmRoaster(blockEntity);
    }

    @Override
    protected boolean isSupportedRecipe(Recipe<?> candidate) {
        return candidate != null
                && LetsDoRoasterRecipeHandler.RECIPE_CLASS.equals(candidate.getClass().getName());
    }

    @Override
    protected String outputPortId() {
        return "farm_and_charm:roaster:output";
    }
}
