package com.huanghuang.rsintegration.mods.farmandcharm;

import com.huanghuang.rsintegration.reflection.probes.LetsDoReflection;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * bakery（Let's Do 烘焙屋）小烹饪锅。
 * <p>
 * 反编译核对：它的方块实体 {@code SmallCookingPotBlockEntity} 与 farm_and_charm /
 * candlelight 的锅**逐条同构** —— 同一套槽位布局（0–5 食材、6 盛装容器、7 产物）、
 * 同一个配方类型 {@code farm_and_charm:pot_cooking} 与同一个配方类
 * {@code net.satisfy.farm_and_charm.recipe.CookingPotRecipe}、同一条热源标签
 * {@code farm_and_charm:allows_cooking}，扣料与扣容器的语义也一致。
 * <p>
 * 因此这里只覆盖"这台机器是不是 bakery 的锅"与输出端口 ID，其余全部沿用
 * {@link LetsDoCookingPotBatchDelegate}。
 */
public final class BakeryCookingPotBatchDelegate extends LetsDoCookingPotBatchDelegate {

    @Override
    protected boolean isSupportedBlockEntity(BlockEntity blockEntity) {
        return LetsDoReflection.isBakeryCookingPot(blockEntity);
    }

    @Override
    protected String outputPortId() {
        return "bakery:cooking_pot:output";
    }
}
