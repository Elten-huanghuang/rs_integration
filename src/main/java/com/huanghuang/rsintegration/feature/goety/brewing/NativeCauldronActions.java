package com.huanghuang.rsintegration.feature.goety.brewing;

import com.Polarice3.Goety.common.blocks.BrewCauldronBlock;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
import com.Polarice3.Goety.utils.SEHelper;
import com.huanghuang.rsintegration.feature.goety.integration.GoetyCauldronCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.*;
import net.minecraft.world.phys.*;

public final class NativeCauldronActions {
    private NativeCauldronActions() {}
    public static boolean water(ItemStack stack) {
        return stack.is(Items.WATER_BUCKET) || stack.is(Items.POTION) && PotionUtils.getPotion(stack) == Potions.WATER;
    }
    /** 搜索范围与 Goety 的烛台查找一致；不会加载未加载区块，仅在玩家操作时调用。 */
    public static long availableSouls(BrewCauldronBlockEntity be) {
        var level = be.getLevel();
        if (level == null) return 0;
        long souls = 0;
        for (var pos : BlockPos.betweenClosed(be.getBlockPos().offset(-8, -8, -8), be.getBlockPos().offset(8, 8, 8))) {
            if (level.hasChunkAt(pos)) {
                int candleSouls = GoetyCauldronCompat.soulCount(level.getBlockEntity(pos));
                if (candleSouls >= 0) souls += candleSouls;
            }
        }
        return souls;
    }
    /** 执行一次明确的容器交互，不经过空坩埚的异常处理路径。 */
    public static ItemStack useContainer(ServerPlayer player, BrewCauldronBlockEntity be, ItemStack one) {
        var level = be.getLevel();
        var state = be.getBlockState();
        if (level == null || !state.hasProperty(BrewCauldronBlock.LEVEL)) return one;
        int water = state.getValue(BrewCauldronBlock.LEVEL);
        if (one.is(Items.GLASS_BOTTLE) && water <= 0) return one;
        if (water == 4) {
            // 原生颜色动画使用 4 表示坩埚已满，不代表还有第四份药剂。
            be.update = 0;
            state = state.setValue(BrewCauldronBlock.LEVEL, 3);
            level.setBlockAndUpdate(be.getBlockPos(), state);
        }
        if (player.isCreative()) return creativeExchange(player, be, one);
        var original = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, one);
        try {
            state.use(level, player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(be.getBlockPos()), Direction.UP, be.getBlockPos(), false));
            return player.getMainHandItem();
        } finally {
            player.setItemInHand(InteractionHand.MAIN_HAND, original);
            player.getInventory().setChanged();
        }
    }
    private static ItemStack creativeExchange(ServerPlayer player, BrewCauldronBlockEntity be, ItemStack one) {
        // 方块交互的创造模式分支会将产物放入玩家背包；编辑器需按相同规则只结算一次，
        // 并将结果交还给自己的实际物品槽。
        ItemStack output;
        if (one.is(Items.WATER_BUCKET)) output = new ItemStack(Items.BUCKET);
        else if (water(one)) output = new ItemStack(Items.GLASS_BOTTLE);
        else if (one.is(Items.GLASS_BOTTLE)) {
            output = be.mode == BrewCauldronBlockEntity.Mode.COMPLETED ? be.getBrew().copy()
                    : PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        } else return one;
        int target = be.getTargetLevel(one, player); // 包含原生装瓶折扣，此处只调用一次。
        if (target < 0) return one;
        if (one.is(Items.GLASS_BOTTLE) && be.mode == BrewCauldronBlockEntity.Mode.COMPLETED)
            SEHelper.increaseBottling(player);
        if (target == 0) be.mode = be.reset();
        be.getLevel().setBlockAndUpdate(be.getBlockPos(), be.getBlockState().setValue(BrewCauldronBlock.LEVEL, target));
        be.markUpdated(); return output;
    }
}
