package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /** 只合并预览快照；执行端仍保留原节点和机器分派。 */
    @Nullable
    public static ParallelPlan mergeForDisplay(List<ParallelPlan> plans) {
        if (plans.isEmpty() || plans.stream().anyMatch(plan -> plan == null)) return null;
        Map<WorkerKey, WorkerShare> workers = new LinkedHashMap<>();
        long total = 0L;
        long fuel = 0L;
        String limitedBy = null;
        for (ParallelPlan plan : plans) {
            total += plan.totalOperations();
            if (total > Integer.MAX_VALUE) return null;
            fuel = SaturatingLongMath.add(fuel, plan.fuelItems());
            if (limitedBy == null) limitedBy = plan.limitedBy();
            for (WorkerShare worker : plan.workers()) {
                WorkerKey key = new WorkerKey(worker.machinePos(), worker.machineType());
                workers.merge(key, worker, (left, right) -> new WorkerShare(left.machinePos(),
                        left.machineType(), Math.addExact(left.operations(), right.operations()),
                        SaturatingLongMath.add(left.fuelItems(), right.fuelItems()),
                        Math.max(left.catalystItems(), right.catalystItems()),
                        Math.max(left.durabilityItems(), right.durabilityItems())));
            }
        }
        long catalysts = 0L;
        long durability = 0L;
        for (WorkerShare worker : workers.values()) {
            catalysts = SaturatingLongMath.add(catalysts, worker.catalystItems());
            durability = SaturatingLongMath.add(durability, worker.durabilityItems());
        }
        return new ParallelPlan(List.copyOf(workers.values()), (int) total, fuel,
                catalysts, durability, limitedBy);
    }

    private record WorkerKey(BlockPos pos, String type) {}

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
