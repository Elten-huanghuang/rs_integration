package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.mods.ironsspellbooks.InkFluidSupport;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** 与可被下游消耗的任务库存分开，只累计真实产出。 */
final class CraftProductionHistory {
    private final Map<IntegrationResourceKey, Long> produced = new HashMap<>();

    void record(ItemStack stack) {
        record(stack, stack.getCount());
    }

    void record(ItemStack stack, long amount) {
        if (amount > 0 && !stack.isEmpty() && !InkFluidSupport.isToken(stack)) {
            add(IntegrationResourceKey.item(stack), amount);
        }
    }

    void record(ItemStack stack, OutputKind kind) {
        if (isProduction(kind)) record(stack);
    }

    void record(CollectedOutput output) {
        if (output.resource().kind() != IntegrationResourceKey.ResourceKind.ITEM) return;
        switch (output.outputKind()) {
            case PRIMARY, SECONDARY, TRANSFORMED, DYNAMIC -> add(output.resource(), output.amount());
            default -> {}
        }
    }

    static boolean isProduction(OutputKind kind) {
        return switch (kind) {
            case PRIMARY, SECONDARY, TRANSFORMED, DYNAMIC -> true;
            default -> false;
        };
    }

    private void add(IntegrationResourceKey key, long count) {
        produced.merge(key, count, SaturatingLongMath::add);
    }

    Map<IntegrationResourceKey, Long> snapshot() {
        return Map.copyOf(produced);
    }
}
