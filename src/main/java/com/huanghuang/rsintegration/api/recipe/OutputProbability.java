package com.huanghuang.rsintegration.api.recipe;

import javax.annotation.Nullable;

public record OutputProbability(
        OutputProbabilityMode mode,
        @Nullable Double chance,
        int maxAttempts,
        String rollGroup
) {
    public OutputProbability {
        if (mode == null || maxAttempts < 1
                || rollGroup == null || rollGroup.isBlank()) {
            throw new IllegalArgumentException("probability model is incomplete");
        }
        if (chance != null && (!Double.isFinite(chance) || chance < 0.0D || chance > 1.0D)) {
            throw new IllegalArgumentException("chance must be in [0, 1]");
        }
        // 已知概率为 100% 时就是固定触发；动态模型不能仅凭估计概率推断必出。
        if (mode == OutputProbabilityMode.PROBABILISTIC
                && chance != null && chance == 1.0D) {
            mode = OutputProbabilityMode.GUARANTEED;
            maxAttempts = 1;
        }
        if (mode == OutputProbabilityMode.GUARANTEED
                && (chance == null || chance != 1.0D || maxAttempts != 1)) {
            throw new IllegalArgumentException(
                    "guaranteed output requires chance=1 and maxAttempts=1");
        }
        if (mode == OutputProbabilityMode.PROBABILISTIC && chance == null) {
            throw new IllegalArgumentException(
                    "probabilistic output requires explicit chance");
        }
    }

    public boolean isGuaranteed() {
        return mode == OutputProbabilityMode.GUARANTEED;
    }

    public static OutputProbability guaranteed() {
        return new OutputProbability(
                OutputProbabilityMode.GUARANTEED, 1.0D, 1, "guaranteed");
    }

    public static OutputProbability probabilistic(
            double chance, int maxAttempts, String rollGroup) {
        return new OutputProbability(
                OutputProbabilityMode.PROBABILISTIC,
                chance, maxAttempts, rollGroup);
    }

    public static OutputProbability dynamic(
            @Nullable Double chance, int maxAttempts, String rollGroup) {
        return new OutputProbability(
                OutputProbabilityMode.DYNAMIC,
                chance, maxAttempts, rollGroup);
    }
}
