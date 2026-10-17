package com.huanghuang.rsintegration.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** 带开放式池腔的共振魔力池方块，允许掉落物进入并触发 Botania 灌注。 */
public final class ResonantManaPoolBlock extends ResonantMagicBlock {
    private static final VoxelShape BASE = box(0, 0, 0, 16, 2, 16);
    private static final VoxelShape NORTH = box(0, 2, 0, 16, 8, 2);
    private static final VoxelShape SOUTH = box(0, 2, 14, 16, 8, 16);
    private static final VoxelShape WEST = box(0, 2, 2, 2, 8, 14);
    private static final VoxelShape EAST = box(14, 2, 2, 16, 8, 14);
    private static final VoxelShape COLLISION = Shapes.or(BASE, NORTH, SOUTH, WEST, EAST);
    private static final VoxelShape OUTLINE = box(0, 0, 0, 16, 8, 16);

    public ResonantManaPoolBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                               CollisionContext context) {
        return OUTLINE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                        CollisionContext context) {
        return COLLISION;
    }

    @Override
    public VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return COLLISION;
    }
}
