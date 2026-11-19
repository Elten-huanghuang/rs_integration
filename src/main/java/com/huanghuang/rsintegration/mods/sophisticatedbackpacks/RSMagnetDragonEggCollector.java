package com.huanghuang.rsintegration.mods.sophisticatedbackpacks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.BlockEvent;
import net.p3pp3rf1y.sophisticatedcore.upgrades.ContentsFilterLogic;

import java.util.function.Predicate;

/** 分批扫描原版龙蛋，通过权限检查后按原版掉落表生成物品。 */
public final class RSMagnetDragonEggCollector {
    private static final int SCAN_LIMIT = 512;
    private final Predicate<BlockEvent.BreakEvent> breakCanceled;
    private long cursor;

    public RSMagnetDragonEggCollector() {
        this(event -> MinecraftForge.EVENT_BUS.post(event));
    }

    RSMagnetDragonEggCollector(Predicate<BlockEvent.BreakEvent> breakCanceled) {
        this.breakCanceled = breakCanceled;
    }

    public void collect(ServerLevel level, BlockPos center, int radius, ServerPlayer player,
                        ContentsFilterLogic filter) {
        if (player.serverLevel() != level || player.isSpectator() || !player.mayBuild()
                || !filter.matchesFilter(new ItemStack(Items.DRAGON_EGG))
                || !level.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)) return;

        int range = Math.max(0, radius);
        long width = 2L * range + 1;
        long volume = width * width * width;
        for (int checked = 0; checked < Math.min(SCAN_LIMIT, volume); checked++) {
            long index = cursor % volume;
            cursor = (index + 1) % volume;
            BlockPos pos = center.offset(offset(index % width), offset((index / width) % width),
                    offset(index / (width * width)));
            if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!state.is(Blocks.DRAGON_EGG) || !level.mayInteract(player, pos)
                    || breakCanceled.test(new BlockEvent.BreakEvent(level, pos, state, player))
                    || level.getBlockState(pos) != state) continue;

            // 先确认方块已移除，再按原版战利品表掉落，且不调用手持工具的挖掘逻辑。
            if (!level.destroyBlock(pos, false, player)) continue;
            Block.dropResources(state, level, pos, null, player, ItemStack.EMPTY);
            return;
        }
    }

    private static int offset(long index) {
        return (int) ((index + 1) / 2) * (index % 2 == 0 ? -1 : 1);
    }
}
