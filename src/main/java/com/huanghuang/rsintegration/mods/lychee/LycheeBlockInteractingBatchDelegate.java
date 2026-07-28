package com.huanghuang.rsintegration.mods.lychee;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.batch.GenericBatchDelegate;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Logical executor anchored to a real, bound Lychee interaction block. */
public final class LycheeBlockInteractingBatchDelegate extends GenericBatchDelegate {
    private static final ResourceLocation HYDRAULIC_PRESS =
            new ResourceLocation("rustic_engineer", "hydraulic_press");

    private ServerLevel machineLevel;
    private BlockPos machinePos;

    @Override
    public boolean validateAndInit(@Nonnull ServerPlayer player, @Nonnull ResourceLocation recipeId,
                                   @Nullable ResourceLocation dim, @Nonnull BlockPos pos) {
        ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
        if (!isHydraulicPress(level, pos)) return false;
        machineLevel = level;
        machinePos = pos.immutable();
        return super.validateAndInit(player, recipeId, dim, pos);
    }

    @Override
    public boolean validateExecutionContext(@Nullable ServerPlayer player) {
        return super.validateExecutionContext(player) && isHydraulicPress(machineLevel, machinePos);
    }

    @Override
    public boolean acceptsMachineWithoutBlockEntity(@Nonnull ServerLevel level, @Nonnull BlockPos pos) {
        return isHydraulicPress(level, pos);
    }

    @Override
    protected boolean isMachineCraftFinished(ServerLevel level, BlockEntity be) {
        return isHydraulicPress(level, machinePos) && super.isMachineCraftFinished(level, be);
    }

    @Override
    @Nullable
    public BlockPos getMachinePos() {
        return machinePos;
    }

    private static boolean isHydraulicPress(@Nullable ServerLevel level, @Nullable BlockPos pos) {
        if (level == null || pos == null) return false;
        if (!level.isLoaded(pos)) level.getChunk(pos);
        return HYDRAULIC_PRESS.equals(ForgeRegistries.BLOCKS.getKey(
                level.getBlockState(pos).getBlock()));
    }
}
