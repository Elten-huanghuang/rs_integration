package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;

import java.util.List;

/**
 * 配方声明、执行端口与内部输出合同共用的产出语义。
 *
 * <p>递归规划只承诺 {@link #guaranteedMinimum()}，不把概率额外产物计入供料。
 * 数量保证与动态身份分别判断；动态身份仍由规划器限制精确 NBT 需求。</p>
 */
public interface OutputSemantics {
    IntegrationResourceKey resource();

    long guaranteedMinimum();

    long maximumAmount();

    boolean isPrimary();

    boolean dynamicIdentity();

    boolean targetProducing();

    default boolean hasRandomizedAmount() {
        return guaranteedMinimum() < maximumAmount();
    }

    default boolean supportsDeterministicProjection() {
        return guaranteedMinimum() > 0L;
    }

    default boolean requiresTargetedProduction() {
        return targetProducing() || !supportsDeterministicProjection();
    }

    /** 没有必出下限的端口，操作完成后允许完全没有产物。 */
    default boolean permitsEmptyResult() {
        return guaranteedMinimum() == 0L;
    }

    /** 无数量目标的请求按固定操作次数尽力交付，递归供料按真实数量补足。 */
    static ProductionCompletionPolicy completionPolicy(
            List<? extends OutputSemantics> outputs, IntegrationResourceKey target) {
        return target != null && requiresTargetedProduction(outputs, target)
                ? ProductionCompletionPolicy.TARGET_QUANTITY
                : ProductionCompletionPolicy.FIXED_OPERATIONS;
    }

    default boolean matchesTarget(IntegrationResourceKey target) {
        IntegrationResourceKey declared = resource();
        return declared.kind() == target.kind()
                && declared.id().equals(target.id())
                && (target.identity().isEmpty()
                || declared.identity().equals(target.identity()));
    }

    static boolean hasDeterministicPrimary(List<? extends OutputSemantics> outputs) {
        return outputs.stream().filter(OutputSemantics::isPrimary)
                .findFirst()
                .map(OutputSemantics::supportsDeterministicProjection)
                .orElse(false);
    }

    static boolean hasRandomizedOutputs(List<? extends OutputSemantics> outputs) {
        return outputs.stream().anyMatch(OutputSemantics::hasRandomizedAmount);
    }

    static boolean supportsTargetedProduction(List<? extends OutputSemantics> outputs) {
        return outputs.stream().anyMatch(OutputSemantics::requiresTargetedProduction);
    }

    static boolean requiresTargetedProduction(
            List<? extends OutputSemantics> outputs, IntegrationResourceKey target) {
        // 主产物的单次数量是现有订单的基准，不能用同物品的少量副产物替代该承诺。
        for (OutputSemantics output : outputs) {
            if (output.isPrimary() && output.matchesTarget(target)) {
                return output.requiresTargetedProduction();
            }
        }
        boolean matched = false;
        for (OutputSemantics output : outputs) {
            if (!output.matchesTarget(target)) continue;
            if (!output.requiresTargetedProduction()) return false;
            matched = true;
        }
        return matched;
    }
}
