package com.huanghuang.rsintegration.crafting;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Objects;

/** 每个递归目标独立计数，只在真实产物结算后增加进度。 */
final class ProbabilisticProduction {
    private final ProductionTarget target;
    private final int maxAttempts;
    private int attempts;
    private int produced;

    ProbabilisticProduction(ProductionTarget target, int maxAttempts) {
        this.target = Objects.requireNonNull(target, "target");
        if (maxAttempts <= 0) throw new IllegalArgumentException("attempt budget must be positive");
        this.maxAttempts = maxAttempts;
    }

    ProductionTarget target() { return target; }
    int attempts() { return attempts; }
    int produced() { return produced; }
    int remaining() { return Math.max(0, target.quantity() - produced); }
    int maxAttempts() { return maxAttempts; }
    boolean complete() { return remaining() == 0; }
    boolean canAttempt() { return !complete() && attempts < maxAttempts; }

    void started() {
        if (!canAttempt()) throw new IllegalStateException("probabilistic attempt budget exhausted");
        attempts++;
    }

    void settle(List<ItemStack> outputs) {
        for (ItemStack output : outputs) {
            if (MaterialMatcher.matchesOutputDeclaration(target.material(), output)) {
                produced = MaterialSources.saturatedAdd(produced, output.getCount());
            }
        }
    }
}
