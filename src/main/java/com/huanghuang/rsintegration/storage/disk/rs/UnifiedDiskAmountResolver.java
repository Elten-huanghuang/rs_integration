package com.huanghuang.rsintegration.storage.disk.rs;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.storage.disk.core.FrozenKey;
import com.huanghuang.rsintegration.storage.disk.core.UnifiedDiskCore;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把归墟盘上的精确 long 库存叠加到 RS 原生 int 缓存。
 *
 * <p>RS 缓存表示单个 Key 时仍受 int 上限限制。这里只补回被截断的磁盘部分，
 * 不把 RS 与磁盘当成两份库存重复相加。</p>
 */
public final class UnifiedDiskAmountResolver {
    private UnifiedDiskAmountResolver() {}

    @Nullable
    public static FrozenKey key(FrozenKey.Kind kind, Object stack) {
        try {
            return kind == FrozenKey.Kind.ITEM
                    ? FrozenKey.item((ItemStack) stack)
                    : FrozenKey.fluid((FluidStack) stack);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static Map<FrozenKey, Long> snapshot(@Nullable INetwork network,
                                                FrozenKey.Kind kind) {
        List<UnifiedDiskCore> disks = disks(network);
        if (disks.isEmpty()) return Map.of();
        Map<FrozenKey, Long> totals = new HashMap<>();
        for (UnifiedDiskCore disk : disks) {
            disk.table(kind).forEach((key, amount) ->
                    totals.merge(key, amount, SaturatingLongMath::add));
        }
        return totals;
    }

    public static long exactAmount(@Nullable INetwork network, FrozenKey key) {
        long total = 0L;
        for (UnifiedDiskCore disk : disks(network)) {
            total = SaturatingLongMath.add(total, disk.table(key.kind()).amount(key));
            if (total == Long.MAX_VALUE) break;
        }
        return total;
    }

    public static long mergeWithNative(@Nullable Long exact, long nativeAmount) {
        if (exact == null || exact <= 0L) return Math.max(0L, nativeAmount);
        long reportedByDisk = Math.min(exact, Integer.MAX_VALUE);
        long otherStorage = Math.max(0L, nativeAmount - reportedByDisk);
        return SaturatingLongMath.add(exact, otherStorage);
    }

    public static long itemAmount(@Nullable INetwork network, ItemStack stack,
                                  long nativeAmount) {
        FrozenKey key = key(FrozenKey.Kind.ITEM, stack);
        return key == null ? nativeAmount
                : mergeWithNative(exactAmount(network, key), nativeAmount);
    }

    public static long fluidAmount(@Nullable INetwork network, FluidStack stack,
                                   long nativeAmount) {
        FrozenKey key = key(FrozenKey.Kind.FLUID, stack);
        return key == null ? nativeAmount
                : mergeWithNative(exactAmount(network, key), nativeAmount);
    }

    private static List<UnifiedDiskCore> disks(@Nullable INetwork network) {
        if (network == null || network.getLevel() == null) return List.of();
        UnifiedDiskManager manager = UnifiedDiskManager.existing(
                network.getLevel().getServer());
        return manager == null ? List.of() : manager.mounts().disksForNetwork(network);
    }
}
