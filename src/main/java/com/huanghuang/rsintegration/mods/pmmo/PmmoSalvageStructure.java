package com.huanghuang.rsintegration.mods.pmmo;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;

/** Configured salvage-block validation, including the Embers Inferno Forge structure. */
public final class PmmoSalvageStructure {
    public static final ResourceLocation INFERNO_FORGE =
            new ResourceLocation("embers", "inferno_forge");
    public static final ResourceLocation INFERNO_FORGE_EDGE =
            new ResourceLocation("embers", "inferno_forge_edge");

    private PmmoSalvageStructure() {}

    public static boolean usesInfernoForge() {
        return INFERNO_FORGE.equals(PmmoSalvageCatalog.salvageBlockId());
    }

    /** Matches the live world-configured salvage block at interaction time. */
    public static boolean isBindingBlock(Block block) {
        ResourceLocation configured = PmmoSalvageCatalog.salvageBlockId();
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(block);
        if (configured == null || blockId == null) return false;
        return configured.equals(blockId)
                || INFERNO_FORGE.equals(configured) && INFERNO_FORGE_EDGE.equals(blockId);
    }

    @Nullable
    public static BlockPos resolveRoot(Level level, BlockPos clickedPos) {
        ResourceLocation configured = PmmoSalvageCatalog.salvageBlockId();
        if (configured == null) return null;
        ResourceLocation clicked = blockId(level, clickedPos);
        if (!INFERNO_FORGE.equals(configured)) {
            return configured.equals(clicked) ? clickedPos.immutable() : null;
        }
        if (INFERNO_FORGE.equals(clicked)) return lowerHalf(level, clickedPos);
        if (!INFERNO_FORGE_EDGE.equals(clicked)) return null;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                BlockPos candidate = clickedPos.offset(dx, 0, dz);
                if (INFERNO_FORGE.equals(blockId(level, candidate))) {
                    return lowerHalf(level, candidate);
                }
            }
        }
        return null;
    }

    public static boolean isValid(Level level, BlockPos boundPos) {
        ResourceLocation configured = PmmoSalvageCatalog.salvageBlockId();
        if (configured == null) return false;
        if (!INFERNO_FORGE.equals(configured)) {
            return configured.equals(blockId(level, boundPos));
        }
        BlockPos lower = resolveRoot(level, boundPos);
        if (lower == null || !validHalf(level, lower, DoubleBlockHalf.LOWER)
                || !validHalf(level, lower.above(), DoubleBlockHalf.UPPER)) return false;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (!INFERNO_FORGE_EDGE.equals(blockId(
                            level, lower.offset(dx, dy, dz)))) return false;
                }
            }
        }
        return true;
    }

    private static BlockPos lowerHalf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                == DoubleBlockHalf.UPPER) return pos.below().immutable();
        return pos.immutable();
    }

    private static boolean validHalf(Level level, BlockPos pos, DoubleBlockHalf expected) {
        BlockState state = level.getBlockState(pos);
        return INFERNO_FORGE.equals(blockId(level, pos))
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == expected;
    }

    @Nullable
    private static ResourceLocation blockId(Level level, BlockPos pos) {
        return ForgeRegistries.BLOCKS.getKey(level.getBlockState(pos).getBlock());
    }
}
