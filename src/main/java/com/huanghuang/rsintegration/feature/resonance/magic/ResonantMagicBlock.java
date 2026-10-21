package com.huanghuang.rsintegration.feature.resonance.magic;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/** 共振魔法方块的公共交互层；具体原生能量接口由可选实体实现。 */
public class ResonantMagicBlock extends Block
        implements EntityBlock, ResonantMagicBlockMarker {
    public ResonantMagicBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        BlockEntityType<?> type = ResonantMagicBlockEntities.typeFor(state.getBlock());
        return type == null ? null : type.create(pos, state);
    }

    @Override
    public void entityInside(BlockState state, Level level, BlockPos pos, Entity entity) {
        if (!level.isClientSide && entity instanceof ItemEntity item) {
            // Botania 是可选依赖，用反射调用可选入口，避免 Botania 缺失时
            // 共振方块类本身因为链接 ManaPool 而无法加载。
            OptionalBotaniaManaPool.tryInfuse(level, pos, item);
        }
        super.entityInside(state, level, pos, entity);
    }

    private static final class OptionalBotaniaManaPool {
        private static final String INTEROP =
                "com.huanghuang.rsintegration.mods.botania.ManaPoolInterop";

        private static void tryInfuse(Level level, BlockPos pos, ItemEntity item) {
            if (!(level instanceof net.minecraft.server.level.ServerLevel server)) return;
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity == null) return;
            try {
                ClassLoader loader = ResonantMagicBlock.class.getClassLoader();
                Class<?> interop = Class.forName(INTEROP, false, loader);
                Class<?> poolType = Class.forName("vazkii.botania.api.mana.ManaPool",
                        false, loader);
                Object pool = poolType.cast(entity);
                interop.getMethod("tryInfuse", net.minecraft.server.level.ServerLevel.class,
                        BlockPos.class, poolType, ItemEntity.class)
                        .invoke(null, server, pos, pool, item);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                // Botania 未安装或版本没有该公开接口时，方块仍作为普通共振方块工作。
            }
        }
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                    BlockEntityType<T> type) {
        return level.isClientSide ? null : (l, p, s, entity) -> {
            if (entity instanceof ResonantMagicStorage magic) magic.serverTick();
        };
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement,
                         boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) != null) {
            level.removeBlockEntity(pos);
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
}
