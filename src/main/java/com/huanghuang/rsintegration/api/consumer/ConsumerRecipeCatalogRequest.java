package com.huanghuang.rsintegration.api.consumer;

public record ConsumerRecipeCatalogRequest(
        ConsumerRecipeQuery query,
        int pageSize,
        long cursor
) {
    public ConsumerRecipeCatalogRequest {
        if (query == null || pageSize <= 0 || cursor < 0L) {
            throw new IllegalArgumentException(
                    "invalid recipe catalog request");
        }
    }
}
