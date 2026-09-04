package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.reflection.probes.EmbersReflection;
import com.huanghuang.rsintegration.util.Reflect;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Starts alchemy through an actual, powered Embers Beam Cannon. */
final class EmbersBeamCannonIgnition {
    static final double REQUIRED_EMBER = 1000.0;
    static final int MAX_DISTANCE = 64;
    static final int ENERGY_WAIT_TICKS = 10 * 20;

    private EmbersBeamCannonIgnition() {}

    static Result ignite(ServerLevel level, BlockPos tabletPos, BlockEntity tablet) {
        Class<?> cannonClass = EmbersReflection.beamCannonBEClass;
        if (cannonClass == null) return Result.NO_CANNON;

        boolean foundAlignedCannon = false;
        for (Direction tabletToCannon : Direction.values()) {
            Direction beamDirection = tabletToCannon.getOpposite();
            for (int distance = 1; distance <= MAX_DISTANCE; distance++) {
                BlockPos scanPos = tabletPos.relative(tabletToCannon, distance);
                if (!level.isLoaded(scanPos)) break;

                BlockEntity candidate = level.getBlockEntity(scanPos);
                if (candidate != null && cannonClass.isInstance(candidate)) {
                    if (!faces(candidate, beamDirection)) break;
                    foundAlignedCannon = true;
                    double ember = readEmber(candidate);
                    if (ember < REQUIRED_EMBER) break;
                    return fire(candidate, beamDirection, tablet);
                }

                if (blocksBeam(level, scanPos, candidate)) break;
            }
        }
        return foundAlignedCannon ? Result.INSUFFICIENT_EMBER : Result.NO_CANNON;
    }

    private static boolean faces(BlockEntity cannon, Direction beamDirection) {
        BlockState state = cannon.getBlockState();
        return state.hasProperty(BlockStateProperties.FACING)
                && state.getValue(BlockStateProperties.FACING) == beamDirection;
    }

    private static boolean blocksBeam(ServerLevel level, BlockPos pos, BlockEntity blockEntity) {
        if (blockEntity != null) {
            Class<?> sparkable = EmbersReflection.isparkableClass;
            if (sparkable != null && sparkable.isInstance(blockEntity)) return true;
            Class<?> receiver = EmbersReflection.iemberPacketReceiverClass;
            if (receiver != null && receiver.isInstance(blockEntity)) return true;
        }
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private static double readEmber(BlockEntity cannon) {
        Object capability = Reflect.getField(cannon, "capability").orElse(null);
        Object value = capability == null ? null : Reflect.invoke(capability, "getEmber").orElse(null);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static Result fire(BlockEntity cannon, Direction beamDirection, BlockEntity tablet) {
        int progressBefore = Reflect.getIntField(tablet, "progress").orElse(0);
        Method fire = Reflect.findMethod(cannon.getClass(), "fire", new Class<?>[]{Direction.class});
        if (fire == null) return Result.FIRE_FAILED;
        try {
            fire.invoke(cannon, beamDirection);
        } catch (ReflectiveOperationException | RuntimeException e) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Beam Cannon at {} failed to fire",
                    cannon.getBlockPos(), e);
            return Result.FIRE_FAILED;
        }
        int progressAfter = Reflect.getIntField(tablet, "progress").orElse(0);
        if (progressAfter <= progressBefore) {
            RSIntegrationMod.LOGGER.warn("[RSI-Embers] Beam Cannon at {} fired but tablet at {} did not start",
                    cannon.getBlockPos(), tablet.getBlockPos());
            return Result.FIRE_FAILED;
        }
        RSIntegrationMod.LOGGER.debug("[RSI-Embers] Beam Cannon at {} started tablet at {}",
                cannon.getBlockPos(), tablet.getBlockPos());
        return Result.STARTED;
    }

    enum Result {
        STARTED(null),
        NO_CANNON("rsi.embers.error.no_beam_cannon"),
        INSUFFICIENT_EMBER("rsi.embers.error.beam_cannon_low_ember"),
        FIRE_FAILED("rsi.embers.error.spark_failed");

        private final String translationKey;

        Result(String translationKey) {
            this.translationKey = translationKey;
        }

        String translationKey() {
            return translationKey;
        }
    }
}
