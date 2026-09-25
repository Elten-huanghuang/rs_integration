package com.huanghuang.rsintegration.compat.historystages;

import com.huanghuang.rsintegration.RSIntegrationMod;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.UUID;

/** Optional History Stages recipe-lock bridge. */
public final class HistoryStagesCompat {
    private static final String RECIPE_HANDLER =
            "net.bananemdnsa.historystages.events.RecipeHandler";
    private static final String STAGE_LOCK_HELPER =
            "net.bananemdnsa.historystages.util.lock.StageLockHelper";
    private static volatile Access access;
    private static volatile boolean unavailable;

    private HistoryStagesCompat() {}

    /** Returns whether History Stages currently denies this recipe to the player. */
    public static boolean isRecipeLocked(@Nullable Recipe<?> recipe,
                                         @Nullable ServerPlayer player) {
        if (recipe == null) return false;
        Access bridge = bridge();
        if (bridge == null) return false;
        try {
            if ((Boolean) bridge.outputLocked.invoke(null, recipe, false)) return true;
            ResourceLocation recipeId = recipe.getId();
            if (recipeId != null && (Boolean) bridge.recipeIdLocked.invoke(null, recipeId, false)) return true;
            if (player == null) return false;
            ItemStack output = recipe.getResultItem(RegistryAccess.EMPTY);
            return !output.isEmpty()
                    && (Boolean) bridge.actionLockedForPlayer.invoke(
                    null, output, player.getUUID(), "recipe");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            RSIntegrationMod.LOGGER.debug("[RSI-HistoryStages] lock check failed", failure);
            return false;
        }
    }

    @Nullable
    private static Access bridge() {
        Access ready = access;
        if (ready != null || unavailable) return ready;
        if (!ModList.get().isLoaded(ModIds.HISTORY_STAGES)) {
            unavailable = true;
            return null;
        }
        try {
            Class<?> handler = Class.forName(RECIPE_HANDLER, false,
                    HistoryStagesCompat.class.getClassLoader());
            Class<?> helper = Class.forName(STAGE_LOCK_HELPER, false,
                    HistoryStagesCompat.class.getClassLoader());
            ready = new Access(
                    handler.getMethod("isOutputLocked", Recipe.class, boolean.class),
                    handler.getMethod("isRecipeIdLocked", ResourceLocation.class, boolean.class),
                    helper.getMethod("isActionLockedForPlayer", ItemStack.class,
                            UUID.class, String.class));
            access = ready;
            return ready;
        } catch (ReflectiveOperationException | LinkageError failure) {
            unavailable = true;
            RSIntegrationMod.LOGGER.warn("[RSI-HistoryStages] compatibility API unavailable", failure);
            return null;
        }
    }

    private record Access(Method outputLocked, Method recipeIdLocked,
                          Method actionLockedForPlayer) {}
}
