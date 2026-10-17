package com.huanghuang.rsintegration.mods.youkaishomecoming.steamer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 幻想乡乐事蒸锅批量参数的纯逻辑单测。
 * <p>
 * 只覆盖 {@link SteamerBatchConfig} 里与 BlockEntity、ItemStack 无关的折算规则，
 * 这样它可以脱离 Forge 运行时单独跑，不会受其它正在开发的模块影响。
 */
class SteamerBatchConfigTest {

    @Test
    @DisplayName("蒸锅本体自带的层数计入总格数")
    void basePotLayerCountsTowardSlots() {
        // 本体 1 层 + 0 层蒸笼 = 1 层 * 4 格。
        assertEquals(4, SteamerBatchConfig.slotCapacity(0));
        // 本体 1 层 + 1 层蒸笼 = 2 层 * 4 格。
        assertEquals(8, SteamerBatchConfig.slotCapacity(1));
        assertEquals(SteamerBatchConfig.SLOTS_PER_RACK * 2, SteamerBatchConfig.slotCapacity(1));
    }

    @Test
    @DisplayName("层数超过原生上限时按上限封顶，负数按零层处理")
    void rackLayersAreClamped() {
        int ceiling = SteamerBatchConfig.MAX_RACKS * SteamerBatchConfig.SLOTS_PER_RACK;
        assertEquals(ceiling, SteamerBatchConfig.slotCapacity(SteamerBatchConfig.MAX_RACKS));
        assertEquals(ceiling, SteamerBatchConfig.slotCapacity(1000));
        // 层数非法时退化为本体那一层，而不是抛异常或返回 0。
        assertEquals(SteamerBatchConfig.SLOTS_PER_RACK, SteamerBatchConfig.slotCapacity(-3));
    }

    @Test
    @DisplayName("耗时等于基准时并行份数取配置上限")
    void parallelLimitReachesConfiguredCeilingAtBaselineTime() {
        assertEquals(64, SteamerBatchConfig.parallelOperationLimit(SteamerBatchConfig.BASE_STEAM_TIME, 64));
        // 配置上限本身也会被尊重。
        assertEquals(7, SteamerBatchConfig.parallelOperationLimit(SteamerBatchConfig.BASE_STEAM_TIME, 7));
    }

    @Test
    @DisplayName("耗时越长可并行份数越少，且始终不少于 1 份")
    void parallelLimitShrinksWithProcessTime() {
        // 基准预算 64 * 100 = 6400：耗时 200 时 32 份，耗时 800 时 8 份。
        assertEquals(32, SteamerBatchConfig.parallelOperationLimit(200, 64));
        assertEquals(8, SteamerBatchConfig.parallelOperationLimit(800, 64));
        // 极长耗时仍然至少保留 1 份，否则整条链会卡死。
        assertEquals(1, SteamerBatchConfig.parallelOperationLimit(Integer.MAX_VALUE, 64));
    }

    @Test
    @DisplayName("耗时非正时回退到基准耗时")
    void nonPositiveProcessTimeFallsBackToBaseline() {
        int zero = SteamerBatchConfig.parallelOperationLimit(0, 64);
        int negative = SteamerBatchConfig.parallelOperationLimit(-30, 64);
        assertEquals(SteamerBatchConfig.parallelOperationLimit(SteamerBatchConfig.BASE_STEAM_TIME, 64), zero);
        assertEquals(zero, negative);
    }

    @Test
    @DisplayName("配置上限为零或负数时仍按单份处理")
    void nonPositiveLimitDegradesToOneOperation() {
        assertEquals(1, SteamerBatchConfig.parallelOperationLimit(SteamerBatchConfig.BASE_STEAM_TIME, 0));
        assertEquals(1, SteamerBatchConfig.parallelOperationLimit(SteamerBatchConfig.BASE_STEAM_TIME, -9));
    }

    @Test
    @DisplayName("批量份数同时受蒸笼格数与耗时折算约束，取更小的一条")
    void bufferedCapacityIsBoundedBySlotsAndTime() {
        // 只有本体一层（4 格）：耗时折算 64 份也没用，上限是 4 份。
        assertEquals(4, SteamerBatchConfig.bufferedOperationCapacity(0,
                SteamerBatchConfig.BASE_STEAM_TIME, 64));
        // 满层 20 格，耗时折算 64 份 -> 取 20。
        assertEquals(20, SteamerBatchConfig.bufferedOperationCapacity(4,
                SteamerBatchConfig.BASE_STEAM_TIME, 64));
        // 满层 20 格但耗时很长，折算只剩 4 份 -> 取 4。
        assertEquals(4, SteamerBatchConfig.bufferedOperationCapacity(4, 1600, 64));
    }

    @Test
    @DisplayName("单格有效容量取堆叠上限，且至少为 1")
    void effectiveSlotCapacityClampsToAtLeastOne() {
        assertEquals(64, SteamerBatchConfig.effectiveSlotCapacity(64));
        assertEquals(16, SteamerBatchConfig.effectiveSlotCapacity(16));
        assertEquals(1, SteamerBatchConfig.effectiveSlotCapacity(1));
        // 非法堆叠上限也要保证 1，否则会算出 0 份而永久等待。
        assertEquals(1, SteamerBatchConfig.effectiveSlotCapacity(0));
        assertEquals(1, SteamerBatchConfig.effectiveSlotCapacity(-5));
    }

    @Test
    @DisplayName("整座蒸锅的格数不超过原生 MAX_STACK 层")
    void totalSlotsRespectNativeStackLimit() {
        assertTrue(SteamerBatchConfig.MAX_RACKS >= SteamerBatchConfig.POT_BASE_RACKS);
        assertEquals(SteamerBatchConfig.MAX_RACKS, 5);
    }
}
