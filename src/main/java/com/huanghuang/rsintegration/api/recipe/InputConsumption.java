package com.huanghuang.rsintegration.api.recipe;

import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

public record InputConsumption(
        Kind kind,
        int damagePerUse,
        long fuelBurnTicksPerOperation,
        @Nullable ResourceLocation linkedOutputPortId
) {
    public enum Kind {
        CONSUMED,
        FUEL,
        CATALYST,
        TRANSFORMED,
        CONTAINER_RETURNED,
        DURABILITY
    }

    public InputConsumption {
        if (kind == null) throw new IllegalArgumentException("kind is required");
        if (damagePerUse < 0 || fuelBurnTicksPerOperation < 0L) {
            throw new IllegalArgumentException("consumption values must be non-negative");
        }
        switch (kind) {
            case DURABILITY -> {
                if (damagePerUse <= 0 || fuelBurnTicksPerOperation != 0L
                        || linkedOutputPortId != null) {
                    throw new IllegalArgumentException("invalid durability consumption");
                }
            }
            case FUEL -> {
                if (fuelBurnTicksPerOperation <= 0L || damagePerUse != 0) {
                    throw new IllegalArgumentException("fuel requires positive burn ticks");
                }
            }
            case TRANSFORMED, CONTAINER_RETURNED -> {
                if (linkedOutputPortId == null
                        || damagePerUse != 0
                        || fuelBurnTicksPerOperation != 0L) {
                    throw new IllegalArgumentException(
                            kind + " requires a linked output port");
                }
            }
            case CONSUMED, CATALYST -> {
                if (damagePerUse != 0 || fuelBurnTicksPerOperation != 0L
                        || linkedOutputPortId != null) {
                    throw new IllegalArgumentException(
                            kind + " does not accept extra consumption values");
                }
            }
        }
    }

    public static InputConsumption consumed() {
        return new InputConsumption(Kind.CONSUMED, 0, 0L, null);
    }

    public static InputConsumption fuel(long burnTicksPerOperation) {
        return new InputConsumption(Kind.FUEL, 0, burnTicksPerOperation, null);
    }

    public static InputConsumption catalyst() {
        return new InputConsumption(Kind.CATALYST, 0, 0L, null);
    }

    public static InputConsumption transformed(ResourceLocation outputPortId) {
        return new InputConsumption(Kind.TRANSFORMED, 0, 0L, outputPortId);
    }

    public static InputConsumption containerReturned(ResourceLocation outputPortId) {
        return new InputConsumption(
                Kind.CONTAINER_RETURNED, 0, 0L, outputPortId);
    }

    public static InputConsumption durability(int damagePerUse) {
        return new InputConsumption(Kind.DURABILITY, damagePerUse, 0L, null);
    }

    public static InputConsumption fuelWithReturnedContainer(
            long burnTicksPerOperation, ResourceLocation outputPortId) {
        return new InputConsumption(
                Kind.FUEL, 0, burnTicksPerOperation, outputPortId);
    }

    /**
     * 该输入是否按“每台并行 worker 预留一份”处理。
     *
     * <p>催化剂和耐久工具不会被单次操作消耗掉整份库存：并行执行时每台机器
     * 各需要一份，因此可并行机器数受库存数量限制。燃料不是复用材料，它按
     * 每个 worker 的实际操作数分别计算燃烧时间。</p>
     */
    public boolean reusableWithinWorkerBatch() {
        return kind == Kind.CATALYST || kind == Kind.DURABILITY;
    }
}
