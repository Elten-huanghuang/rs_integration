package com.huanghuang.rsintegration.mods.youkaishomecoming.cooking;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;

/**
 * 幻想乡乐事烹饪锅的批量契约参数与槽位探测。
 * <p>
 * 三台锅（小铁锅／矮锅／大汤锅）共用同一份 {@link CookingPotBatchDelegate} 实现，
 * 只在配方耗时与可接受的并行份数上不同。这些参数集中在这里，既方便统一调参，
 * 也让"本机物理容量"的推导逻辑可以脱离 BlockEntity 单独做单元测试。
 */
public final class CookingPotBatchConfig {

    /** 小铁锅：配方最短，最适合铺满批量。 */
    public static final int SMALL = 0;
    /** 矮锅：中等耗时。 */
    public static final int SHORT = 1;
    /** 大汤锅：耗时最长，限制批次以免一次性占满过久。 */
    public static final int LARGE = 2;

    /** 每份配方的基准耗时（tick）。 */
    private static final int[] BASE_PROCESS_TIME = {100, 120, 160};
    /** 单台机器一次预载的最大并行份数，实际还会被配方耗时与槽位容量进一步压低。 */
    private static final int[] MAX_PARALLEL_OPERATIONS = {32, 24, 16};

    private CookingPotBatchConfig() {}

    public static boolean inputBufferEnabled() {
        return RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING_COOKING_POT_INPUT_BUFFER.get();
    }

    public static int inputBufferLimit() {
        return Math.max(1, RSIntegrationConfig.YOUKAISHOMECOMING_COOKING_POT_INPUT_BUFFER_LIMIT.get());
    }

    public static int baseProcessTime(int potIndex) {
        return BASE_PROCESS_TIME[clamp(potIndex)];
    }

    public static int maxParallelOperations(int potIndex) {
        return MAX_PARALLEL_OPERATIONS[clamp(potIndex)];
    }

    /**
     * 按配方实际耗时反推可并行份数。
     * <p>
     * 单台机器的物理槽位虽然能塞下 64 份，但若一次全填满，最后一份的完成时间会
     * 是首份的 N 倍，反而拖慢整批结算。因此按"等效基础耗时"折算：
     * 总耗时固定为一个基准窗口，份数越多单份越快（最多为基准），
     * 从而在批量收益和结算延迟之间取得平衡。
     *
     * @param potIndex    锅类型下标
     * @param processTime 配方耗时（tick），&lt;=0 时回退到该锅的基准耗时
     * @param limit       配置的操作上限
     * @return 至少为 1
     */
    public static int parallelOperationLimit(int potIndex, int processTime, int limit) {
        int base = baseProcessTime(potIndex);
        int resolved = processTime > 0 ? processTime : base;
        int budget = Math.max(1, limit) * base;
        int byTime = Math.max(1, budget / resolved);
        return Math.min(Math.min(Math.max(1, limit), byTime), maxParallelOperations(potIndex));
    }

    /**
     * 给定配方耗时与槽位容量，本机一次能预载几份。
     *
     * @param potIndex      锅类型下标
     * @param processTime   配方耗时（tick）
     * @param limit         配置的操作上限
     * @param slotCapacity  单个原料槽可容纳的数量
     * @param outputPerOp   每份产物数量
     * @param outputCapacity 输出槽可容纳的数量
     * @return 0 表示不具备批量条件
     */
    public static int bufferedOperationCapacity(int potIndex, int processTime, int limit,
                                                int slotCapacity, int outputPerOp,
                                                int outputCapacity) {
        if (slotCapacity <= 0 || outputPerOp <= 0 || outputCapacity <= 0) return 0;
        int operations = parallelOperationLimit(potIndex, processTime, limit);
        operations = Math.min(operations, slotCapacity);
        operations = Math.min(operations, outputCapacity / outputPerOp);
        return Math.max(0, operations);
    }

    /** 单槽容量：取槽位上限与物品堆叠上限的较小值，至少为 1。 */
    public static int effectiveSlotCapacity(int slotLimit, int maxStackSize) {
        return Math.max(1, Math.min(slotLimit, maxStackSize));
    }

    private static int clamp(int potIndex) {
        return Math.max(SMALL, Math.min(LARGE, potIndex));
    }
}
