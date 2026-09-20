package com.huanghuang.rsintegration.compat.ftbquests;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class QuestTaskAvailability {

    private QuestTaskAvailability() {
    }

    static List<Long> newlyAvailableTaskIds(Collection<Long> availableBefore,
                                             Collection<Long> availableAfter) {
        Set<Long> before = availableBefore == null
                ? Set.of() : new LinkedHashSet<>(availableBefore);
        List<Long> result = new ArrayList<>();
        if (availableAfter == null) return result;
        for (Long taskId : availableAfter) {
            if (taskId != null && !before.contains(taskId)) result.add(taskId);
        }
        return result;
    }
}
