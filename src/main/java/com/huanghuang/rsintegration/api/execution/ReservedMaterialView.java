package com.huanghuang.rsintegration.api.execution;

import java.util.List;
import java.util.Optional;

public interface ReservedMaterialView {
    List<ReservedMaterial> materials();

    Optional<ReservedMaterial> find(String entryId);
}
