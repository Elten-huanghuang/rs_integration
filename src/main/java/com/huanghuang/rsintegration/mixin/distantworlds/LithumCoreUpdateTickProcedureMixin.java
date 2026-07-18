package com.huanghuang.rsintegration.mixin.distantworlds;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.mcreator.distantworlds.procedures.LithumCoreUpdateTickProcedure", remap = false)
public abstract class LithumCoreUpdateTickProcedureMixin {
    @Redirect(
            method = "execute",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;random()D", ordinal = 8),
            remap = false,
            require = 0
    )
    private static double rsi$disableDestabilizedFironFailure(
            LevelAccessor level, double x, double y, double z) {
        return rsi$failureRoll(level, x, y, z);
    }

    @Redirect(
            method = "execute",
            at = @At(value = "INVOKE", target = "Ljava/lang/Math;random()D", ordinal = 13),
            remap = false,
            require = 0
    )
    private static double rsi$disableNormalFironFailure(
            LevelAccessor level, double x, double y, double z) {
        return rsi$failureRoll(level, x, y, z);
    }

    private static double rsi$failureRoll(LevelAccessor level, double x, double y, double z) {
        if (!RSIntegrationConfig.DISABLE_DISTANT_WORLDS_FIRON_FAILURE.get()) {
            return Math.random();
        }
        BlockEntity core = level.getBlockEntity(BlockPos.containing(x, y, z));
        if (core != null && core.getPersistentData().getString("CurrentRecipe").startsWith("firon_")) {
            return 0.0D;
        }
        return Math.random();
    }
}
