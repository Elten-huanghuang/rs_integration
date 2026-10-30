package com.huanghuang.rsintegration.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.recipe.OutputSemantics;
import com.huanghuang.rsintegration.crafting.graph.MaterialKey;
import com.huanghuang.rsintegration.crafting.graph.OutputKind;
import com.huanghuang.rsintegration.crafting.graph.OutputProbability;

import javax.annotation.Nullable;
import java.util.Objects;

/** 单次物理操作的声明输出，供终端图无损投影。 */
public record RecipeOutputSpec(
        @Nullable MaterialKey material,
        IntegrationResourceKey resource,
        int expectedPerOperation,
        int minimumPerOperation,
        int maximumPerOperation,
        OutputKind kind,
        OutputProbability probability,
        boolean dynamicIdentity,
        @Nullable Integer sourceInputIndex,
        boolean targetProducing
) implements OutputSemantics {
    public RecipeOutputSpec(
            @Nullable MaterialKey material, IntegrationResourceKey resource,
            int expectedPerOperation, int minimumPerOperation, int maximumPerOperation,
            OutputKind kind, OutputProbability probability, boolean dynamicIdentity,
            @Nullable Integer sourceInputIndex) {
        this(material, resource, expectedPerOperation, minimumPerOperation,
                maximumPerOperation, kind, probability, dynamicIdentity,
                sourceInputIndex, false);
    }

    public RecipeOutputSpec {
        Objects.requireNonNull(resource, "resource");
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
        if (resource.kind() == IntegrationResourceKey.ResourceKind.ITEM
                && material == null) {
            throw new IllegalArgumentException(
                    "item output requires material projection");
        }
    }

    @Override
    public long guaranteedMinimum() {
        return probability.isGuaranteed() ? minimumPerOperation : 0L;
    }

    @Override
    public long maximumAmount() {
        return maximumPerOperation;
    }

    @Override
    public boolean isPrimary() {
        return kind == OutputKind.PRIMARY;
    }

    public RecipeOutputSpec withTargetProducing(boolean targeted) {
        return new RecipeOutputSpec(material, resource, expectedPerOperation,
                minimumPerOperation, maximumPerOperation, kind, probability,
                dynamicIdentity, sourceInputIndex, targeted);
    }

    public static RecipeOutputSpec item(
            MaterialKey material,
            int perOperation,
            OutputKind kind
    ) {
        return item(material, perOperation, perOperation, perOperation, kind,
                OutputProbability.guaranteed(), false, null);
    }

    public static RecipeOutputSpec item(
            MaterialKey material,
            int expectedPerOperation,
            int minimumPerOperation,
            int maximumPerOperation,
            OutputKind kind,
            OutputProbability probability,
            boolean dynamicIdentity,
            @Nullable Integer sourceInputIndex
    ) {
        Objects.requireNonNull(material, "material");
        return new RecipeOutputSpec(
                material,
                IntegrationResourceKey.item(material.toStack(1)),
                expectedPerOperation,
                minimumPerOperation,
                maximumPerOperation,
                kind,
                probability,
                dynamicIdentity,
                sourceInputIndex
        );
    }

    public static RecipeOutputSpec fluid(
            IntegrationResourceKey resource,
            int expectedPerOperation,
            int minimumPerOperation,
            int maximumPerOperation,
            OutputKind kind,
            OutputProbability probability,
            boolean dynamicIdentity,
            @Nullable Integer sourceInputIndex
    ) {
        if (resource.kind()
                != IntegrationResourceKey.ResourceKind.FLUID) {
            throw new IllegalArgumentException(
                    "fluid output requires a fluid resource");
        }
        return new RecipeOutputSpec(
                null,
                resource,
                expectedPerOperation,
                minimumPerOperation,
                maximumPerOperation,
                kind,
                probability,
                dynamicIdentity,
                sourceInputIndex
        );
    }
}
