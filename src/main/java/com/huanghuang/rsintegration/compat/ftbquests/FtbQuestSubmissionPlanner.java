package com.huanghuang.rsintegration.compat.ftbquests;

import com.huanghuang.rsintegration.crafting.CraftPacketUtils;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.refinedmods.refinedstorage.api.network.INetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds a multi-root recursive crafting preview for one eligible quest. */
public final class FtbQuestSubmissionPlanner {

    private FtbQuestSubmissionPlanner() {}

    public static QuestSubmissionPlan plan(ServerPlayer player,
                                            QuestSubmissionSnapshot snapshot,
                                            INetwork network) {
        List<IngredientSpec> specs = new ArrayList<>();
        List<String> overflow = new ArrayList<>();
        for (QuestItemRequirement requirement : snapshot.requirements()) {
            int remaining = boundedCount(requirement.remaining(), overflow,
                    requirement.displayStack().getHoverName().getString());
            if (remaining <= 0) continue;
            Ingredient ingredient = requirement.validDisplayItems().isEmpty()
                    ? Ingredient.of(requirement.displayStack())
                    : Ingredient.of(requirement.validDisplayItems().stream());
            specs.add(new IngredientSpec(ingredient, remaining));
        }

        Map<CraftingResolver.StackKey, Integer> available =
                MaterialSources.listAllAvailable(player, network);
        List<String> missing = new ArrayList<>(overflow);
        var graph = CraftingResolver.resolveGraphForSpecsWithTypes(specs, available,
                player.serverLevel(), player, network, missing, null, false);

        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        for (int i = 0; i < specs.size(); i++) {
            IngredientSpec spec = specs.get(i);
            ItemStack display = snapshot.requirements().get(i).displayStack().copyWithCount(1);
            int have = available.entrySet().stream()
                    .filter(entry -> spec.ingredient().test(entry.getKey().toStack()))
                    .mapToInt(Map.Entry::getValue).sum();
            materials.put(IngredientKey.of(display),
                    new PlanResponse.Availability(spec.count(), have));
        }
        return new QuestSubmissionPlan(snapshot, graph, PlanGraphView.from(graph),
                materials, missing.stream().distinct().toList());
    }

    private static int boundedCount(long count, List<String> errors, String name) {
        if (count > Integer.MAX_VALUE) {
            errors.add(name + " x" + count);
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0L, count);
    }
}
