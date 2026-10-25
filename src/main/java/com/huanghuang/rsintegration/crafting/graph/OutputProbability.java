package com.huanghuang.rsintegration.crafting.graph;

import javax.annotation.Nullable;
import java.util.Objects;

/** 一个输出端口的触发概率语义，与数量范围分开表达。 */
public record OutputProbability(
        Mode mode,
        @Nullable Double chance,
        int maxAttempts,
        String rollGroup
) {
    public enum Mode {
        GUARANTEED,
        PROBABILISTIC,
        DYNAMIC
    }

    public OutputProbability {
        Objects.requireNonNull(mode, "mode");
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (rollGroup == null || rollGroup.isBlank()) {
            throw new IllegalArgumentException("rollGroup must not be blank");
        }
        if (chance != null && (chance < 0.0D || chance > 1.0D || chance.isNaN())) {
            throw new IllegalArgumentException("chance must be in [0, 1]");
        }
        if (mode == Mode.GUARANTEED && (chance == null || chance != 1.0D || maxAttempts != 1)) {
            throw new IllegalArgumentException(
                    "guaranteed output requires chance=1 and maxAttempts=1");
        }
        if (mode == Mode.PROBABILISTIC && chance == null) {
            throw new IllegalArgumentException(
                    "probabilistic output requires explicit chance");
        }
    }

    public static OutputProbability guaranteed() {
        return new OutputProbability(Mode.GUARANTEED, 1.0D, 1, "guaranteed");
    }

    public static OutputProbability probabilistic(
            double chance, int maxAttempts, String rollGroup) {
        return new OutputProbability(Mode.PROBABILISTIC, chance, maxAttempts, rollGroup);
    }

    public static OutputProbability dynamic(
            @Nullable Double chance, int maxAttempts, String rollGroup) {
        return new OutputProbability(Mode.DYNAMIC, chance, maxAttempts, rollGroup);
    }

    public boolean isGuaranteed() {
        return mode == Mode.GUARANTEED;
    }
}
