package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Executes shared Farmer's Delight cooking recipes in Twilight Delight's Fiery
 * Cooking Pot. 炽铁锅与农夫乐事烹饪锅同构（同槽位、同配方、同容器语义），
 * 仅热源判定不同：炽铁锅自供热，迷宫炉上方的锅亦视为加热。
 */
public final class TwilightDelightFieryPotBatchDelegate extends CookingPotBatchDelegate {

    @Override
    protected boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        // 炽铁锅的方块实体仍是 FD 的 CookingPotBlockEntity，槽位与反射通道完全沿用。
        // 机器身份由绑定目标（方块类）确认，此处不再重复判方块类型。
        return super.isSupportedBlockEntity(blockEntity);
    }

    @Override
    protected boolean isHeated(BlockEntity be) {
        // 炽铁锅自供热 / 迷宫炉上方供热，绕过原版热源探测；
        // 其余情形仍回退到烹饪锅自身的加热判定。
        if (TwilightDelightFieryPotSupport.isNativelyHeated(be)) return true;
        return super.isHeated(be);
    }

    @Override
    protected boolean inputBufferEnabled() {
        return RSIntegrationConfig.ENABLE_TWILIGHT_DELIGHT_FIERY_POT_INPUT_BUFFER.get();
    }

    @Override
    protected int inputBufferLimit() {
        return Math.max(1, RSIntegrationConfig.TWILIGHT_DELIGHT_FIERY_POT_INPUT_BUFFER_LIMIT.get());
    }

    @Override
    protected String outputPortId() {
        return "twilightdelight:fiery_cooking_pot:output";
    }
}
