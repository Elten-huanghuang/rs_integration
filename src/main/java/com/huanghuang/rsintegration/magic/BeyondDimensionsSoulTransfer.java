package com.huanghuang.rsintegration.magic;

import com.wintercogs.beyonddimensions.api.storage.handler.impl.AbstractUnorderedStackHandler;
import com.wintercogs.beyonddimensions.api.storage.key.KeyAmount;
import net.minecraft.world.item.ItemStack;

/** Beyond Dimensions 库存中的灵魂读写，仅在对应 API 已加载时进入。 */
public final class BeyondDimensionsSoulTransfer {
    private BeyondDimensionsSoulTransfer() {}

    public static long deposit(AbstractUnorderedStackHandler storage,
                               long requested, boolean perform) {
        if (requested <= 0) return 0;
        long accepted = accepted(storage.insert(
                GoetySoulStackKey.INSTANCE, requested, true), requested);
        if (perform && accepted > 0) {
            storage.insert(GoetySoulStackKey.INSTANCE, accepted, false);
        }
        return accepted;
    }

    public static long deposit(AbstractUnorderedStackHandler storage,
                               ItemStack totem, long requested, boolean perform) {
        if (!GoetySoulTotem.isTotem(totem) || requested <= 0) return 0;
        long requestedNow = Math.min(requested, GoetySoulTotem.souls(totem));
        long accepted = deposit(storage, requestedNow, perform);
        int moved = saturate(accepted);
        if (perform && moved > 0) {
            GoetySoulTotem.extract(totem, moved, true);
        }
        return moved;
    }

    public static long withdraw(AbstractUnorderedStackHandler storage,
                                ItemStack totem, long requested, boolean perform) {
        if (!GoetySoulTotem.isTotem(totem) || requested <= 0) return 0;
        long requestedNow = Math.min(requested, GoetySoulTotem.space(totem));
        long available = storage.extract(
                GoetySoulStackKey.INSTANCE, requestedNow, true, false).amount();
        int moved = Math.min(saturate(available), GoetySoulTotem.space(totem));
        if (perform && moved > 0) {
            long extracted = storage.extract(
                    GoetySoulStackKey.INSTANCE, moved, false, false).amount();
            int confirmed = saturate(extracted);
            GoetySoulTotem.insert(totem, confirmed, true);
            return confirmed;
        }
        return moved;
    }

    private static long accepted(KeyAmount remainder, long requested) {
        return Math.max(0, requested - (remainder == null ? 0 : remainder.amount()));
    }

    private static int saturate(long amount) {
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, amount);
    }
}
