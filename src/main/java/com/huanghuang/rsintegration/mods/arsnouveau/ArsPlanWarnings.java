package com.huanghuang.rsintegration.mods.arsnouveau;
import java.lang.reflect.Field;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Plan-time Source cost and bound-machine status for supported Ars recipes. */
public final class ArsPlanWarnings {
    private ArsPlanWarnings() {}

    public static int sourceCost(Recipe<?> recipe) {
        if (recipe == null || !ArsRecipeClassifier.isAutomatable(
                ArsTileAccess.recipeTypeId(recipe))) {
            return 0;
        }
        return rawSourceCost(recipe);
    }

    static int rawSourceCost(Object recipe) {
        if (recipe == null) return 0;
        int cost = readIntField(recipe, "sourceCost");
        if (cost < 0) cost = readIntField(recipe, "source");
        return Math.max(0, cost);
    }

    private static int readIntField(Object value, String fieldName) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                Object raw = field.get(value);
                return raw instanceof Number number ? number.intValue() : -1;
            } catch (NoSuchFieldException ignored) {
                // Continue through the hierarchy.
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                return -1;
            }
        }
        return -1;
    }

    public static List<Component> getPlanWarnings(ServerPlayer player, Recipe<?> recipe,
                                               @Nullable ResourceLocation dim,
                                               @Nullable BlockPos pos) {
        List<Component> warnings = new ArrayList<>();
        if (recipe != null && ArsRecipeClassifier.isGlyph(ArsTileAccess.recipeTypeId(recipe))) {
            int requiredExperience = Math.max(0, readIntField(recipe, "exp"));
            if (requiredExperience > 0) {
                int availableExperience = totalExperience(player);
                String key = player.isCreative() || availableExperience >= requiredExperience
                        ? "rsi.ars_nouveau.warn.experience_available"
                        : "rsi.ars_nouveau.warn.experience_insufficient";
                warnings.add(Component.translatable(
                        key, format(requiredExperience), format(availableExperience)));
            }
        }

        int required = sourceCost(recipe);
        if (required <= 0) return warnings;

        ArsSourceProbe.SourceSnapshot snapshot = null;
        if (dim != null && pos != null) {
            ServerLevel level = CraftPacketUtils.resolveLevel(player.server, dim, player);
            if (level != null && level.isLoaded(pos)) {
                snapshot = ArsSourceProbe.probe(level, pos);
            }
        }

        if (snapshot == null || !snapshot.isAvailable()) {
            warnings.add(Component.translatable(
                    "rsi.ars_nouveau.warn.source_cost", format(required)));
        } else if (snapshot.current < required) {
            warnings.add(Component.translatable(
                    "rsi.ars_nouveau.warn.source_insufficient",
                    format(snapshot.current), format(required), format(snapshot.max)));
        } else {
            warnings.add(Component.translatable(
                    "rsi.ars_nouveau.warn.source_available",
                    format(required), format(snapshot.current), format(snapshot.max)));
        }
        return warnings;
    }

    private static int totalExperience(ServerPlayer player) {
        int completedLevels = player.experienceLevel;
        int base;
        if (completedLevels <= 16) {
            base = completedLevels * completedLevels + 6 * completedLevels;
        } else if (completedLevels <= 31) {
            base = (int) (2.5D * completedLevels * completedLevels
                    - 40.5D * completedLevels + 360.0D);
        } else {
            base = (int) (4.5D * completedLevels * completedLevels
                    - 162.5D * completedLevels + 2220.0D);
        }
        return base + (int) (player.experienceProgress * player.getXpNeededForNextLevel());
    }

    private static String format(long value) {
        return String.format("%,d", value);
    }
}
