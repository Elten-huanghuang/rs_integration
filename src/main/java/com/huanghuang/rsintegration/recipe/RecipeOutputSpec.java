package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;

import javax.annotation.Nullable;
import java.util.Objects;

/** 单次物理操作的声明输出，供终端图无损投影。 */
public record RecipeOutputSpec(
        MaterialKey material,
        int expectedPerOperation,
        int minimumPerOperation,
        int maximumPerOperation,
        OutputKind kind,
        OutputProbability probability,
        boolean dynamicIdentity,
        @Nullable Integer sourceInputIndex
) {
    public RecipeOutputSpec {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(probability, "probability");
        if (expectedPerOperation < 0
                || minimumPerOperation < 0
                || maximumPerOperation < minimumPerOperation
                || maximumPerOperation == 0
                && (expectedPerOperation > 0 || minimumPerOperation > 0)) {
            throw new IllegalArgumentException("invalid output per-operation range");
        }
        if (expectedPerOperation < minimumPerOperation
                || expectedPerOperation > maximumPerOperation) {
            throw new IllegalArgumentException(
                    "expected output must be within its range");
        }
        if (sourceInputIndex != null && sourceInputIndex < 0) {
            throw new IllegalArgumentException(
                    "source input index must be non-negative");
        }
    }

    public RecipeOutputSpec(
            MaterialKey material,
            int perOperation,
            OutputKind kind
    ) {
        this(material, perOperation, perOperation, perOperation, kind,
                OutputProbability.guaranteed(), false, null);
    }
}
