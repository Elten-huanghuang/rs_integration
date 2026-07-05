package com.huanghuang.rsintegration.mods.youkaishomecoming;

import com.huanghuang.rsintegration.RSIntegrationMod;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.lang.reflect.Field;

/**
 * Reflection utility for accessing TimedRecipeBlockEntity protected fields
 * (recipeProgress, totalTime) from outside the subclass hierarchy.
 */
public final class YhkReflect {

    private static final String TIMED_BE =
            "dev.xkmc.youkaishomecoming.content.pot.base.TimedRecipeBlockEntity";

    @Nullable
    private static volatile Field recipeProgressField;
    @Nullable
    private static volatile Field totalTimeField;
    private static volatile boolean probed;

    private YhkReflect() {}

    private static void probe() {
        if (probed) return;
        probed = true;
        try {
            Class<?> timed = Class.forName(TIMED_BE);
            recipeProgressField = timed.getDeclaredField("recipeProgress");
            recipeProgressField.setAccessible(true);
            totalTimeField = timed.getDeclaredField("totalTime");
            totalTimeField.setAccessible(true);
        } catch (Exception e) {
            RSIntegrationMod.LOGGER.warn("[RSI-YhkReflect] probe failed: {}", e.toString());
        }
    }

    public static int getRecipeProgress(BlockEntity be) {
        probe();
        if (recipeProgressField == null) return -1;
        try {
            return recipeProgressField.getInt(be);
        } catch (Exception e) {
            return -1;
        }
    }

    public static int getTotalTime(BlockEntity be) {
        probe();
        if (totalTimeField == null) return -1;
        try {
            return totalTimeField.getInt(be);
        } catch (Exception e) {
            return -1;
        }
    }
}
