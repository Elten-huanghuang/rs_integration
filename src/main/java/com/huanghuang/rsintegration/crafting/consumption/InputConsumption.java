package com.huanghuang.rsintegration.crafting.consumption;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/**
 * 单个配方输入槽在一次操作中的统一消费语义。
 *
 * <p>普通消费语义使用配方声明的物品数量；耐久和燃料使用
 * {@code unitsPerOperation} 表示每次操作消耗的耐久点或燃料值。</p>
 */
public record InputConsumption(Kind kind, long unitsPerOperation,
                               @Nullable ResourceLocation linkedOutputPortId) {
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
        if (unitsPerOperation < 0L) {
            throw new IllegalArgumentException("unitsPerOperation must be non-negative");
        }
        boolean capacityLimited = kind == Kind.FUEL || kind == Kind.DURABILITY;
        if (capacityLimited != (unitsPerOperation > 0L)) {
            throw new IllegalArgumentException(
                    "FUEL and DURABILITY require positive units; other kinds require zero");
        }
        boolean supportsLinkedOutput = kind == Kind.FUEL
                || kind == Kind.TRANSFORMED
                || kind == Kind.CONTAINER_RETURNED;
        if (linkedOutputPortId != null && !supportsLinkedOutput) {
            throw new IllegalArgumentException(kind + " does not return an output");
        }
    }

    public InputConsumption(Kind kind, long unitsPerOperation) {
        this(kind, unitsPerOperation, null);
    }

    public static InputConsumption consumed() {
        return new InputConsumption(Kind.CONSUMED, 0L);
    }

    public static InputConsumption fuel() {
        return fuel(1L);
    }

    public static InputConsumption fuel(long burnUnitsPerOperation) {
        return new InputConsumption(Kind.FUEL, burnUnitsPerOperation);
    }

    public static InputConsumption catalyst() {
        return new InputConsumption(Kind.CATALYST, 0L);
    }

    public static InputConsumption transformed() {
        return new InputConsumption(Kind.TRANSFORMED, 0L);
    }

    public static InputConsumption transformed(ResourceLocation linkedOutputPortId) {
        return new InputConsumption(Kind.TRANSFORMED, 0L, linkedOutputPortId);
    }

    public static InputConsumption containerReturned() {
        return new InputConsumption(Kind.CONTAINER_RETURNED, 0L);
    }

    public static InputConsumption containerReturned(ResourceLocation linkedOutputPortId) {
        return new InputConsumption(Kind.CONTAINER_RETURNED, 0L, linkedOutputPortId);
    }

    public static InputConsumption durability(long damagePerUse) {
        return new InputConsumption(Kind.DURABILITY, damagePerUse);
    }

    public DemandRole demandRole() {
        return switch (kind) {
            case CONSUMED -> DemandRole.CONSUMED;
            case FUEL -> DemandRole.FUEL;
            case CATALYST -> DemandRole.CATALYST;
            case TRANSFORMED -> DemandRole.TRANSFORMED;
            case CONTAINER_RETURNED -> DemandRole.CONTAINER_RETURNING;
            case DURABILITY -> DemandRole.DURABILITY;
        };
    }

    /** 同一 worker 的一批操作中可重复使用，不需要按操作次数重复预留。 */
    public boolean reusableWithinWorkerBatch() {
        return kind == Kind.CATALYST || kind == Kind.DURABILITY;
    }

    public boolean requiresLinkedOutput() {
        return kind == Kind.TRANSFORMED || kind == Kind.CONTAINER_RETURNED;
    }

    /**
     * 计算该输入在多次操作下实际需要的物品数量。
     *
     * @param operations 操作次数
     * @param amountPerOperation 普通语义下每次操作的物品数量
     * @param unitsPerItem 单个物品可提供的耐久点或燃料值
     */
    public long requiredPhysicalAmount(long operations, long amountPerOperation,
                                       long unitsPerItem) {
        if (operations < 1L || amountPerOperation < 1L) {
            throw new IllegalArgumentException("invalid input amount");
        }
        if (kind == Kind.CATALYST) return amountPerOperation;
        if (kind == Kind.FUEL || kind == Kind.DURABILITY) {
            if (unitsPerItem < 1L) {
                throw new IllegalArgumentException("unitsPerItem must be positive");
            }
            long requiredUnits = DurabilityMath.multiplySaturating(
                    operations, unitsPerOperation);
            long requiredItems = kind == Kind.FUEL
                    ? FuelMath.requiredItems(requiredUnits, unitsPerItem)
                    : DurabilityMath.ceilDiv(requiredUnits, unitsPerItem);
            return kind == Kind.DURABILITY
                    ? Math.min(operations, requiredItems) : requiredItems;
        }
        return DurabilityMath.multiplySaturating(operations, amountPerOperation);
    }

    public static InputConsumption fromDemandRole(DemandRole role) {
        if (role == null) throw new IllegalArgumentException("role is required");
        return switch (role) {
            case CONSUMED -> consumed();
            case FUEL -> fuel();
            case CATALYST -> catalyst();
            case TRANSFORMED -> transformed();
            case CONTAINER_RETURNING -> containerReturned();
            case DURABILITY -> durability(1L);
        };
    }
}
