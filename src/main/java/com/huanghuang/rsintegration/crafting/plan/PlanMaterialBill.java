package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the material availability view shared by plan validation and the client tree. */
public final class PlanMaterialBill {
    private PlanMaterialBill() {}

    public static Result summarize(Map<Item, ? extends Number> neededCounts,
                                   Map<Item, Ingredient> itemSources,
                                   Map<Item, ? extends Number> itemAvailable,
                                   Map<StackKey, ? extends Number> stackAvailable,
                                   ItemStack targetOutput,
                                   List<PlanStep> steps,
                                   int repeatCount,
                                   @Nullable PlanGraphView graph,
                                   boolean hasMissing) {
        Map<IngredientKey, PlanResponse.Availability> netMaterials = graph == null
                ? buildLegacyNetMaterials(neededCounts, itemSources, itemAvailable, stackAvailable,
                        targetOutput, steps, repeatCount)
                : buildGraphNetMaterials(graph, itemAvailable, stackAvailable);
        boolean feasible = !hasMissing
                && netMaterials.values().stream().allMatch(PlanResponse.Availability::isEnough);

        Map<IngredientKey, Long> leftovers = new LinkedHashMap<>();
        for (Map.Entry<Item, ? extends Number> entry : neededCounts.entrySet()) {
            long count = entry.getValue() == null ? 0L : entry.getValue().longValue();
            if (count >= 0L || entry.getKey() == targetOutput.getItem()) continue;
            leftovers.put(IngredientKey.of(new ItemStack(entry.getKey())),
                    count == Long.MIN_VALUE ? Long.MAX_VALUE : -count);
        }

        Map<IngredientKey, PlanResponse.Availability> displayMaterials = graph == null
                ? buildDisplayMaterials(netMaterials, itemSources, itemAvailable, stackAvailable,
                        targetOutput, steps, repeatCount, null)
                // A graph is already the server-authoritative material-flow view.  Do not
                // rebuild its bill through PlanTreeModel: the visual tree intentionally folds
                // shared producer nodes, so its gross traversal is not a complete external
                // leaf-material list and can expose intermediate outputs as materials.
                : new LinkedHashMap<>(netMaterials);
        return new Result(feasible, displayMaterials, leftovers);
    }

    private static Map<IngredientKey, PlanResponse.Availability> buildNetMaterials(
            Map<Item, ? extends Number> neededCounts,
            Map<Item, Ingredient> itemSources,
            Map<Item, ? extends Number> itemAvailable,
            Map<StackKey, ? extends Number> stackAvailable) {
        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        Set<String> mergedTagKeys = new HashSet<>();
        for (Map.Entry<Item, ? extends Number> entry : neededCounts.entrySet()) {
            long needed = entry.getValue() == null ? 0L : entry.getValue().longValue();
            if (needed <= 0L) continue;
            Item displayItem = entry.getKey();
            Ingredient source = itemSources.get(displayItem);
            if (source != null && source.getItems().length > 1) {
                String tagKey = source.toJson().toString();
                if (!mergedTagKeys.add(tagKey)) continue;
                long netNeeded = 0L;
                long totalHave = 0L;
                for (ItemStack option : source.getItems()) {
                    if (option.isEmpty()) continue;
                    Item optionItem = option.getItem();
                    netNeeded = SaturatingLongMath.signedAdd(netNeeded,
                            countOf(neededCounts, optionItem));
                    totalHave = SaturatingLongMath.add(totalHave,
                            countAvailable(itemAvailable, optionItem));
                }
                materials.put(IngredientKey.of(new ItemStack(displayItem)),
                        new PlanResponse.Availability(Math.max(0, netNeeded), totalHave));
                continue;
            }

            boolean nbtStrict = source != null && IngredientMatcher.requiresNbt(source);
            long have = nbtStrict
                    ? countNbtMatching(source, stackAvailable)
                    : countAvailable(itemAvailable, displayItem);
            ItemStack materialDisplay = new ItemStack(displayItem);
            if (nbtStrict && source.getItems().length > 0) {
                materialDisplay = source.getItems()[0].copyWithCount(1);
            }
            materials.put(IngredientKey.of(materialDisplay),
                    new PlanResponse.Availability(needed, have));
        }
        return materials;
    }

