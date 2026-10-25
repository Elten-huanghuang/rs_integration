package com.huanghuang.rsintegration.api.consumer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public record ConsumerRecipeCatalogPage(
        ConsumerRecipeQuery query,
        long recipeRevision,
        long cursor,
        @Nullable Long nextCursor,
        List<ConsumerRecipeCandidate> candidates
) {
    public ConsumerRecipeCatalogPage {
        if (query == null || recipeRevision <= 0L || cursor < 0L
                || (nextCursor != null && nextCursor <= cursor)) {
            throw new IllegalArgumentException(
                    "invalid recipe catalog page");
        }
        if (candidates != null
                && candidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "catalog candidates must not contain null");
        }
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public boolean complete() {
        return nextCursor == null;
    }
}
