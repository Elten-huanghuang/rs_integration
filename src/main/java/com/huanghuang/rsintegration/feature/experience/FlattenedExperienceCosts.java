package com.huanghuang.rsintegration.feature.experience;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.function.IntConsumer;

/** 消耗按最低所需等级折算成经验点，不随玩家持有的等级上涨。 */
public final class FlattenedExperienceCosts {
    private FlattenedExperienceCosts() {}

    public static long points(int playerLevel, int consumedLevels, int requiredLevel) {
        int consumed = Math.max(0, Math.min(ExperiencePoints.MAX_LEVEL, consumedLevels));
        int reference = Math.max(0, Math.min(ExperiencePoints.MAX_LEVEL,
                Math.min(playerLevel, requiredLevel)));
        int start = Math.max(0, reference - consumed);
        return ExperiencePoints.forLevel(start + consumed) - ExperiencePoints.forLevel(start);
    }

    public static void chargeLevels(Player player, int change, int requiredLevel,
                                    IntConsumer original) {
        if (change >= 0 || !ExperiencePoints.supported(player)) {
            original.accept(change);
            return;
        }
        int previousLevel = player.experienceLevel;
        long previousPoints = ExperiencePoints.current(player);
        // 保留 Forge 的等级事件：取消扣费时不再扣除，调整扣费时以实际减少的等级结算。
        original.accept(change);
        int consumed = previousLevel - player.experienceLevel;
        if (consumed <= 0) return;
        long cost = points(previousLevel, consumed, requiredLevel);
        apply(player, ExperiencePoints.plan(Math.max(0L, previousPoints - cost), true));
    }

    private static void apply(Player player, ExperiencePoints.State state) {
        if (player instanceof ServerPlayer serverPlayer) {
            ExperiencePoints.apply(serverPlayer, state);
        } else {
            player.experienceLevel = state.level();
            player.experienceProgress = state.progress();
            player.totalExperience = (int) Math.min(Integer.MAX_VALUE, state.points());
        }
    }
}
