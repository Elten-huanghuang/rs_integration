package com.huanghuang.rsintegration.api.recipe;

import com.huanghuang.rsintegration.api.IntegrationResourceKey;

public record ItemAlternative(
        IntegrationResourceKey resource
) {
    public ItemAlternative {
        if (resource == null
                || resource.kind() != IntegrationResourceKey.ResourceKind.ITEM) {
            throw new IllegalArgumentException("item alternative is required");
        }
    }
}
