package com.huanghuang.rsintegration.mods.farmandcharm;

import com.huanghuang.rsintegration.reflection.probes.LetsDoReflection;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * candlelight（烛火晚宴）大汤锅。
 * <p>
 * 整合包里的"汤锅"与"铜汤锅"是同一个方块 {@code candlelight:cooking_pot}
 * （方块名不同，方块实体同为 {@code LargeCookingPotBlockEntity}），因此共用一个委托。
 * 配方仍是 farm_and_charm 的 {@code CookingPotRecipe}，与基类一致，无需覆盖。
 */
public final class CandlelightCookingPotBatchDelegate extends LetsDoCookingPotBatchDelegate {

    @Override
    protected boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        return LetsDoReflection.isCandlelightCookingPot(blockEntity);
    }

    @Override
    protected String outputPortId() {
        return "candlelight:cooking_pot:output";
    }
}
