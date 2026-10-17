package com.huanghuang.rsintegration.mods.youkaishomecoming.cooking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 幻想乡乐事烹饪锅批量参数的纯逻辑单测。
 * <p>
 * 只覆盖 {@link CookingPotBatchConfig} 里与 BlockEntity、ItemStack 无关的折算规则，
 * 这样它可以脱离 Forge 运行时单独跑，不会受其它正在开发的模块影响。
 */
class CookingPotBatchConfigTest {

    private static final int SMALL = CookingPotBatchConfig.SMALL;
    private static final int SHORT = CookingPotBatchConfig.SHORT;
    private static final int LARGE = CookingPotBatchConfig.LARGE;

    @Test
    @DisplayName("基准耗时与并行上限按锅型返回，越界下标夹紧到两端")
    void potParametersAreClamped() {
        assertEquals(100, CookingPotBatchConfig.baseProcessTime(SMALL));
        assertEquals(120, CookingPotBatchConfig.baseProcessTime(SHORT));
        assertEquals(160, CookingPotBatchConfig.baseProcessTime(LARGE));

        assertEquals(32, CookingPotBatchConfig.maxParallelOperations(SMALL));
        assertEquals(24, CookingPotBatchConfig.maxParallelOperations(SHORT));
        assertEquals(16, CookingPotBatchConfig.maxParallelOperations(LARGE));

        // 越界下标必须夹紧，不能抛 ArrayIndexOutOfBoundsException。
        assertEquals(CookingPotBatchConfig.baseProcessTime(SMALL),
                CookingPotBatchConfig.baseProcessTime(-7));
        assertEquals(CookingPotBatchConfig.baseProcessTime(LARGE),
                CookingPotBatchConfig.baseProcessTime(99));
        assertEquals(CookingPotBatchConfig.maxParallelOperations(SMALL),
                CookingPotBatchConfig.maxParallelOperations(-1));
        assertEquals(CookingPotBatchConfig.maxParallelOperations(LARGE),
                CookingPotBatchConfig.maxParallelOperations(1 << 20));
    }

    @Test
    @DisplayName("耗时等于基准时并行份数取锅型上限")
    void parallelLimitReachesPotCeilingAtBaselineTime() {
        // 小锅：预算 64 * 100 = 6400，按耗时折算 64 份，再被小锅上限 32 截断。
        assertEquals(32, CookingPotBatchConfig.parallelOperationLimit(SMALL, 100, 64));
        assertEquals(24, CookingPotBatchConfig.parallelOperationLimit(SHORT, 120, 64));
        assertEquals(16, CookingPotBatchConfig.parallelOperationLimit(LARGE, 160, 64));
    }

    @Test
    @DisplayName("耗时越长可并行份数越少，且始终不少于 1 份")
    void parallelLimitShrinksWithProcessTime() {
        // 小锅基准预算 6400：耗时 400 时只能排 16 份。
        assertEquals(16, CookingPotBatchConfig.parallelOperationLimit(SMALL, 400, 64));
        // 耗时 800 -> 8 份。
        assertEquals(8, CookingPotBatchConfig.parallelOperationLimit(SMALL, 800, 64));
        // 极长耗时仍然至少保留 1 份，否则整条链会卡死。
        assertEquals(1, CookingPotBatchConfig.parallelOperationLimit(SMALL, 100_000, 64));
        assertEquals(1, CookingPotBatchConfig.parallelOperationLimit(LARGE, Integer.MAX_VALUE, 64));
    }

    @Test
    @DisplayName("耗时非正时回退到该锅的基准耗时")
    void nonPositiveProcessTimeFallsBackToBaseline() {
        int zero = CookingPotBatchConfig.parallelOperationLimit(SMALL, 0, 64);
        int negative = CookingPotBatchConfig.parallelOperationLimit(SMALL, -20, 64);
        assertEquals(CookingPotBatchConfig.parallelOperationLimit(SMALL, 100, 64), zero);
        assertEquals(zero, negative);
    }

    @Test
    @DisplayName("配置上限为零或负数时仍按单份处理")
    void nonPositiveLimitDegradesToOneOperation() {
        assertEquals(1, CookingPotBatchConfig.parallelOperationLimit(SMALL, 100, 0));
        assertEquals(1, CookingPotBatchConfig.parallelOperationLimit(SMALL, 100, -5));
        // 上限小于基准折算值时以上限为准。
        assertEquals(3, CookingPotBatchConfig.parallelOperationLimit(SMALL, 100, 3));
    }

    @Test
    @DisplayName("槽位容量非正时直接判定为不具备批量条件")
    void bufferedCapacityRejectsInvalidInputs() {
        assertEquals(0, CookingPotBatchConfig.bufferedOperationCapacity(SMALL, 100, 64, 0, 1, 64));
        assertEquals(0, CookingPotBatchConfig.bufferedOperationCapacity(SMALL, 100, 64, 64, 0, 64));
        assertEquals(0, CookingPotBatchConfig.bufferedOperationCapacity(SMALL, 100, 64, 64, 1, 0));
        assertEquals(0, CookingPotBatchConfig.bufferedOperationCapacity(SMALL, 100, 64, -1, 1, 64));
    }

    @Test
    @DisplayName("批量份数同时受原料槽容量与输出槽容量约束")
    void bufferedCapacityIsBoundedBySlots() {
        // 不设槽位限制时退化为按耗时折算的上限（小锅 32）。
        assertEquals(32, CookingPotBatchConfig.bufferedOperationCapacity(SMALL, 100, 64,
                Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
        // 原料槽只装得下 5 份。
        assertEquals(5, CookingPotBatchConfig.bufferedOperationCapacity(SMALL, 100, 64,
                5, 1, Integer.MAX_VALUE));
        // 输出槽装得下 8 个产物、每份产出 2 个 -> 最多 4 份。
        assertEquals(4, CookingPotBatchConfig.bufferedOperationCapacity(SMALL, 100, 64,
                Integer.MAX_VALUE, 2, 8));
        // 两条约束同时生效时取更小的一条。
        assertEquals(4, CookingPotBatchConfig.bufferedOperationCapacity(SMALL, 100, 64, 6, 2, 8));
        // 输出槽连一份产物都放不下 -> 0，调用方必须回退到单次路径。
        assertEquals(0, CookingPotBatchConfig.bufferedOperationCapacity(SMALL, 100, 64,
                Integer.MAX_VALUE, 3, 2));
    }

    @Test
    @DisplayName("单槽容量取槽位上限与堆叠上限的较小值，至少为 1")
    void effectiveSlotCapacityClampsToAtLeastOne() {
        assertEquals(1, CookingPotBatchConfig.effectiveSlotCapacity(1, 64));
        assertEquals(16, CookingPotBatchConfig.effectiveSlotCapacity(64, 16));
        assertEquals(64, CookingPotBatchConfig.effectiveSlotCapacity(64, 64));
        // 槽位上限为 0 或负数时也要保证至少 1，否则会算出 0 份而永久等待。
        assertEquals(1, CookingPotBatchConfig.effectiveSlotCapacity(0, 64));
        assertEquals(1, CookingPotBatchConfig.effectiveSlotCapacity(-3, 64));
    }
}
