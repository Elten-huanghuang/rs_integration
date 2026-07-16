package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.IngredientSpec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;

/** Splits planner-owned inputs from Embers aspect catalysts. */
public record EreAlchemyMaterials(
        List<IngredientSpec> graphSpecs,
        List<IngredientSpec> supplementalSpecs) {

    public EreAlchemyMaterials {
        graphSpecs = List.copyOf(graphSpecs);
        supplementalSpecs = List.copyOf(supplementalSpecs);
        if (graphSpecs.size() < supplementalSpecs.size()) {
            throw new IllegalArgumentException("graph specs must contain every alchemy input");
        }
    }

    public static EreAlchemyMaterials create(Ingredient tablet, List<Ingredient> aspects,
                                               List<Ingredient> inputs) {
        if (aspects.size() != inputs.size()) {
            throw new IllegalArgumentException("aspect/input count mismatch");
        }
        List<IngredientSpec> graph = new ArrayList<>(inputs.size() + (tablet != null ? 1 : 0));
        List<IngredientSpec> supplemental = new ArrayList<>(aspects.size());
        if (tablet != null) graph.add(new IngredientSpec(tablet, 1));
        for (int i = 0; i < inputs.size(); i++) {
            supplemental.add(new IngredientSpec(aspects.get(i), 1));
            graph.add(new IngredientSpec(inputs.get(i), 1));
        }
        return new EreAlchemyMaterials(graph, supplemental);
    }

    /** Full placement order: optional tablet, then (aspect, input) for each pedestal. */
    public List<IngredientSpec> allSpecs() {
        return interleave(graphSpecs, supplementalSpecs);
    }

    public List<ItemStack> mergeStacks(List<ItemStack> graphMaterials,
                                       List<ItemStack> supplementalMaterials) {
        return interleave(graphMaterials, supplementalMaterials);
    }

    private static <T> List<T> interleave(List<T> graph, List<T> supplemental) {
        int tabletOffset = graph.size() - supplemental.size();
        if (tabletOffset < 0 || tabletOffset > 1 || supplemental.size() + tabletOffset != graph.size()) {
            throw new IllegalArgumentException("invalid alchemy material split");
        }
        List<T> merged = new ArrayList<>(graph.size() + supplemental.size());
        if (tabletOffset == 1) merged.add(graph.get(0));
        for (int i = 0; i < supplemental.size(); i++) {
            merged.add(supplemental.get(i));
            merged.add(graph.get(i + tabletOffset));
        }
        return List.copyOf(merged);
    }
}
