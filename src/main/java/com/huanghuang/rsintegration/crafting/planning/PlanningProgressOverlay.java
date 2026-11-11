package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.batch.BatchCraftNetworkHandler;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Locale;

/** 计算状态文案与取消操作；绘制统一交给 CraftProgressOverlay。 */
@OnlyIn(Dist.CLIENT)
public final class PlanningProgressOverlay {
    private PlanningProgressOverlay() {}

    public static boolean isVisible() {
        return RSIntegrationConfig.enabled(
                RSIntegrationConfig.CLIENT_SPEC, RSIntegrationConfig.PLANNING_HUD_VISIBLE);
    }

    /** 只切换客户端提示，不修改计划、计算请求或机器订单。 */
    public static void toggleVisible() {
        if (!RSIntegrationConfig.CLIENT_SPEC.isLoaded()) return;
        RSIntegrationConfig.PLANNING_HUD_VISIBLE.set(!isVisible());
        RSIntegrationConfig.saveClientConfig();
    }

    public static boolean cancelCurrent() {
        var current = PlanningProgressTracker.current();
        return current != null && cancel(current.requestId());
    }

    public static boolean cancel(long selectedId) {
        long requestId = PlanningProgressTracker.cancel(selectedId);
        if (requestId == 0L) return false;
        BatchCraftNetworkHandler.CHANNEL.sendToServer(new PlanningCancelPacket(requestId));
        return true;
    }

    public static int accent(PlanningProgressSnapshot.State state) {
        return switch (state) {
            case ACCEPTED, WARMING_UP, QUEUED -> 0xFFE0B35A;
            case RUNNING -> 0xFF68A9E8;
            case FINALIZING, SUCCEEDED -> 0xFF67BE7B;
            case FAILED, TIMED_OUT -> 0xFFE06C75;
            case CANCELLED, STALE -> 0xFF929DA5;
        };
    }

    public static String titleKey(PlanningProgressSnapshot.State state) {
        return "rsi.planning.state." + state.name().toLowerCase(Locale.ROOT);
    }

    public static String phaseKey(PlanningProgressSnapshot.Phase phase) {
        return "rsi.planning.phase." + phase.name().toLowerCase(Locale.ROOT);
    }

    static String formatElapsed(long elapsedMillis) {
        if (elapsedMillis < 10_000L) {
            return String.format(Locale.ROOT, "%.1fs", elapsedMillis / 1_000.0);
        }
        return (elapsedMillis / 1_000L) + "s";
    }

}
