package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.MaterialMatcher;
import com.huanghuang.rsintegration.crafting.MaterialVariantPreferences;
import com.huanghuang.rsintegration.crafting.RecipeIndex;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.quantity.SaturatingLongMath;
import com.huanghuang.rsintegration.mods.ironsspellbooks.IronSpellBooksRecipeCatalog;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.util.ModIds;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

/** 配方树/候选步骤的材料展示选择与替代配方补齐。 */
final class CraftDisplayMaterials {
    private CraftDisplayMaterials() {}

    static ItemStack matchBestAvailable(Ingredient ingredient, Map<Item, Long> itemAvailable) {
        return matchBestAvailable(ingredient, itemAvailable, Collections.emptySet());
    }

    static ItemStack matchBestAvailable(Ingredient ingredient, Map<Item, Long> itemAvailable,
                                        Set<Item> preferred) {
        ItemStack best = null;
        long bestCount = -1L;
        int bestPreference = Integer.MAX_VALUE;
        var variantPreferences = MaterialVariantPreferences.snapshot();
        for (ItemStack stack : ingredient.getItems()) {
            if (stack.isEmpty()) continue;
            long count = itemAvailable.getOrDefault(stack.getItem(), 0L);
            int preference = variantPreferences.rank(stack.getItem());
            boolean betterStockState = (count > 0) != (bestCount > 0) && count > 0;
            if (best == null || betterStockState
                    || (count > 0 && bestCount > 0 && preference < bestPreference)
                    || (preference == bestPreference && count > bestCount)) {
                bestCount = count;
                bestPreference = preference;
                best = stack;
            }
        }
        if (best != null && bestCount > 0) return cleanDisplayNbt(best.copy());
        if (!preferred.isEmpty()) {
            for (ItemStack stack : ingredient.getItems()) {
                if (!stack.isEmpty() && preferred.contains(stack.getItem())) {
                    return cleanDisplayNbt(stack.copy());
                }
            }
        }
        if (best != null) return cleanDisplayNbt(best.copy());
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return cleanDisplayNbt(stack.copy());
        }
        return null;
    }

    static ItemStack firstValidDisplayItem(Ingredient ingredient) {
        for (ItemStack stack : ingredient.getItems()) {
            if (!stack.isEmpty()) return stack.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    static ItemStack matchAndConsume(Ingredient ingredient, Map<Item, Long> available) {
        return matchAndConsume(ingredient, available, Collections.emptySet());
    }

    static ItemStack matchAndConsume(Ingredient ingredient, Map<Item, Long> available,
                                     Set<Item> preferred) {
        ItemStack matched = matchBestAvailable(ingredient, available, preferred);
        if (matched != null) {
            available.merge(matched.getItem(), -1L, SaturatingLongMath::add);
        }
        return matched;
    }

    static List<ResolutionStep> attachIndexedAlternatives(
            List<ResolutionStep> steps,
            Map<Item, List<RecipeIndex.Entry>> recipeIndex,
            RegistryAccess access) {
        if (steps.isEmpty() || recipeIndex.isEmpty()) return steps;

        Map<ResourceLocation, RecipeIndex.Entry> entriesById = new LinkedHashMap<>();
        for (List<RecipeIndex.Entry> entries : recipeIndex.values()) {
            for (RecipeIndex.Entry entry : entries) {
                entriesById.putIfAbsent(entry.recipe().getId(), entry);
            }
        }

        List<ResolutionStep> enriched = new ArrayList<>(steps.size());
        for (ResolutionStep step : steps) {
            RecipeIndex.Entry primary = entriesById.get(step.recipeId());
            if (primary == null) {
                enriched.add(step);
                continue;
            }
            ItemStack output = ModRecipeHandlers.tryGetResultItem(primary.recipe(), access);
            List<RecipeIndex.Entry> sameItem = output.isEmpty()
                    ? null : recipeIndex.get(output.getItem());
            if (sameItem == null || sameItem.size() < 2) {
                enriched.add(step);
                continue;
            }

            LinkedHashMap<ResourceLocation, String> alternatives = new LinkedHashMap<>();
            for (int i = 0; i < step.alternativeIds().size(); i++) {
                ResourceLocation altId = step.alternativeIds().get(i);
                if (altId.equals(step.recipeId())) continue;
                String altMod = i < step.alternativeModTypes().size()
                        ? step.alternativeModTypes().get(i) : step.modType().id();
                alternatives.putIfAbsent(altId, altMod);
            }
            for (RecipeIndex.Entry candidate : sameItem) {
                ResourceLocation candidateId = candidate.recipe().getId();
                if (candidateId.equals(step.recipeId())) continue;
                ItemStack candidateOutput = ModRecipeHandlers.tryGetResultItem(
                        candidate.recipe(), access);
                if (!sameRecipeOutput(output, candidateOutput)) continue;
                alternatives.putIfAbsent(candidateId, candidate.modType().id());
            }
            if (alternatives.isEmpty()) {
                enriched.add(step);
                continue;
            }
            enriched.add(new ResolutionStep(
                    step.recipeId(), step.modType(), step.recipeTypeId(),
                    List.copyOf(alternatives.keySet()), List.copyOf(alternatives.values()),
                    step.inferMode(), step.executions(), step.syntheticInput(),
                    step.syntheticOutput(), step.productionTarget(), step.magicCosts()));
        }
        return List.copyOf(enriched);
    }

    static boolean sameRecipeOutput(ItemStack left, ItemStack right) {
        return MaterialMatcher.equivalentRuntimeFragment(left, right)
                || (ModList.get().isLoaded(ModIds.IRONS_SPELLBOOKS)
                && IronSpellBooksRecipeCatalog.sameSpellScroll(left, right));
    }

    /** 给实际出现的材料提供候选，库存充足和缺料叶节点也能显式改选生产配方。 */
    static PlanGraphView attachMaterialRecipeOptions(
            PlanGraphView graph, Map<Item, List<RecipeIndex.Entry>> recipeIndex,
            RegistryAccess access, BiPredicate<Recipe<?>, ModType> available) {
        Set<IngredientKey> materials = new LinkedHashSet<>();
        graph.roots().forEach(root -> {
            materials.add(IngredientKey.of(root.display()));
            root.allocations().forEach(edge -> materials.add(IngredientKey.of(edge.material())));
        });
        graph.edges().forEach(edge -> materials.add(IngredientKey.of(edge.material())));
        graph.unresolved().forEach(demand -> materials.add(IngredientKey.of(demand.display())));
        Map<IngredientKey, List<PlanGraphView.RecipeOption>> choices = new LinkedHashMap<>();
        for (IngredientKey material : materials) {
            Map<ResourceLocation, PlanGraphView.RecipeOption> options = new LinkedHashMap<>();
            for (RecipeIndex.Entry entry : recipeIndex.getOrDefault(material.item(), List.of())) {
                ItemStack output = ModRecipeHandlers.tryGetResultItem(entry.recipe(), access);
                if (!sameRecipeOutput(material.stack(1), output)
                        || !available.test(entry.recipe(), entry.modType())) continue;
                options.putIfAbsent(entry.recipe().getId(),
                        new PlanGraphView.RecipeOption(entry.recipe().getId(), entry.modType().id()));
            }
            if (!options.isEmpty()) choices.put(material, List.copyOf(options.values()));
        }
        return graph.withRecipeOptions(choices);
    }

    private static ItemStack cleanDisplayNbt(ItemStack stack) {
        if (!stack.hasTag()) return stack;
        CompoundTag tag = stack.getTag();
        if (tag != null) {
            tag.remove("BlockEntityTag");
            tag.remove("BlockId");
            if (tag.isEmpty()) stack.setTag(null);
        }
        return stack;
    }
}
