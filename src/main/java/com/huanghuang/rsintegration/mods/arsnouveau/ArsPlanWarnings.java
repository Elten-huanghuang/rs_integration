package com.huanghuang.rsintegration.mods.arsnouveau;

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
                java.lang.reflect.Field field = type.getDeclaredField(fieldName);
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

    private static String format(long value) {
        return String.format("%,d", value);
    }
}
