package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/** 递归步骤必须实际生产的材料；数量不是尝试次数。 */
public record ProductionTarget(MaterialKey material, long quantity) {
    public ProductionTarget {
        Objects.requireNonNull(material, "material");
        if (quantity <= 0) throw new IllegalArgumentException("production quantity must be positive");
    }

    public static ProductionTarget of(ItemStack stack, long quantity) {
        return new ProductionTarget(MaterialKey.of(stack), quantity);
    }
}
