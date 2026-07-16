package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;

import java.util.List;
import java.util.Map;

public record QuestSubmissionPlan(
        QuestSubmissionSnapshot snapshot,
        CraftPlanGraph graph,
        PlanGraphView graphView,
        Map<IngredientKey, PlanResponse.Availability> materials,
        List<String> missing) {

    public QuestSubmissionPlan {
        materials = Map.copyOf(materials);
        missing = List.copyOf(missing);
    }

    public boolean feasible() {
        return missing.isEmpty() && graph.unresolvedDemands().isEmpty();
    }
}
