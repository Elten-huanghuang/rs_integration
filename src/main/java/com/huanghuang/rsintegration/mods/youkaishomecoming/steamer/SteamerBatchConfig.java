package com.huanghuang.rsintegration.mods.youkaishomecoming.steamer;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;

/**
 * 幻想乡乐事蒸锅的批量契约参数与容量折算。
 * <p>
 * 蒸锅与同模块的三台烹饪锅机制不同：一台蒸锅由"多层蒸笼 × 每层四格"构成，
 * 每个格子各自持有食材、各自累计蒸制进度。因此"一次发配 N 份"落在蒸锅上
 * 就是"一次占用 N 个蒸笼格"，而不是把 N 份堆进同一个格子。
 * <p>
 * 这里集中放置与 BlockEntity 无关的换算规则，便于脱离 Forge 运行时做单元测试。
 */
public final class SteamerBatchConfig {

    /** 每个蒸笼层的格子数（RackData 构造时创建长度为 4 的 RackItemData 数组）。 */
    public static final int SLOTS_PER_RACK = 4;

    /** 蒸锅本体自带的基础层数（RackInfo：pot 为真时层数再加 1）。 */
    public static final int POT_BASE_RACKS = 1;

    /** 整座蒸锅允许的最大层数（SteamerBlockEntity 的原生上限 MAX_STACK）。 */
    public static final int MAX_RACKS = 5;

    /** 每份蒸制配方的基准耗时（tick），用于把批次折算成有限的占机时间。 */
    public static final int BASE_STEAM_TIME = 100;

    private SteamerBatchConfig() {}

    public static boolean inputBufferEnabled() {
        return RSIntegrationConfig.ENABLE_YOUKAISHOMECOMING_STEAMER_INPUT_BUFFER.get();
    }

    public static int inputBufferLimit() {
        return Math.max(1, RSIntegrationConfig.YOUKAISHOMECOMING_STEAMER_INPUT_BUFFER_LIMIT.get());
    }

    /**
     * 给定"蒸锅本体 + 若干层蒸笼"，蒸锅一共能放几份食材。
     *
     * @param rackLayers 蒸笼层数（不含蒸锅本体自带的那一层），负数按 0 处理
     * @return 至少为一个蒸笼层的格数
     */
    public static int slotCapacity(int rackLayers) {
        int layers = POT_BASE_RACKS + Math.max(0, rackLayers);
        return Math.min(MAX_RACKS, layers) * SLOTS_PER_RACK;
    }

    /**
     * 按单份蒸制耗时反推可预载份数。
     * <p>
     * 格子够多不等于应该一次填满：格子全部占满时最后一份的完成时间是首份的 N 倍，
     * 整批结算被最慢的那份拖住。因此以"等效一个基准窗口"折算份数，
     * 与烹饪锅 {@code CookingPotBatchConfig.parallelOperationLimit} 的口径一致。
     *
     * @param processTime 单份蒸制耗时（tick），&lt;=0 时回退到基准耗时
     * @param limit       配置的操作上限
     * @return 至少为 1
     */
    public static int parallelOperationLimit(int processTime, int limit) {
        int resolved = processTime > 0 ? processTime : BASE_STEAM_TIME;
        long budget = (long) Math.max(1, limit) * BASE_STEAM_TIME;
        return (int) Math.max(1, Math.min(Math.max(1, limit), budget / resolved));
    }

    /**
     * 本机一次能预载几份。
     *
     * @param rackLayers  蒸笼层数（不含蒸锅本体）
     * @param processTime 单份蒸制耗时（tick）
     * @param limit       配置的操作上限
     * @return 0 表示当前结构放不下任何一份
     */
    public static int bufferedOperationCapacity(int rackLayers, int processTime, int limit) {
        int slots = slotCapacity(rackLayers);
        if (slots <= 0) return 0;
        return Math.min(slots, parallelOperationLimit(processTime, limit));
    }

    /**
     * 单格有效容量：取原生单格上限与物品堆叠上限的较小值，至少为 1。
     * <p>
     * 蒸笼批量路径按“一格一份”铺料；该值用于规划器约束单份物品的堆叠上限。
     */
    public static int effectiveSlotCapacity(int maxStackSize) {
        return Math.max(1, Math.min(64, maxStackSize));
    }
}
