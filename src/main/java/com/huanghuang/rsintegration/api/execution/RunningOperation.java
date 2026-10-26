package com.huanghuang.rsintegration.api.execution;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

/**
 * 一个已经启动的自定义操作。
 *
 * <p>所有回调均在服务端主线程调用。实现必须只观察真实机器状态，并只报告实际出现的
 * 输出。不要在 {@link #snapshot()} 中保存 Level、Player、BlockEntity、capability
 * 或回调对象。</p>
 */
public interface RunningOperation {
    /**
     * 读取当前机器阶段、进度和活动 recipe。
     */
    CraftObservation observe(ServerLevel level);

    /**
     * 收集上次调用之后真实新增的输出，并处置已预留材料。
     */
    OperationCollection collect(ServerPlayer player);

    /**
     * 保存可跨服务端重启恢复的 primitive、ID 或 NBT 状态。
     */
    OperationSnapshot snapshot();

    /**
     * 处理失败清理。无法证明已回收的材料必须报告为 UNCERTAIN。
     */
    OperationCleanupResult onFailure(
            FailureReason reason,
            @Nullable ServerPlayer player
    );

    /**
     * 处理取消清理。无法证明已回收的材料必须报告为 UNCERTAIN。
     */
    OperationCleanupResult onCancel(
            CancelReason reason,
            @Nullable ServerPlayer player
    );

    /**
     * 在结算完成后执行最后清理。该回调不得补发产物或再次扣减材料。
     */
    void onSettled(@Nullable ServerPlayer player);
}
