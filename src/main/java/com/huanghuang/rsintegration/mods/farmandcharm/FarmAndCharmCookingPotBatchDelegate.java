package com.huanghuang.rsintegration.mods.farmandcharm;

import com.huanghuang.rsintegration.reflection.probes.LetsDoReflection;
import net.minecraft.world.level.block.entity.BlockEntity;

/** farm_and_charm（任务书里的"宴会锅"）烹饪锅。 */
public final class FarmAndCharmCookingPotBatchDelegate extends LetsDoCookingPotBatchDelegate {

    @Override
    protected boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        return LetsDoReflection.isFarmAndCharmCookingPot(blockEntity);
    }

    @Override
    protected String outputPortId() {
        return "farm_and_charm:cooking_pot:output";
    }
}
