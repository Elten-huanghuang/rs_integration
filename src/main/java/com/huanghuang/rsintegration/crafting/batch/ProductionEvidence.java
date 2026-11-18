package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import javax.annotation.Nullable;
import java.util.List;

/** 非实物凭证不能进入任务私有库存；交付凭证和仅观察的产量也不能互换。 */
public interface ProductionEvidence {
    ProductionEvidence NONE = new ProductionEvidence() {};

    static ProductionEvidence from(IBatchDelegate operation) {
        return operation instanceof ProductionEvidence evidence ? evidence : NONE;
    }

    default List<CollectedOutput> takeDelivered() { return List.of(); }
    default List<CollectedOutput> takeObserved() { return List.of(); }
    @Nullable default String deliveryFailure() { return null; }
}
