package com.huanghuang.rsintegration.api.execution;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import javax.annotation.Nullable;

public interface RunningOperation {
    CraftObservation observe(ServerLevel level);

    OperationCollection collect(ServerPlayer player);

    OperationSnapshot snapshot();

    OperationCleanupResult onFailure(
            FailureReason reason,
            @Nullable ServerPlayer player
    );

    OperationCleanupResult onCancel(
            CancelReason reason,
            @Nullable ServerPlayer player
    );

    void onSettled(@Nullable ServerPlayer player);
}
