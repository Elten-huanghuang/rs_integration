package com.huanghuang.rsintegration.mods.profoundcurse;

import com.example.profound_curse.block.CursedPedestalBlock;
import com.example.profound_curse.block.CursedPedestalBlockEntity;
import com.example.profound_curse.block.QuartzDisplayStandBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/** 诅咒基座真实结构、槽位和方块实体访问。 */
final class CursedPedestalStructure {
    static final int DISPLAY_SLOT_COUNT = 8;
    static final int CATALYST_SLOT = DISPLAY_SLOT_COUNT;
    static final int CRAFT_DURATION = 100;
    static final long CRAFT_TIMEOUT_TICKS = 200L;
    static final String DISPLAY_GROUP = "display_stands";
    static final String CATALYST_GROUP = "pedestal";

    /**
     * 与 PedestalPosition.ORDERED 完全一致：
     * 北、东北、东、东南、南、西南、西、西北。
     */
    static final List<BlockPos> DISPLAY_OFFSETS = List.of(
            new BlockPos(0, 0, -3),
            new BlockPos(2, 0, -2),
            new BlockPos(3, 0, 0),
            new BlockPos(2, 0, 2),
            new BlockPos(0, 0, 3),
            new BlockPos(-2, 0, 2),
            new BlockPos(-3, 0, 0),
            new BlockPos(-2, 0, -2)
    );

    private CursedPedestalStructure() {}

    static List<BlockPos> displayPositions(BlockPos pedestalPos) {
        return DISPLAY_OFFSETS.stream()
                .map(pedestalPos::offset)
                .toList();
    }

    @Nullable
    static CursedPedestalBlockEntity pedestal(
            ServerLevel level,
            BlockPos pos
    ) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof QuartzDisplayStandBlockEntity) {
            return null;
        }
        return blockEntity instanceof CursedPedestalBlockEntity pedestal
                ? pedestal : null;
    }

    @Nullable
    static QuartzDisplayStandBlockEntity displayStand(
            ServerLevel level,
            BlockPos pos
    ) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof QuartzDisplayStandBlockEntity stand
                ? stand : null;
    }

    static boolean structureChunksLoaded(
            ServerLevel level,
            BlockPos pedestalPos
    ) {
        if (!level.hasChunkAt(pedestalPos)) return false;
        return displayPositions(pedestalPos).stream()
                .allMatch(level::hasChunkAt);
    }

    static boolean placeDisplayItem(
            ServerLevel level,
            BlockPos pos,
            ItemStack stack
    ) {
        QuartzDisplayStandBlockEntity stand =
                displayStand(level, pos);
        if (stand == null
                || stand.hasItem()
                || stack.isEmpty()
                || stack.getCount() != 1) {
            return false;
        }
        stand.setItem(stack.copy());
        refreshVisualState(level, pos, stand);
        return true;
    }

    static boolean placeCatalyst(
            ServerLevel level,
            BlockPos pos,
            ItemStack stack
    ) {
        CursedPedestalBlockEntity pedestal =
                pedestal(level, pos);
        if (pedestal == null
                || pedestal.hasItem()
                || pedestal.isCrafting()
                || stack.isEmpty()
                || stack.getCount() != 1) {
            return false;
        }
        pedestal.setItem(stack.copy());
        refreshVisualState(level, pos, pedestal);
        return true;
    }

    static ItemStack removeStoredItem(
            ServerLevel level,
            BlockPos pos,
            CursedPedestalBlockEntity holder
    ) {
        ItemStack removed = holder.removeItem();
        refreshVisualState(level, pos, holder);
        return removed;
    }

    static void refreshAllVisualStates(
            ServerLevel level,
            BlockPos pedestalPos
    ) {
        CursedPedestalBlockEntity pedestal =
                pedestal(level, pedestalPos);
        if (pedestal != null) {
            refreshVisualState(level, pedestalPos, pedestal);
        }
        for (BlockPos pos : displayPositions(pedestalPos)) {
            QuartzDisplayStandBlockEntity stand =
                    displayStand(level, pos);
            if (stand != null) {
                refreshVisualState(level, pos, stand);
            }
        }
    }

    private static void refreshVisualState(
            ServerLevel level,
            BlockPos pos,
            CursedPedestalBlockEntity blockEntity
    ) {
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(CursedPedestalBlock.HAS_ITEM)) return;
        BlockState updated = state.setValue(
                CursedPedestalBlock.HAS_ITEM,
                blockEntity.hasItem()
        );
        if (!updated.equals(state)) {
            level.setBlock(pos, updated, 3);
        }
    }
}
