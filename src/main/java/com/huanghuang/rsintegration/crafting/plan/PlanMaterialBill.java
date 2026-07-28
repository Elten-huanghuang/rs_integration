package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.IngredientMatcher;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeModel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;

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

    public static Result summarize(Map<Item, Integer> neededCounts,
                                   Map<Item, Ingredient> itemSources,
                                   Map<Item, Integer> itemAvailable,
                                   Map<StackKey, Integer> stackAvailable,
                                   ItemStack targetOutput,
                                   List<PlanStep> steps,
                                   int repeatCount,
                                   @Nullable PlanGraphView graph,
                                   boolean hasMissing) {
        Map<IngredientKey, PlanResponse.Availability> netMaterials = buildNetMaterials(
                neededCounts, itemSources, itemAvailable, stackAvailable);
        boolean feasible = !hasMissing
                && netMaterials.values().stream().allMatch(PlanResponse.Availability::isEnough);

        Map<IngredientKey, Integer> leftovers = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> entry : neededCounts.entrySet()) {
            if (entry.getValue() >= 0 || entry.getKey() == targetOutput.getItem()) continue;
            leftovers.put(IngredientKey.of(new ItemStack(entry.getKey())), -entry.getValue());
        }

        Map<IngredientKey, PlanResponse.Availability> displayMaterials = buildDisplayMaterials(
                netMaterials, itemAvailable, stackAvailable, targetOutput, steps, repeatCount, graph);
        return new Result(feasible, displayMaterials, leftovers);
    }

    private static Map<IngredientKey, PlanResponse.Availability> buildNetMaterials(
            Map<Item, Integer> neededCounts,
            Map<Item, Ingredient> itemSources,
            Map<Item, Integer> itemAvailable,
            Map<StackKey, Integer> stackAvailable) {
        Map<IngredientKey, PlanResponse.Availability> materials = new LinkedHashMap<>();
        Set<String> mergedTagKeys = new HashSet<>();
        for (Map.Entry<Item, Integer> entry : neededCounts.entrySet()) {
            if (entry.getValue() <= 0) continue;
            int needed = entry.getValue();
            Item displayItem = entry.getKey();
            Ingredient source = itemSources.get(displayItem);
            if (source != null && source.getItems().length > 1) {
                String tagKey = source.toJson().toString();
                if (!mergedTagKeys.add(tagKey)) continue;
                int netNeeded = 0;
                int totalHave = 0;
                for (ItemStack option : source.getItems()) {
                    if (option.isEmpty()) continue;
                    Item optionItem = option.getItem();
                    netNeeded += neededCounts.getOrDefault(optionItem, 0);
                    totalHave += itemAvailable.getOrDefault(optionItem, 0);
                }
                materials.put(IngredientKey.of(new ItemStack(displayItem)),
                        new PlanResponse.Availability(Math.max(0, netNeeded), totalHave));
                continue;
            }

            boolean nbtStrict = source != null && isNbtStrict(source);
            int have = nbtStrict
                    ? countNbtMatching(source, stackAvailable)
                    : itemAvailable.getOrDefault(displayItem, 0);
            ItemStack materialDisplay = new ItemStack(displayItem);
            if (nbtStrict && source.getItems().length > 0) {
                materialDisplay = source.getItems()[0].copyWithCount(1);
            }
            materials.put(IngredientKey.of(materialDisplay),
                    new PlanResponse.Availability(needed, have));
        }
        return materials;
    }

    private static Map<IngredientKey, PlanResponse.Availability> buildDisplayMaterials(
            Map<IngredientKey, PlanResponse.Availability> netMaterials,
            Map<Item, Integer> itemAvailable,
            Map<StackKey, Integer> stackAvailable,
            ItemStack targetOutput,
            List<PlanStep> steps,
            int repeatCount,
            @Nullable PlanGraphView graph) {
        PlanResponse treeSource = new PlanResponse(true, "", targetOutput, steps,
                Collections.emptyMap(), Collections.emptyList(), "",
                null, null, 0, 0, 0, Collections.emptyList(), repeatCount,
                null, null, null, 0, false, false, false, null,
                Collections.emptySet(), Collections.emptyMap(), null, graph);
        Map<IngredientKey, Integer> grossDemand =
                PlanTreeModel.grossDemandByKey(PlanTreeModel.from(treeSource));

        Map<IngredientKey, PlanResponse.Availability> displayMaterials = new LinkedHashMap<>();
        for (Map.Entry<IngredientKey, Integer> entry : grossDemand.entrySet()) {
            IngredientKey key = entry.getKey();
            ItemStack display = key.stack(1);
            int have = display.hasTag()
                    ? countExactStack(display, stackAvailable)
                    : itemAvailable.getOrDefault(key.item(), 0);
            displayMaterials.put(key, new PlanResponse.Availability(entry.getValue(), have));
        }
        for (Map.Entry<IngredientKey, PlanResponse.Availability> entry : netMaterials.entrySet()) {
            displayMaterials.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return displayMaterials;
    }

    private static boolean isNbtStrict(Ingredient ingredient) {
        if (ingredient instanceof StrictNBTIngredient) return true;
        ItemStack[] items = ingredient.getItems();
        if (items.length == 0) return false;
        for (ItemStack stack : items) {
            if (stack.isEmpty() || !stack.hasTag()) return false;
        }
        return true;
    }

    private static int countNbtMatching(Ingredient ingredient, Map<StackKey, Integer> available) {
        int total = 0;
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            if (IngredientMatcher.test(ingredient, entry.getKey())) total += entry.getValue();
        }
        return total;
    }

    private static int countExactStack(ItemStack expected, Map<StackKey, Integer> available) {
        int total = 0;
        for (Map.Entry<StackKey, Integer> entry : available.entrySet()) {
            if (ItemStack.isSameItemSameTags(entry.getKey().toStack(), expected)) {
                total += entry.getValue();
            }
        }
        return total;
    }

    public record Result(boolean feasible,
                         Map<IngredientKey, PlanResponse.Availability> materials,
                         Map<IngredientKey, Integer> leftovers) {
        public Result {
            materials = Collections.unmodifiableMap(new LinkedHashMap<>(materials));
            leftovers = Collections.unmodifiableMap(new LinkedHashMap<>(leftovers));
        }
    }
}
