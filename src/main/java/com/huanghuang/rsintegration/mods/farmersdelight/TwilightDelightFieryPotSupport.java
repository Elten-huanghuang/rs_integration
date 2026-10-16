package com.huanghuang.rsintegration.mods.farmersdelight;

import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;

/**
 * Optional-safe heat-source override for Twilight Delight's Fiery Cooking Pot.
 * <p>
 * 炽铁锅直接继承农夫乐事的 {@code CookingPotBlock}，其方块实体仍是 FD 的
 * {@code CookingPotBlockEntity}，因此槽位、配方与容器语义全部复用 FD 烹饪锅。
 * 唯一差别是热源判定：Twilight Delight 用 mixin 让炽铁锅恒为加热状态，
 * 并让迷宫炉上方的锅也视为加热。本类只补这条判定，绝不引用 twilightdelight
 * 自身的类型，缺失该模组时安全降级。
 */
public final class TwilightDelightFieryPotSupport {

    private static final String FIERY_POT_BLOCK =
            "dev.xkmc.twilightdelight.content.block.FieryCookingPotBlock";
    private static final String MAZE_STOVE_BLOCK =
            "dev.xkmc.twilightdelight.content.block.MazeStoveBlock";

    private TwilightDelightFieryPotSupport() {}

    /** 该方块是否为 Twilight Delight 的炽铁锅（自供热）。 */
    public static boolean isFieryPot(@Nullable Block block) {
        return block != null && FIERY_POT_BLOCK.equals(block.getClass().getName());
    }

    /** 该方块是否为 Twilight Delight 的迷宫炉（会为其正上方的锅供热）。 */
    public static boolean isMazeStove(@Nullable Block block) {
        return block != null && MAZE_STOVE_BLOCK.equals(block.getClass().getName());
    }

    /** 整合包当前是否装载了 Twilight Delight。 */
    public static boolean isModLoaded() {
        return ModList.get().isLoaded(ModIds.TWILIGHTDELIGHT);
    }

    /**
     * 炽铁锅自带热源，且 Twilight Delight 让迷宫炉上方的锅同样视为加热。
     * 两者都绕过原版 {@code HeatableBlockEntity} 探测，因此需要在此显式放行。
     */
    public static boolean isNativelyHeated(@Nullable Level level, @Nullable BlockPos pos) {
        if (level == null || pos == null
                || !level.hasChunkAt(pos) || !level.hasChunkAt(pos.below())) {
            return false;
        }
        if (isFieryPot(level.getBlockState(pos).getBlock())) return true;
        return isMazeStove(level.getBlockState(pos.below()).getBlock());
    }

    /** 方块实体所在位置是否由炽铁锅自身供热。 */
    public static boolean isNativelyHeated(@Nullable BlockEntity blockEntity) {
        return blockEntity != null
                && isNativelyHeated(blockEntity.getLevel(), blockEntity.getBlockPos());
    }
}
