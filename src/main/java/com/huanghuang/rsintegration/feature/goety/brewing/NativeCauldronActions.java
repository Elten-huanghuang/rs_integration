package com.huanghuang.rsintegration.feature.goety.brewing;

import com.Polarice3.Goety.common.blocks.BrewCauldronBlock;
import com.Polarice3.Goety.common.blocks.entities.BrewCauldronBlockEntity;
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
    /** Same 8-block cube as Goety's candle search; never loads missing chunks. Called on actions only. */
    public static long availableSouls(BrewCauldronBlockEntity be) {
        var level = be.getLevel();
        if (level == null) return 0;
        long souls = 0;
        for (var pos : net.minecraft.core.BlockPos.betweenClosed(be.getBlockPos().offset(-8, -8, -8), be.getBlockPos().offset(8, 8, 8))) {
            if (level.hasChunkAt(pos) && level.getBlockEntity(pos) instanceof com.Polarice3.Goety.api.blocks.entities.ISoulCandle candle)
                souls += Math.max(0, candle.getSouls());
        }
        return souls;
    }
    /** A single explicit container interaction, with no call to the broken empty-cauldron path. */
    public static ItemStack useContainer(ServerPlayer player, BrewCauldronBlockEntity be, ItemStack one) {
        var level = be.getLevel();
        var state = be.getBlockState();
        if (level == null || !state.hasProperty(BrewCauldronBlock.LEVEL)) return one;
        int water = state.getValue(BrewCauldronBlock.LEVEL);
        if (one.is(Items.GLASS_BOTTLE) && water <= 0) return one;
        if (water == 4) {
            // Native colour animation represents a full cauldron, not a fourth serving.
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
        // Block.use's creative branch routes the product into inventory. The editor instead
        // exchanges its own real slot item using the same public cauldron rules, exactly once.
        ItemStack output;
        if (one.is(Items.WATER_BUCKET)) output = new ItemStack(Items.BUCKET);
        else if (water(one)) output = new ItemStack(Items.GLASS_BOTTLE);
        else if (one.is(Items.GLASS_BOTTLE)) {
            output = be.mode == BrewCauldronBlockEntity.Mode.COMPLETED ? be.getBrew().copy()
                    : PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
        } else return one;
        int target = be.getTargetLevel(one, player); // Includes native bottling discounts; never call twice.
        if (target < 0) return one;
        if (one.is(Items.GLASS_BOTTLE) && be.mode == BrewCauldronBlockEntity.Mode.COMPLETED)
            com.Polarice3.Goety.utils.SEHelper.increaseBottling(player);
        if (target == 0) be.mode = be.reset();
        be.getLevel().setBlockAndUpdate(be.getBlockPos(), be.getBlockState().setValue(BrewCauldronBlock.LEVEL, target));
        be.markUpdated(); return output;
    }
}
