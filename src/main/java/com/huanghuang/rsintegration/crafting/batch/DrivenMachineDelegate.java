package com.huanghuang.rsintegration.crafting.batch;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** RSI 驱动的原生多阶段机器：推进一次后保存结束证据，重复观察不能再触发原生动作。 */
public abstract class DrivenMachineDelegate extends AbstractBatchDelegate {
    private boolean finished;

    @Override public final void advance(ServerLevel current) {
        if (phase != CraftPhase.WORKING || finished) return;
        var level = resolveMachineLevel(current);
        var pos = getMachinePos();
        if (level == null || pos == null || !level.hasChunkAt(pos)) return;
        var machine = level.getBlockEntity(pos);
        if (machine != null && !machine.isRemoved()) finished = advanceNativeCycle(level, machine);
    }

    protected abstract boolean advanceNativeCycle(ServerLevel level, BlockEntity machine);

    @Override protected final boolean isMachineCraftFinished(ServerLevel level, BlockEntity machine) {
        return finished;
    }
}
