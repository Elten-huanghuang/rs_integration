package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.Objects;

/** 递归步骤必须实际生产的材料；数量不是尝试次数。 */
public record ProductionTarget(
        @Nullable MaterialKey material,
        IntegrationResourceKey resource,
        long quantity
) {
    public ProductionTarget {
        Objects.requireNonNull(resource, "resource");
        if (quantity <= 0) throw new IllegalArgumentException("production quantity must be positive");
    }

    public static ProductionTarget of(ItemStack stack, long quantity) {
        Objects.requireNonNull(stack, "stack");
        return new ProductionTarget(
                MaterialKey.of(stack),
                IntegrationResourceKey.item(stack),
                quantity
        );
    }

    public static ProductionTarget of(
            IntegrationResourceKey resource,
            long quantity
    ) {
        Objects.requireNonNull(resource, "resource");
        return new ProductionTarget(
                resource.kind()
                        == IntegrationResourceKey.ResourceKind.ITEM
                        ? itemMaterial(resource)
                        : null,
                resource,
                quantity
        );
    }

    boolean matchesItem(ItemStack stack) {
        MaterialKey expected = material;
        return expected != null
                && MaterialMatcher.matchesOutputDeclaration(expected, stack);
    }

    boolean matchesFluid(CollectedOutput output) {
        return resource.kind()
                == IntegrationResourceKey.ResourceKind.FLUID
                && output.resource().kind()
                == IntegrationResourceKey.ResourceKind.FLUID
                && resource.id().equals(output.resource().id())
                && (resource.identity().isEmpty()
                || resource.identity().equals(
                output.resource().identity()));
    }

    /** 仅用于进度、失败提示和内部循环身份，不代表真实物品产物。 */
    public ItemStack displayStack() {
        if (material != null) return material.toStack(1);
        return InkFluidSupport.tokenFor(resource, 1);
    }

    private static MaterialKey itemMaterial(
            IntegrationResourceKey resource
    ) {
        ItemStack stack = new ItemStack(
                BuiltInRegistries.ITEM.get(resource.id()));
        if (stack.isEmpty()) {
            throw new IllegalArgumentException(
                    "unknown item target: " + resource.id());
        }
        stack.setCount(1);
        if (!resource.identity().isEmpty()) {
            stack.setTag(resource.identity());
        }
        return MaterialKey.of(stack);
    }
}
