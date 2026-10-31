package com.huanghuang.rsintegration.feature.experience;

import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/** 等级公式全程使用 long，不受原版 totalExperience 的 int 上限限制。 */
public final class ExperiencePoints {
    // 保证原版 getXpNeededForNextLevel 的 9 * level - 158 不发生 int 溢出。
    public static final int MAX_LEVEL = (int) ((Integer.MAX_VALUE + 158L) / 9L);
    public static final long MAX_POINTS = forLevel(MAX_LEVEL) + toNextLevel(MAX_LEVEL) - 1L;

    public record State(int level, float progress, long points) {}

    private ExperiencePoints() {}

    public static long forLevel(int level) {
        long value = Math.max(0, level);
        if (value <= 16) return value * value + 6L * value;
        if (value <= 31) return (5L * value * value - 81L * value + 720L) / 2L;
        return (9L * value * value - 325L * value + 4440L) / 2L;
    }

    public static long toNextLevel(int level) {
        if (level >= 30) return 9L * level - 158L;
        if (level >= 15) return 5L * level - 38L;
        return 2L * level + 7L;
    }

    public static boolean supported(Player player) {
        return player.experienceLevel >= 0 && player.experienceLevel <= MAX_LEVEL
                && Float.isFinite(player.experienceProgress)
                && player.experienceProgress >= 0.0F && player.experienceProgress < 1.0F;
    }

    public static long current(Player player) {
        int level = Math.max(0, Math.min(MAX_LEVEL, player.experienceLevel));
        double progress = Float.isFinite(player.experienceProgress)
                ? Math.max(0.0, Math.min(1.0, player.experienceProgress)) : 0.0;
        return Math.min(MAX_POINTS,
                forLevel(level) + (long) (progress * toNextLevel(level)));
    }

    public static State plan(long points, boolean roundDown) {
        long target = Math.max(0L, Math.min(MAX_POINTS, points));
        int low = 0;
        int high = MAX_LEVEL;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (forLevel(middle) <= target) low = middle;
            else high = middle - 1;
        }
        long base = forLevel(low);
        long width = toNextLevel(low);
        float progress = (float) ((double) (target - base) / width);
        long represented = base + (long) ((double) progress * width);
        // 原版进度只有 float 精度；只结算实际可表示的经验差额。
        if (roundDown && represented > target) progress = Math.nextDown(progress);
        if (!roundDown && represented < target) progress = Math.nextUp(progress);
        if (roundDown && represented < target) {
            float higher = Math.nextUp(progress);
            if (base + (long) ((double) higher * width) <= target) progress = higher;
        }
        if (!roundDown && represented > target) {
            float lower = Math.nextDown(progress);
            if (base + (long) ((double) lower * width) >= target) progress = lower;
        }
        if (progress >= 1.0F && low < MAX_LEVEL) {
            return new State(low + 1, 0.0F, forLevel(low + 1));
        }
        if (progress >= 1.0F) progress = Math.nextDown(1.0F);
        return new State(low, progress, base + (long) ((double) progress * width));
    }

    public static void apply(ServerPlayer player, State state) {
        player.experienceLevel = state.level();
        player.experienceProgress = state.progress();
        player.totalExperience = (int) Math.min(Integer.MAX_VALUE, state.points());
        // 高等级时 totalExperience 已饱和，不能依赖原版只比较该字段的自动同步。
        player.connection.send(new ClientboundSetExperiencePacket(
                player.experienceProgress, player.totalExperience, player.experienceLevel));
    }
}
