package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.plan.PlanBackgroundClient;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 客户端独立预览任务；选中任务只改变显示，不取消其他计算。 */
@OnlyIn(Dist.CLIENT)
public final class PlanningProgressTracker {
    static final long SUCCESS_VISIBLE_MS = 400L;
    static final long TERMINAL_VISIBLE_MS = 3_000L;
    static final long FINALIZING_VISIBLE_MS = 8_000L;
    private static final long LOCAL_TIMEOUT_MS = 60_000L;
    private static final Map<Long, Task> TASKS = new LinkedHashMap<>();
    private static long selectedRequestId;
    private static long newestRequestId;

    private PlanningProgressTracker() {}

    public static void start(long requestId, ResourceLocation recipeId) {
        if (requestId <= 0L || recipeId == null) return;
        TASKS.put(requestId, new Task(new PlanningProgressSnapshot(requestId, 0L, recipeId,
                PlanningProgressSnapshot.State.ACCEPTED,
                PlanningProgressSnapshot.Phase.ACCEPTING, 0L, Component.empty())));
        newestRequestId = Math.max(newestRequestId, requestId);
        select(requestId);
    }

    public static void select(long requestId) {
        if (!TASKS.containsKey(requestId)) return;
        if (selectedRequestId != requestId && TASKS.containsKey(selectedRequestId)) {
            PlanBackgroundClient.detach(selectedRequestId);
        }
        selectedRequestId = requestId;
        PlanBackgroundClient.attach(requestId);
    }

    public static boolean isSelected(long requestId) { return selectedRequestId == requestId; }

    public static List<PlanningProgressSnapshot> tasks() {
        expire();
        return TASKS.values().stream().map(task -> task.value).toList();
    }

    public static void update(PlanningProgressSnapshot snapshot) {
        if (snapshot == null) return;
        Task task = TASKS.get(snapshot.requestId());
        if (task == null || task.value.state() == PlanningProgressSnapshot.State.CANCELLED
                || task.value.requestGeneration() > snapshot.requestGeneration()
                && snapshot.requestGeneration() > 0L) return;
        long now = System.currentTimeMillis();
        task.lastUpdateAt = now;
        if (task.value.state() != snapshot.state() || task.value.phase() != snapshot.phase()) {
            task.stateStartedAt = now;
        }
        task.value = snapshot;
        if (snapshot.terminal()) task.terminalSince = now;
    }

    @Nullable
    public static PlanningProgressSnapshot current() {
        return get(selectedRequestId);
    }

    /** 页面始终查询自己的请求，不能随着 HUD 切换到别的任务。 */
    @Nullable
    public static PlanningProgressSnapshot get(long requestId) {
        expire();
        Task task = TASKS.get(requestId);
        return task == null ? null : task.value;
    }

    public static long elapsedMillis() {
        Task task = TASKS.get(selectedRequestId);
        if (task == null) return 0L;
        return task.value.terminal() ? task.value.elapsedMillis()
                : Math.max(task.value.elapsedMillis(), System.currentTimeMillis() - task.startedAt);
    }

    public static void clear() {
        TASKS.clear();
        selectedRequestId = 0L;
        newestRequestId = 0L;
    }

    public static boolean acceptsResponse(long requestId) {
        // 旧入口仍可接收新请求；已知的取消/完成请求不能复活。
        Task task = TASKS.get(requestId);
        return requestId <= 0L || (task != null
                ? task.value.state() != PlanningProgressSnapshot.State.CANCELLED
                : requestId > newestRequestId);
    }

    public static long cancelCurrent() { return cancel(selectedRequestId); }

    public static long cancel(long requestId) {
        Task task = TASKS.get(requestId);
        if (task == null || task.value.terminal()) return 0L;
        long now = System.currentTimeMillis();
        PlanningProgressSnapshot previous = task.value;
        // 短暂保留取消终态，供原页面解除 pending；迟到的进度和结果都不能复活任务。
        task.value = new PlanningProgressSnapshot(requestId, previous.requestGeneration(),
                previous.recipeId(), PlanningProgressSnapshot.State.CANCELLED, previous.phase(),
                Math.max(previous.elapsedMillis(), now - task.startedAt), Component.empty());
        task.terminalSince = now;
        PlanBackgroundClient.attach(requestId);
        return requestId;
    }

    public static void responseReceived(long requestId) { TASKS.remove(requestId); }

    private static void expire() {
        long now = System.currentTimeMillis();
        var iterator = TASKS.values().iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next();
            PlanningProgressSnapshot current = task.value;
            // 成功状态可能早于分页结果到达；不能在 400ms 后拒收完整结果。
            if (current.terminal() && current.state() != PlanningProgressSnapshot.State.SUCCEEDED
                    && task.terminalSince > 0L && now - task.terminalSince >= terminalVisibleMillis(current.state())) {
                iterator.remove();
            } else if (!current.terminal() && (finalizingTimedOut(current.state(), task.stateStartedAt, now)
                    || heartbeatTimedOut(task.lastUpdateAt, now))) {
                task.value = new PlanningProgressSnapshot(current.requestId(), current.requestGeneration(),
                        current.recipeId(), PlanningProgressSnapshot.State.TIMED_OUT, current.phase(),
                        now - task.startedAt, Component.translatable("rsi.planning.detail.no_update"));
                task.terminalSince = now;
            } else if (current.state() == PlanningProgressSnapshot.State.SUCCEEDED
                    && heartbeatTimedOut(task.lastUpdateAt, now)) {
                iterator.remove();
            }
        }
    }

    static long terminalVisibleMillis(PlanningProgressSnapshot.State state) {
        return state == PlanningProgressSnapshot.State.SUCCEEDED ? SUCCESS_VISIBLE_MS : TERMINAL_VISIBLE_MS;
    }

    static boolean finalizingTimedOut(PlanningProgressSnapshot.State state, long startedAt, long now) {
        return state == PlanningProgressSnapshot.State.FINALIZING && startedAt > 0L
                && now - startedAt >= FINALIZING_VISIBLE_MS;
    }

    static boolean heartbeatTimedOut(long updatedAt, long now) {
        return updatedAt > 0L && now - updatedAt >= LOCAL_TIMEOUT_MS;
    }

    private static final class Task {
        private PlanningProgressSnapshot value;
        private final long startedAt = System.currentTimeMillis();
        private long stateStartedAt = startedAt;
        private long lastUpdateAt = startedAt;
        private long terminalSince;
        private Task(PlanningProgressSnapshot value) { this.value = value; }
    }
}
