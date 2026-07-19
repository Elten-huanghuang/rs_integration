package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/** Suppresses user-facing chat while the scheduler repeatedly probes machine readiness. */
public final class PreparationMessageScope {
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private PreparationMessageScope() {}

    public static IBatchDelegate.PreparationResult prepare(
            IBatchDelegate delegate, ServerPlayer player, ResourceLocation recipeId,
            @Nullable ResourceLocation dimension, BlockPos position) {
        return callSilently(() -> delegate.prepare(player, recipeId, dimension, position));
    }

    public static boolean validate(
            IBatchDelegate delegate, ServerPlayer player, ResourceLocation recipeId,
            @Nullable ResourceLocation dimension, BlockPos position) {
        return callSilently(() -> delegate.validateAndInit(
                player, recipeId, dimension, position));
    }

    public static boolean isSilent() {
        return DEPTH.get() > 0;
    }

    static <T> T callSilently(Supplier<T> action) {
        int previous = DEPTH.get();
        DEPTH.set(previous + 1);
        try {
            return action.get();
        } finally {
            if (previous == 0) DEPTH.remove();
            else DEPTH.set(previous);
        }
    }
}
