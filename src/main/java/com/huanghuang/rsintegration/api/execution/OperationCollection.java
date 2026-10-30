package com.huanghuang.rsintegration.api.execution;

import com.huanghuang.rsintegration.api.diagnostic.IntegrationDiagnostic;

import java.util.List;

/**
 * 一次操作的真实结算。{@code complete=true} 证明操作和材料处置已结算，
 * 不表示订单数量目标已经达到；概率未命中时允许 {@code outputs} 为空。
 */
public record OperationCollection(
        long collectionSequence,
        List<CollectedOutput> outputs,
        List<MaterialDisposition> materials,
        boolean complete,
        List<IntegrationDiagnostic> diagnostics
) {
    public OperationCollection {
        if (collectionSequence < 1L) {
            throw new IllegalArgumentException(
                    "collectionSequence must be positive");
        }
        outputs = outputs == null ? List.of() : List.copyOf(outputs);
        materials = materials == null ? List.of() : List.copyOf(materials);
        diagnostics = diagnostics == null ? List.of() : List.copyOf(diagnostics);
    }
}
