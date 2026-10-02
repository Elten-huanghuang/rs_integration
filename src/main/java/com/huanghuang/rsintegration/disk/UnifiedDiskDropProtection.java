package com.huanghuang.rsintegration.disk;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** 只由归墟盘的物品更新钩子调用；沿用原版实体，不替换、复制或改写盘的 UUID。 */
public final class UnifiedDiskDropProtection {
    private static final String SAFE_POSITION = "RSIUnifiedDiskSafePosition";

    private UnifiedDiskDropProtection() {}

    public static void update(ItemEntity entity) {
        Level level = entity.level();
        if (level.isClientSide || entity.getItem().isEmpty()) return;
        // 原版 makeFakeItem 用不可拾取的临时实体展示物品，仍允许其按原逻辑移除。
        if (entity.getAge() == entity.getItem().getEntityLifespan(level) - 1 && entity.hasPickUpDelay()) return;
        entity.setUnlimitedLifetime();
        entity.lifespan = Integer.MAX_VALUE;
        entity.clearFire();

        CompoundTag persistent = entity.getPersistentData();
        CompoundTag safe = persistent.getCompound(SAFE_POSITION);
        String dimension = level.dimension().location().toString();
        boolean valid = validPosition(safe, dimension, level);
        double y = entity.getY();
        // 在 Entity.checkBelowWorld 的 minHeight - 64 删除边界之前救回。
        if (y < level.getMinBuildHeight() - 16.0) {
            double x = valid ? safe.getDouble("X") : entity.getX();
            double z = valid ? safe.getDouble("Z") : entity.getZ();
            double destinationY = valid ? safe.getDouble("Y") : level.getMinBuildHeight() + 2.0;
            // 保留同一个掉落物实体；无重生副本、无跨维度移动、无强制区块加载。
            entity.setPos(x, destinationY, z);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.setNoGravity(true);
            entity.hasImpulse = true;
        } else if (y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()
                && (!valid || entity.onGround())) {
            double x = entity.getX(), z = entity.getZ();
            if (!valid || safe.getDouble("X") != x || safe.getDouble("Y") != y || safe.getDouble("Z") != z) {
                CompoundTag position = new CompoundTag();
                position.putString("Dimension", dimension);
                position.putDouble("X", x); position.putDouble("Y", y); position.putDouble("Z", z);
                persistent.put(SAFE_POSITION, position);
            }
        }
    }

    private static boolean validPosition(CompoundTag position, String dimension, Level level) {
        if (!dimension.equals(position.getString("Dimension"))) return false;
        if (!position.contains("X", Tag.TAG_DOUBLE) || !position.contains("Y", Tag.TAG_DOUBLE)
                || !position.contains("Z", Tag.TAG_DOUBLE)) return false;
        if (!Double.isFinite(position.getDouble("X")) || !Double.isFinite(position.getDouble("Y"))
                || !Double.isFinite(position.getDouble("Z"))) return false;
        return Math.abs(position.getDouble("X")) < 30_000_000
                && Math.abs(position.getDouble("Z")) < 30_000_000
                && position.getDouble("Y") >= level.getMinBuildHeight()
                && position.getDouble("Y") < level.getMaxBuildHeight();
    }
}
