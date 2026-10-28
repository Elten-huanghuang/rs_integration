package com.huanghuang.rsintegration.crafting.plan;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 计划阶段估算出的并行分派快照，用于递归树展示。
 *
 * <p>真实执行时仍由 {@code ParallelCraftGroup} 重新校验机器/材料；
 * 这里只展示“准备分给哪些机器、每台多少操作、大概多少燃料/催化剂”。</p>
 */
public record ParallelPlan(
        List<WorkerShare> workers,
        int totalOperations,
        long fuelItems,
        long catalystItems,
        long durabilityItems,
        @Nullable String limitedBy
) {
    public ParallelPlan {
        workers = List.copyOf(workers);
    }

    /** 兼容旧调用：没有可复用耐久工具/催化剂拆分。 */
    public ParallelPlan(List<WorkerShare> workers, int totalOperations,
                        long fuelItems, long catalystItems, @Nullable String limitedBy) {
        this(workers, totalOperations, fuelItems, catalystItems, 0L, limitedBy);
    }

    public int workerCount() {
        return workers.size();
    }

    /** 每台 worker 启动前需要预留一次的复用材料总数（催化剂 + 耐久工具）。 */
    public long reusableItems() {
        return catalystItems + durabilityItems;
    }

    public String shareSummary() {
        StringBuilder sb = new StringBuilder();
        for (WorkerShare worker : workers) {
            if (sb.length() > 0) sb.append('+');
            sb.append(worker.operations());
        }
        return sb.toString();
    }

    /** 单台物理机器的分派份额；坐标用于和机器绑定/调试日志对照。 */
    public record WorkerShare(
            BlockPos machinePos,
            String machineType,
            int operations,
            long fuelItems,
            long catalystItems,
            long durabilityItems
    ) {
        /** 兼容旧调用：没有耐久工具。 */
        public WorkerShare(BlockPos machinePos, String machineType, int operations,
                           long fuelItems, long catalystItems) {
            this(machinePos, machineType, operations, fuelItems, catalystItems, 0L);
        }

        public long reusableItems() {
            return catalystItems + durabilityItems;
        }
    }
}
