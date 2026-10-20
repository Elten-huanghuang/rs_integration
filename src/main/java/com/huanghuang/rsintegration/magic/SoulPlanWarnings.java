package com.huanghuang.rsintegration.magic;

import com.huanghuang.rsintegration.crafting.CraftStorageEndpoint;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * 灵魂预览的展示口径。
 *
 * <p>预览与执行共用 {@link SoulPlanPreview#estimate}，这里只负责把同一个数翻成
 * 玩家看得懂的文案，不再各自算一遍可用量——那正是「预览说够、执行拿不到」的来源。</p>
 */
public final class SoulPlanWarnings {
    private SoulPlanWarnings() {}

    /**
     * 灵魂计划提示；{@code total <= 0} 时返回 null。
     *
     * <p>可达时给出「总需求 / 后端可用 / 后端承担 / 机器与 Arca 补足」；
     * 不可达时只说总需求，因为后端量未知，显示 0 会被读成「一份都没有」。</p>
     */
    @Nullable
    public static Component describe(
            ServerPlayer player, @Nullable CraftStorageEndpoint endpoint, long total) {
        if (total <= 0) return null;
        SoulPlanPreview.Estimate estimate = SoulPlanPreview.estimate(player, endpoint, total);
        if (!estimate.reachable()) {
            return Component.translatable(
                    "rsi.goety.warn.total_ritual_souls_required",
                    String.format("%,d", total));
        }
        return Component.translatable(
                "rsi.goety.warn.soul_plan",
                String.format("%,d", total),
                String.format("%,d", estimate.authoritativeOrZero()),
                String.format("%,d", estimate.backendShare()),
                String.format("%,d", estimate.fallbackShare()));
    }
}
