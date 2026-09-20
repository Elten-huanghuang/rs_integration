package com.huanghuang.rsintegration.crafting.planning;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.concurrent.atomic.AtomicLong;

/** Session-wide non-zero ids shared by every client preview entry point. */
@OnlyIn(Dist.CLIENT)
public final class PlanningRequestIds {
    private static final AtomicLong NEXT = new AtomicLong(1L);

    private PlanningRequestIds() {}

    public static long next() {
        return NEXT.getAndUpdate(value -> value >= Long.MAX_VALUE ? 1L : value + 1L);
    }
}
