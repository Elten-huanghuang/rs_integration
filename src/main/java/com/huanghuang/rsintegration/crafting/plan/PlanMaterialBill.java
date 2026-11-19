package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
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

    /** 草稿只汇总已分配的外部材料，不把未结算的需求提示当成缺料。 */
    static Map<IngredientKey, PlanResponse.Availability> completedAllocations(
            @Nullable PlanGraphView graph, Map<StackKey, Long> snapshotStock) {
        if (graph == null) return Map.of();
        Map<IngredientKey, Long> needed = new LinkedHashMap<>();
        graph.visitInitialAllocations((stack, count) -> mergeDemand(needed, stack, count));
        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        needed.forEach((key, count) -> {
            long available = snapshotStock.getOrDefault(StackKey.of(key.stack(1), true), 0L);
            materials.put(key, new PlanResponse.Availability(count, available, 0L));
        });
        return Collections.unmodifiableMap(materials);
    }

    public static Result summarize(Map<Item, ? extends Number> neededCounts,
                                   Map<Item, Ingredient> itemSources,
                                   Map<Item, ? extends Number> itemAvailable,
                                   Map<StackKey, ? extends Number> stackAvailable,
                                   ItemStack targetOutput,
                                   List<PlanStep> steps,
                                   int repeatCount,
                                   @Nullable PlanGraphView graph,
                                   boolean hasMissing) {
        Map<IngredientKey, Long> traceDemand = graph == null ? traceDemand(steps) : Map.of();
        Map<IngredientKey, PlanResponse.Availability> netMaterials = graph == null
                ? buildTraceNetMaterials(neededCounts, itemSources, itemAvailable, stackAvailable,
                        targetOutput, steps, traceDemand)
                : buildGraphNetMaterials(graph, stackAvailable);
        boolean feasible = !hasMissing
                && (graph == null || !graph.hasUnresolvedDemands())
                && netMaterials.values().stream().allMatch(material -> material.missingCount() == 0);

        Map<IngredientKey, Long> leftovers = new LinkedHashMap<>();
        for (Map.Entry<Item, ? extends Number> entry : neededCounts.entrySet()) {
            long count = entry.getValue() == null ? 0L : entry.getValue().longValue();
            if (count >= 0L || entry.getKey() == targetOutput.getItem()) continue;
            leftovers.put(IngredientKey.of(new ItemStack(entry.getKey())),
                    count == Long.MIN_VALUE ? Long.MAX_VALUE : -count);
        }

        Map<IngredientKey, PlanResponse.Availability> displayMaterials = graph == null
                ? buildDisplayMaterials(netMaterials, itemSources, itemAvailable, stackAvailable, traceDemand)
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

    private static Map<IngredientKey, PlanResponse.Availability> buildTraceNetMaterials(
            Map<Item, ? extends Number> neededCounts,
            Map<Item, Ingredient> itemSources,
            Map<Item, ? extends Number> itemAvailable,
            Map<StackKey, ? extends Number> stackAvailable,
            ItemStack targetOutput,
            List<PlanStep> steps,
            Map<IngredientKey, Long> gross) {
        Map<IngredientKey, PlanResponse.Availability> materials = buildNetMaterials(
                neededCounts, itemSources, itemAvailable, stackAvailable);
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

    private static <K> long countOf(Map<K, ? extends Number> counts, K key) {
        Number count = counts.get(key);
        return count == null ? 0L : count.longValue();
    }

    private static long countAvailable(Map<Item, ? extends Number> counts, Item item) {
        return countOf(counts, item);
    }

    /** 每个已知操作只计一次；同一操作的催化剂槽相加，不同操作只需保留峰值。 */
    private static Map<IngredientKey, Long> traceDemand(List<PlanStep> steps) {
        Map<IngredientKey, Long> consumed = new LinkedHashMap<>();
        Map<IngredientKey, Long> peak = new LinkedHashMap<>();
        for (PlanStep step : steps) {
            Map<IngredientKey, Long> reusable = new LinkedHashMap<>();
            if (step.traceInputs() != null) {
                for (var input : step.traceInputs()) {
                    mergeDemand(input.role() == DemandRole.CATALYST ? reusable : consumed,
                            input.material(), input.quantity());
                }
            } else {
                for (int slot = 0; slot < step.inputs().size(); slot++) {
                    mergeDemand(step.inputRole(slot) == DemandRole.CATALYST ? reusable : consumed,
                            step.inputs().get(slot), step.totalInputCount(slot, step.batches()));
                }
            }
            reusable.forEach((key, quantity) -> peak.merge(key, quantity, Math::max));
        }
        peak.forEach((key, quantity) -> consumed.merge(key, quantity, SaturatingLongMath::add));
        return consumed;
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
            Map<StackKey, ? extends Number> stackAvailable) {
        Map<IngredientKey, Long> initial = new LinkedHashMap<>();
        Map<IngredientKey, Long> unresolved = new LinkedHashMap<>();
        graph.visitInitialAllocations((stack, count) -> mergeDemand(initial, stack, count));
        graph.visitUnresolvedDemand((stack, count) -> mergeDemand(unresolved, stack, count));

        Map<IngredientKey, PlanResponse.Availability> result = new LinkedHashMap<>();
        initial.forEach((key, count) -> {
            long available = countOf(stackAvailable, StackKey.of(key.stack(1), true));
            result.put(key, new PlanResponse.Availability(count, available));
        });
        for (Map.Entry<IngredientKey, Long> entry : unresolved.entrySet()) {
            IngredientKey key = entry.getKey();
            long quantity = entry.getValue();
            PlanResponse.Availability allocated = result.get(key);
            // 未分配需求不能被同名库存冲销，只能在新规划中重新匹配。
            result.put(key, allocated == null
                    ? new PlanResponse.Availability(quantity,
                            countOf(stackAvailable, StackKey.of(key.stack(1), true)), quantity)
                    : new PlanResponse.Availability(SaturatingLongMath.add(allocated.needed(), quantity),
                            allocated.available(), SaturatingLongMath.add(allocated.missingCount(), quantity)));
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
            Map<IngredientKey, Long> grossDemand) {

        Map<IngredientKey, PlanResponse.Availability> displayMaterials = new LinkedHashMap<>();
        for (Map.Entry<IngredientKey, Long> entry : grossDemand.entrySet()) {
            IngredientKey key = entry.getKey();
            ItemStack display = key.stack(1);
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
                have = display.hasTag() ? countExactStack(display, stackAvailable)
                        : countNbtMatching(source, stackAvailable);
            } else {
                have = display.hasTag()
                        ? countExactStack(display, stackAvailable)
                        : countAvailable(itemAvailable, key.item());
            }
            PlanResponse.Availability availability =
                    new PlanResponse.Availability(entry.getValue(), have);
            displayMaterials.merge(materialKey, availability, (left, right) ->
                    new PlanResponse.Availability(SaturatingLongMath.add(left.needed(), right.needed()),
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
