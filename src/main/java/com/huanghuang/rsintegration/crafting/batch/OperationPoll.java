package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.server.level.ServerLevel;

/** 单次执行轮询：先接管可取产物，再推进机器并读取本轮结果。 */
public final class OperationPoll {
    private OperationPoll() {}

    public static IBatchDelegate.CraftObservation poll(IBatchDelegate delegate, ServerLevel level) {
        delegate.transferOutputs(level);
        return advanceAndObserve(delegate, level);
    }

    /** 并行组按预算完成整轮搬运后，从工作单元游标继续此步骤。 */
    public static IBatchDelegate.CraftObservation advanceAndObserve(IBatchDelegate delegate, ServerLevel level) {
        delegate.advanceCraft(level);
        return delegate.observeCraft(level);
    }
}