    private static Map<IngredientKey, PlanResponse.Availability> buildLegacyNetMaterials(
            Map<Item, ? extends Number> neededCounts,
            Map<Item, Ingredient> itemSources,
            Map<Item, ? extends Number> itemAvailable,
            Map<StackKey, ? extends Number> stackAvailable,
            ItemStack targetOutput,
            List<PlanStep> steps,
            int repeatCount) {
        Map<IngredientKey, PlanResponse.Availability> materials = buildNetMaterials(
                neededCounts, itemSources, itemAvailable, stackAvailable);
        Map<IngredientKey, Long> gross = buildGrossDemand(
                targetOutput, steps, repeatCount, null);
        Set<Item> nbtItems = new HashSet<>();
        for (IngredientKey key : gross.keySet()) {
            Ingredient source = itemSources.get(key.item());
            if (key.stack(1).hasTag()
                    && source != null
                    && IngredientMatcher.requiresNbt(source)) {
                nbtItems.add(key.item());
            }
        }
        if (nbtItems.isEmpty()) return materials;

        // The old Item-keyed bill cannot represent two strict variants of the
        // same item. Replace only item types that are exclusively represented
        // by concrete NBT keys in this tree; mixed plain/tag demands retain the
        // legacy semantic aggregation for their plain portion.
        for (Item item : nbtItems) {
            boolean allTagged = gross.keySet().stream()
                    .filter(key -> key.item() == item)
                    .allMatch(key -> key.stack(1).hasTag());
            if (!allTagged) continue;
            materials.entrySet().removeIf(entry -> entry.getKey().item() == item);
            for (Map.Entry<IngredientKey, Long> entry : gross.entrySet()) {
                IngredientKey key = entry.getKey();
                if (key.item() != item) continue;
                long produced = 0L;
                int terminalStep = terminalStepIndex(targetOutput, steps);
                for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                    // The final step matching the requested output is the
                    // terminal operation and must not cancel its own input.
                    if (stepIndex == terminalStep) continue;
                    PlanStep step = steps.get(stepIndex);
                    if (IngredientKey.of(step.output()).equals(key)) {
                        produced = SaturatingLongMath.add(
                                produced, step.totalOutputCount());
                    }
                }
                long externalNeeded = Math.max(0L, entry.getValue() - produced);
                if (externalNeeded == 0L) continue;
                ItemStack display = key.stack(1);
                materials.put(key, new PlanResponse.Availability(
                        externalNeeded, countExactStack(display, stackAvailable)));
            }
        }
        return materials;
    }

    private static long countOf(Map<Item, ? extends Number> counts, Item item) {
        Number count = counts.get(item);
        return count == null ? 0L : count.longValue();
    }

    private static long countAvailable(Map<Item, ? extends Number> counts, Item item) {
        return countOf(counts, item);
    }

    private static Map<IngredientKey, Long> buildGrossDemand(ItemStack targetOutput,
                                                                  List<PlanStep> steps,
                                                                  int repeatCount,
                                                                  @Nullable PlanGraphView graph) {
        String recipeId = "";
        if (graph == null) {
            int terminal = terminalStepIndex(targetOutput, steps);
            if (terminal >= 0) recipeId = steps.get(terminal).recipeId().toString();
        }
        PlanResponse treeSource = new PlanResponse(true, "", targetOutput, steps,
                Collections.emptyMap(), Collections.emptyList(), recipeId,
                null, null, 0, 0, 0, Collections.emptyList(), repeatCount,
                null, null, null, 0, false, false, false, null,
                Collections.emptySet(), Collections.emptyMap(), null, graph);
        return PlanTreeModel.grossDemandByKey(PlanTreeModel.from(treeSource));
    }

    private static int terminalStepIndex(ItemStack targetOutput, List<PlanStep> steps) {
        IngredientKey targetKey = IngredientKey.of(targetOutput);
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (IngredientKey.of(steps.get(i).output()).equals(targetKey)) return i;
        }
        return -1;
    }

    /**
     * Builds the external bill from the server-authoritative allocation graph.
     * The legacy resolver maps demands by Item, which is insufficient for strict
     * ingredients such as differently enchanted books.  Initial-pool edges and
     * unresolved demands already contain the exact selected MaterialKey, so they
     * are the only sources that should enter the external material bill.
     */
    private static Map<IngredientKey, PlanResponse.Availability> buildGraphNetMaterials(
            PlanGraphView graph,
            Map<Item, ? extends Number> itemAvailable,
            Map<StackKey, ? extends Number> stackAvailable) {
        Map<IngredientKey, Long> needed = new LinkedHashMap<>();
        for (PlanGraphView.EdgeView edge : graph.edges()) {
            if (edge.source().initial()) {
                mergeDemand(needed, edge.material(), edge.quantity());
            }
        }
        for (PlanGraphView.RootView root : graph.roots()) {
            for (PlanGraphView.RootEdgeView edge : root.allocations()) {
                if (edge.source().initial()) {
                    mergeDemand(needed, edge.material(), edge.quantity());
                }
            }
        }
        for (PlanGraphView.UnresolvedView unresolved : graph.unresolved()) {
            mergeDemand(needed, unresolved.display(), unresolved.quantity());
        }

        Map<IngredientKey, PlanResponse.Availability> result = new LinkedHashMap<>();
        for (Map.Entry<IngredientKey, Long> entry : needed.entrySet()) {
            IngredientKey key = entry.getKey();
            ItemStack display = key.stack(1);
            long available = display.hasTag()
                    ? countExactStack(display, stackAvailable)
                    : countAvailable(itemAvailable, key.item());
            result.put(key, new PlanResponse.Availability(entry.getValue(), available));
        }
        return result;
    }

    private static void mergeDemand(Map<IngredientKey, Long> demand,
                                    ItemStack material, long quantity) {
        if (material == null || material.isEmpty() || quantity <= 0L) return;
        IngredientKey key = IngredientKey.of(material);
        demand.merge(key, quantity, SaturatingLongMath::add);
    }

    private static Map<IngredientKey, PlanResponse.Availability> buildDisplayMaterials(
            Map<IngredientKey, PlanResponse.Availability> netMaterials,
            Map<Item, Ingredient> itemSources,
            Map<Item, ? extends Number> itemAvailable,
            Map<StackKey, ? extends Number> stackAvailable,
            ItemStack targetOutput,
            List<PlanStep> steps,
            int repeatCount,
            @Nullable PlanGraphView graph) {
        Map<IngredientKey, Long> grossDemand =
                buildGrossDemand(targetOutput, steps, repeatCount, graph);

        Map<IngredientKey, PlanResponse.Availability> displayMaterials = new LinkedHashMap<>();
        for (Map.Entry<IngredientKey, Long> entry : grossDemand.entrySet()) {
            IngredientKey key = entry.getKey();
            ItemStack display = key.stack(1);
            // Graph allocations preserve the concrete NBT selected by the
            // planner. Do not fall back to itemSources here: that map is keyed
            // only by Item, so four strict enchanted-book demands would all be
            // measured against the first book's enchantment.
            if (graph != null) {
                PlanResponse.Availability graphAvailability = netMaterials.get(key);
                if (graphAvailability != null) {
                    displayMaterials.put(key, new PlanResponse.Availability(
                            entry.getValue(), graphAvailability.available()));
                    continue;
                }
            }
            Ingredient source = itemSources.get(key.item());
            boolean nbtStrict = source != null && IngredientMatcher.requiresNbt(source);
            long have;
            IngredientKey materialKey = key;
            if (source != null && !nbtStrict) {
                // A vanilla Ingredient may expose a tagged JEI display stack while
                // matching by item only.  Count every stored variant and collapse
                // the material card to the semantic item identity.
                have = countAvailable(itemAvailable, key.item());
                materialKey = IngredientKey.of(new ItemStack(key.item()));
            } else if (source != null) {
                have = countNbtMatching(source, stackAvailable);
            } else {
                have = display.hasTag()
                        ? countExactStack(display, stackAvailable)
                        : countAvailable(itemAvailable, key.item());
            }
            PlanResponse.Availability availability =
                    new PlanResponse.Availability(entry.getValue(), have);
            displayMaterials.merge(materialKey, availability, (left, right) ->
                    new PlanResponse.Availability(left.needed() + right.needed(),
                            Math.max(left.available(), right.available())));
        }
        // Keep gross demand for tree nodes without treating planned production
        // as missing stock. Only the net bill describes external shortages.
        displayMaterials.replaceAll((key, gross) -> {
            PlanResponse.Availability net = netMaterials.get(key);
            return new PlanResponse.Availability(gross.needed(), gross.available(),
                    net == null ? 0 : net.missingCount());
        });
        for (Map.Entry<IngredientKey, PlanResponse.Availability> entry : netMaterials.entrySet()) {
            displayMaterials.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return displayMaterials;
    }

    private static long countNbtMatching(Ingredient ingredient,
                                         Map<StackKey, ? extends Number> available) {
        long total = 0L;
        for (Map.Entry<StackKey, ? extends Number> entry : available.entrySet()) {
            if (IngredientMatcher.test(ingredient, entry.getKey())) {
                total = SaturatingLongMath.add(total, entry.getValue().longValue());
            }
        }
        return total;
    }

    private static long countExactStack(ItemStack expected,
                                        Map<StackKey, ? extends Number> available) {
        long total = 0L;
        for (Map.Entry<StackKey, ? extends Number> entry : available.entrySet()) {
            if (MaterialMatcher.equivalentRuntimeFragment(entry.getKey().toStack(), expected)) {
                total = SaturatingLongMath.add(total, entry.getValue().longValue());
            }
        }
        return total;
    }

    public record Result(boolean feasible,
                         Map<IngredientKey, PlanResponse.Availability> materials,
                          Map<IngredientKey, Long> leftovers) {
        public Result {
            materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
            leftovers = Collections.unmodifiableMap(new LinkedHashMap<>(leftovers));
        }
    }
}
