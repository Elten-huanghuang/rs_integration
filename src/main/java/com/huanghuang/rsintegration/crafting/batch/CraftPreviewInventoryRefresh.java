package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnostic;
import com.huanghuang.rsintegration.crafting.plan.IntegrationDiagnosticCode;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PreviewInventorySyncManager;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 一次用户请求共用刷新预算；后台路由、响应组装和缓存命中不能各自重置预算。 */
final class CraftPreviewInventoryRefresh implements CraftPlanResultDispatcher.Sink {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final CraftPlanResultDispatcher.Sink destination;
    private final int maximumRefreshes;
    private final BooleanSupplier currentRequest;
    private final BiPredicate<PlanningSnapshot, PlanResponse> inventoryChanged;
    private final Consumer<PlanningSnapshot> invalidate;
    private final Executor refreshExecutor;
    private final BiConsumer<PlanResponse, CraftPlanResultDispatcher.Sink> restart;
    private int refreshes;
    private boolean finished;
    private boolean refreshPending;

    CraftPreviewInventoryRefresh(CraftPlanResultDispatcher.Sink destination, int maximumRefreshes,
                                 BooleanSupplier currentRequest,
                                 BiPredicate<PlanningSnapshot, PlanResponse> inventoryChanged,
                                 Consumer<PlanningSnapshot> invalidate,
                                 Executor refreshExecutor,
                                 BiConsumer<PlanResponse, CraftPlanResultDispatcher.Sink> restart) {
        this.destination = destination;
        this.maximumRefreshes = Math.max(0, maximumRefreshes);
        this.currentRequest = currentRequest;
        this.inventoryChanged = inventoryChanged;
        this.invalidate = invalidate;
        this.refreshExecutor = refreshExecutor;
        this.restart = restart;
    }

    @Override
    public void success(PlanResponse plan, PlanningSnapshot snapshot) {
        if (finished || refreshPending) return;
        if (!currentRequest.getAsBoolean()) {
            cancelled(Component.translatable("rsi.plan.failure.planning_cancelled"));
            return;
        }
        if (plan.graph() != null && PreviewInventorySyncManager.enabled()) {
            // 图中的初始供料在打开窗口后持续投影。数量变化不再重新启动整次搜索。
            finished = true;
            destination.success(plan, snapshot);
            return;
        }
        boolean changed = inventoryChanged.test(snapshot, plan);
        if (changed && refreshes < maximumRefreshes) {
            refreshes++;
            refreshPending = true;
            // 入服务端队列，避免同步缓存命中形成递归；实际启动前再次检查请求是否已被替代。
            refreshExecutor.execute(() -> {
                refreshPending = false;
                if (finished) return;
                if (!currentRequest.getAsBoolean()) {
                    cancelled(Component.translatable("rsi.plan.failure.planning_cancelled"));
                    return;
                }
                try {
                    invalidate.accept(snapshot);
                    restart.accept(plan, this);
                } catch (RuntimeException failure) {
                    // 延后任务已离开原响应回调的异常边界，仍须给客户端发送终态。
                    LOGGER.error("[RSI-plan] Inventory refresh failed for {}", snapshot.recipeId(), failure);
                    error(PlanOutcome.RUNTIME_UNAVAILABLE,
                            IntegrationDiagnostic.error(IntegrationDiagnosticCode.INTERNAL_ERROR));
                }
            });
            return;
        }
        finished = true;
        destination.success(changed ? plan.withInventoryChangeNotice() : plan, snapshot);
    }

    @Override
    public void error(PlanOutcome outcome, IntegrationDiagnostic diagnostic) {
        if (finished) return;
        finished = true;
        destination.error(outcome, diagnostic);
    }

    @Override
    public void cancelled(Component message) {
        if (finished) return;
        finished = true;
        destination.cancelled(message);
    }

    @Override
    public void notice(Component message) {
        if (!finished) destination.notice(message);
    }
}
