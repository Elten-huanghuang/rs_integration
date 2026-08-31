package com.huanghuang.rsintegration.mods.aetherworks;

import com.huanghuang.rsintegration.reflection.probes.AetherworksReflection;
import com.huanghuang.rsintegration.util.Reflect;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.List;

/** Shared structure and inventory checks for the Aetherworks automation delegates. */
final class AetherworksMachineSafety {
    private AetherworksMachineSafety() {}

    @Nullable
    static BlockEntity findAttachedForge(Level level, BlockPos center, Object machine) {
        if (level == null || center == null || machine == null
                || AetherworksReflection.forgeBEClass == null) return null;
        BlockPos.MutableBlockPos cursor = center.mutable();
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockEntity candidate = level.getBlockEntity(cursor);
                    if (candidate != null
                            && AetherworksReflection.forgeBEClass.isInstance(candidate)
                            && isAttachedToForge(candidate, machine)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    static boolean isAttachedToForge(@Nullable Object forge, @Nullable Object machine) {
        if (forge == null || machine == null) return false;
        Object parts = Reflect.invoke(forge, "getParts").orElse(null);
        return containsIdentity(parts, machine);
    }

    static boolean containsIdentity(@Nullable Object parts, Object machine) {
        if (!(parts instanceof Iterable<?> iterable) || machine == null) return false;
        for (Object part : iterable) {
            if (part == machine) return true;
        }
        return false;
    }

    static boolean recoveredExpected(ItemStack recovered, ItemStack expected) {
        return recovered != null && expected != null
                && !recovered.isEmpty() && !expected.isEmpty()
                && recovered.getCount() >= expected.getCount()
                && ItemStack.isSameItemSameTags(recovered, expected);
    }

    static boolean recoveredExpectedSlots(List<ItemStack> recovered, List<ItemStack> expected) {
        if (recovered == null || expected == null || recovered.size() < expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            if (!recoveredExpected(recovered.get(i), expected.get(i))) return false;
        }
        return true;
    }
}
