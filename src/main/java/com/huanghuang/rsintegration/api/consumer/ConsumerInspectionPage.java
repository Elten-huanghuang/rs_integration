package com.huanghuang.rsintegration.api.consumer;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

public record ConsumerInspectionPage(
        ConsumerInspectionTicketId ticketId,
        int pageIndex,
        ConsumerInspectionPageState state,
        @Nullable Integer nextPageIndex,
        long snapshotRevision,
        List<ConsumerCraftability> results
) {
    public ConsumerInspectionPage {
        if (ticketId == null || pageIndex < 0 || state == null
                || snapshotRevision <= 0L
                || (nextPageIndex != null && nextPageIndex <= pageIndex)) {
            throw new IllegalArgumentException("invalid inspection page");
        }
        if (results != null
                && results.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "inspection results must not contain null");
        }
        results = results == null ? List.of() : List.copyOf(results);
        if (state != ConsumerInspectionPageState.COMPLETE
                && !results.isEmpty()) {
            throw new IllegalArgumentException(
                    "incomplete page cannot contain results");
        }
        if (state != ConsumerInspectionPageState.COMPLETE
                && nextPageIndex != null) {
            throw new IllegalArgumentException(
                    "incomplete page cannot advance the cursor");
        }
    }

    public boolean complete() {
        return state == ConsumerInspectionPageState.COMPLETE
                && nextPageIndex == null;
    }
}
