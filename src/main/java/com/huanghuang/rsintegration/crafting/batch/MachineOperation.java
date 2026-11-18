package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.crafting.TaskOutputReception;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import javax.annotation.Nullable;

/** 一次机器操作，只负责投料、物理推进、状态及清理。 */
public interface MachineOperation {
    default void bindProduction(TaskOutputReception.Writer writer) {}
    boolean start(OperationStartContext context);
    default void advance(ServerLevel level) {}
    CraftObservation observe(ServerLevel level);
    void recover(@Nullable ServerPlayer player, String reason);
    void cleanup(@Nullable ServerPlayer player);

    enum CraftPhase { WAITING_FOR_START, WORKING, DONE, FAILED }

    /** 只有实际完成工作量的增长才能重置无进展超时。 */
    record CraftObservation(CraftPhase phase, String detail, long completedWork) {
        public CraftObservation {
            if (completedWork < 0) throw new IllegalArgumentException("completedWork must be non-negative");
        }
        public CraftObservation(CraftPhase phase, String detail) { this(phase, detail, 0); }
        public CraftObservation(CraftPhase phase) { this(phase, ""); }
        public CraftObservation withCompletedWork(long work) { return new CraftObservation(phase, detail, work); }
    }
}
