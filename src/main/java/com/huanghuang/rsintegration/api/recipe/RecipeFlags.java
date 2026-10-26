package com.huanghuang.rsintegration.api.recipe;

/**
 * 配方的公共规划与执行语义。
 *
 * <p>{@code supportsBatching=true} 表示机器允许把多个操作的同槽材料一次性写入，
 * 并在完成前累计产生全部确定性输出。它必须和真实机器容量、输出端口及
 * {@link #orderedInputs()} 保持一致，不能只作为性能提示使用。</p>
 */
public record RecipeFlags(
        boolean recursivePlanning,
        boolean targetProduction,
        boolean deterministicOutput,
        boolean runtimeDependentIdentity,
        boolean requiresFuel,
        boolean supportsBatching,
        boolean orderedInputs
) {}
