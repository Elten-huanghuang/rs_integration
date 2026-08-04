package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PureRecipePlannerPropertyTest {
    private static final int CASES = 200;

    @Test
    void randomizedSmallDagMatchesIndependentBreadthFirstReference() {
        Random random = new Random(0x525349504C414EL);
        for (int caseIndex = 0; caseIndex < CASES; caseIndex++) {
            GeneratedCase generated = generate(random, caseIndex);
            PureRecipePlanner.Result actual = PureRecipePlanner.resolve(
                    generated.graph(), generated.available(), generated.roots(),
                    generated.maxSteps(), 200_000, 16_384);
            boolean expected = referenceFeasible(generated);

            assertEquals(expected, actual.feasible(), "case " + caseIndex);
            assertTrue(actual.expandedStates() <= 200_001, "case " + caseIndex);
            if (actual.feasible()) {
                assertTrue(actual.steps().size() <= generated.maxSteps(), "case " + caseIndex);
                assertTrue(canReplayToRemaining(generated, actual),
                        "material conservation failed for case " + caseIndex);
            }
        }
    }

    @Test
    void veryDeepAcyclicChainStopsBeforeOverflowingTheJavaStack() {
        int length = 1_200;
        List<MaterialRef> materials = new ArrayList<>();
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int i = 0; i < length; i++) materials.add(material("deep_" + i));
        for (int i = 1; i < length; i++) {
            MaterialRef output = materials.get(i);
            recipes.put(output, List.of(new RecipeNode(id("deep_recipe_" + i), output, 1,
                    List.of(new IngredientRef(List.of(materials.get(i - 1)), 1)))));
        }

        PureRecipePlanner.Result result = PureRecipePlanner.resolve(
                new ImmutableRecipeGraph(recipes), Map.of(),
                List.of(new IngredientRef(List.of(materials.get(length - 1)), 1)),
                4_096, 100_000, 8_192);

        assertEquals(PureRecipePlanner.Status.SEARCH_LIMIT, result.status());
        assertTrue(result.steps().isEmpty());
    }

    private static GeneratedCase generate(Random random, int caseIndex) {
        int materialCount = 4 + random.nextInt(3);
        List<MaterialRef> materials = new ArrayList<>();
        for (int i = 0; i < materialCount; i++) {
            materials.add(material("case_" + caseIndex + "_material_" + i));
        }

        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        int recipeIndex = 0;
        for (int outputIndex = 1; outputIndex < materialCount; outputIndex++) {
            int candidateCount = random.nextInt(3);
            List<RecipeNode> candidates = new ArrayList<>();
            for (int candidateIndex = 0; candidateIndex < candidateCount; candidateIndex++) {
                int inputCount = 1 + random.nextInt(Math.min(2, outputIndex));
                List<IngredientRef> inputs = new ArrayList<>();
                for (int inputIndex = 0; inputIndex < inputCount; inputIndex++) {
                    int first = random.nextInt(outputIndex);
                    List<MaterialRef> alternatives = new ArrayList<>();
                    alternatives.add(materials.get(first));
                    if (outputIndex > 1 && random.nextBoolean()) {
                        MaterialRef second = materials.get(random.nextInt(outputIndex));
                        if (!alternatives.contains(second)) alternatives.add(second);
                    }
                    inputs.add(new IngredientRef(alternatives, 1 + random.nextInt(2)));
                }
                candidates.add(new RecipeNode(id("case_" + caseIndex + "_recipe_" + recipeIndex++),
                        materials.get(outputIndex), 1 + random.nextInt(2), inputs));
            }
            if (!candidates.isEmpty()) recipes.put(materials.get(outputIndex), candidates);
        }

        Map<MaterialRef, Integer> available = new HashMap<>();
        for (MaterialRef material : materials) {
            int count = random.nextInt(3);
            if (count > 0) available.put(material, count);
        }
        List<IngredientRef> roots = new ArrayList<>();
        int rootCount = 1 + random.nextInt(3);
        for (int i = 0; i < rootCount; i++) {
            MaterialRef first = materials.get(random.nextInt(materialCount));
            List<MaterialRef> alternatives = new ArrayList<>(List.of(first));
            if (random.nextBoolean()) {
                MaterialRef second = materials.get(random.nextInt(materialCount));
                if (!alternatives.contains(second)) alternatives.add(second);
            }
            roots.add(new IngredientRef(alternatives, 1 + random.nextInt(2)));
        }
        return new GeneratedCase(new ImmutableRecipeGraph(recipes), Map.copyOf(available),
                List.copyOf(roots), 6);
    }

    private static boolean referenceFeasible(GeneratedCase generated) {
        ArrayDeque<ReferenceState> queue = new ArrayDeque<>();
        List<ReferenceTask> roots = generated.roots().stream()
                .map(ReferenceNeed::new).map(ReferenceTask.class::cast).toList();
        queue.add(new ReferenceState(generated.available(), roots, 0));
        Set<ReferenceState> visited = new HashSet<>();
        int expanded = 0;
        while (!queue.isEmpty() && expanded++ < 500_000) {
            ReferenceState state = queue.removeFirst();
            if (!visited.add(state)) continue;
            if (state.pending().isEmpty()) return true;
            ReferenceTask current = state.pending().get(0);
            List<ReferenceTask> rest = state.pending().subList(1, state.pending().size());
            if (current instanceof ReferenceNeed need) {
                enqueueDirectChoices(queue, state.stock(), need.ingredient(), rest, state.steps());
                for (MaterialRef wanted : need.ingredient().alternatives()) {
                    int have = state.stock().getOrDefault(wanted, 0);
                    int missing = need.ingredient().count() - have;
                    if (missing <= 0) continue;
                    for (RecipeNode recipe : generated.graph().recipesByOutput()
                            .getOrDefault(wanted, List.of())) {
                        if (state.steps() + scheduled(rest) >= generated.maxSteps()) continue;
                        int batches = (missing + recipe.outputCount() - 1) / recipe.outputCount();
                        List<ReferenceTask> pending = new ArrayList<>();
                        for (IngredientRef input : recipe.inputs()) {
                            pending.add(new ReferenceNeed(new IngredientRef(input.alternatives(),
                                    input.count() * batches)));
                        }
                        pending.add(new ReferenceProduce(recipe.output(), recipe.outputCount(),
                                need.ingredient().count(), batches));
                        pending.addAll(rest);
                        queue.addLast(new ReferenceState(state.stock(), List.copyOf(pending), state.steps()));
                    }
                }
            } else {
                ReferenceProduce produce = (ReferenceProduce) current;
                int before = state.stock().getOrDefault(produce.output(), 0);
                int after = before + produce.outputCount() * produce.batches() - produce.consumeCount();
                if (after >= 0) {
                    queue.addLast(new ReferenceState(withCount(state.stock(), produce.output(), after),
                            List.copyOf(rest), state.steps() + 1));
                }
            }
        }
        return false;
    }

    private static boolean canReplayToRemaining(GeneratedCase generated,
                                                PureRecipePlanner.Result result) {
        Set<Map<MaterialRef, Integer>> states = Set.of(generated.available());
        for (PureRecipePlanner.PlannedStep step : result.steps()) {
            RecipeNode recipe = generated.graph().recipesById().get(step.recipeId());
            if (recipe == null) return false;
            for (IngredientRef input : recipe.inputs()) {
                states = consume(states, new IngredientRef(input.alternatives(),
                        input.count() * step.batches()));
            }
            Set<Map<MaterialRef, Integer>> produced = new HashSet<>();
            for (Map<MaterialRef, Integer> state : states) {
                int count = state.getOrDefault(recipe.output(), 0)
                        + recipe.outputCount() * step.batches();
                produced.add(withCount(state, recipe.output(), count));
            }
            states = produced;
        }
        for (IngredientRef root : generated.roots()) states = consume(states, root);
        return states.contains(result.remaining());
    }

    private static Set<Map<MaterialRef, Integer>> consume(Set<Map<MaterialRef, Integer>> states,
                                                           IngredientRef ingredient) {
        Set<Map<MaterialRef, Integer>> consumed = new HashSet<>();
        for (Map<MaterialRef, Integer> state : states) {
            consumeCombinations(state, ingredient.alternatives(), 0,
                    ingredient.count(), consumed);
        }
        return consumed;
    }

    private static void enqueueDirectChoices(ArrayDeque<ReferenceState> queue,
                                             Map<MaterialRef, Integer> stock,
                                             IngredientRef ingredient,
                                             List<ReferenceTask> rest, int steps) {
        Set<Map<MaterialRef, Integer>> consumed = new HashSet<>();
        consumeCombinations(stock, ingredient.alternatives(), 0,
                ingredient.count(), consumed);
        for (Map<MaterialRef, Integer> result : consumed) {
            queue.addLast(new ReferenceState(result, List.copyOf(rest), steps));
        }
    }

    private static void consumeCombinations(Map<MaterialRef, Integer> stock,
                                            List<MaterialRef> alternatives, int index,
                                            int remaining,
                                            Set<Map<MaterialRef, Integer>> results) {
        if (remaining == 0) {
            results.add(Map.copyOf(stock));
            return;
        }
        if (index >= alternatives.size()) return;

        MaterialRef material = alternatives.get(index);
        int have = stock.getOrDefault(material, 0);
        for (int take = 0; take <= Math.min(have, remaining); take++) {
            consumeCombinations(take == 0 ? stock : withCount(stock, material, have - take),
                    alternatives, index + 1, remaining - take, results);
        }
    }

    private static int scheduled(List<ReferenceTask> tasks) {
        int count = 0;
        for (ReferenceTask task : tasks) if (task instanceof ReferenceProduce) count++;
        return count;
    }

    private static Map<MaterialRef, Integer> withCount(Map<MaterialRef, Integer> stock,
                                                        MaterialRef material, int count) {
        Map<MaterialRef, Integer> copy = new HashMap<>(stock);
        if (count <= 0) copy.remove(material);
        else copy.put(material, count);
        return Map.copyOf(copy);
    }

    private sealed interface ReferenceTask permits ReferenceNeed, ReferenceProduce {}
    private record ReferenceNeed(IngredientRef ingredient) implements ReferenceTask {}
    private record ReferenceProduce(MaterialRef output, int outputCount,
                                    int consumeCount, int batches) implements ReferenceTask {}
    private record ReferenceState(Map<MaterialRef, Integer> stock,
                                  List<ReferenceTask> pending, int steps) {
        private ReferenceState {
            stock = Map.copyOf(stock);
            pending = List.copyOf(pending);
        }
    }
    private record GeneratedCase(ImmutableRecipeGraph graph,
                                 Map<MaterialRef, Integer> available,
                                 List<IngredientRef> roots, int maxSteps) {}

    private static MaterialRef material(String path) {
        return new MaterialRef(id(path), "");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
