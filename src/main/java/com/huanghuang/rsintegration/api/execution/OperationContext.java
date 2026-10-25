package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.machine.MachineContext;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record OperationContext(
        ServerPlayer player,
        UUID transactionId,
        UUID operationId,
        int attempt,
        ReservedMaterialView materials,
        MachineContext machine,
        long currentTick
) {
    public OperationContext {
        if (player == null || transactionId == null || operationId == null
                || attempt < 1 || materials == null || machine == null
                || currentTick < 0L) {
            throw new IllegalArgumentException("operation context is incomplete");
        }
    }
}
