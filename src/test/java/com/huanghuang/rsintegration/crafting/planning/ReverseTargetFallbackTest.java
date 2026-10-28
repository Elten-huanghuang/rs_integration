package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.SelfAmplifyingRecipePolicy;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反向换算目标（如「分解压缩萤石」）不该把终端计划拉进
 * 「9 个萤石压成 1 个压缩萤石、再分解回 9 个萤石」的闭环。
 */
class ReverseTargetFallbackTest extends BootstrapTest {

    private static final MaterialRef DUST = new MaterialRef(
            new ResourceLocation("minecraft", "glowstone_dust"), "");
    private static final MaterialRef GLOWSTONE = new MaterialRef(
            new ResourceLocation("minecraft", "glowstone"), "");
    private static final MaterialRef COMPRESSED = new MaterialRef(
            new ResourceLocation("prefab", "compressed_glowstone"), "");

    private static final ResourceLocation FOUR_TO_ONE =
            new ResourceLocation("minecraft", "glowstone");
    private static final ResourceLocation COMPRESS =
            new ResourceLocation("prefab", "compressed_glowstone");
    private static final ResourceLocation DECOMPRESS =
            new ResourceLocation("prefab", "compressed_glowstone_decompress");

    private static final PlanningPreferences NO_PREFERENCES =
            new PlanningPreferences(Map.of(), Map.of(), Set.of());

    private static ImmutableRecipeGraph graph() {
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        recipes.put(GLOWSTONE, List.of(
                new RecipeNode(FOUR_TO_ONE, GLOWSTONE, 1,
                        List.of(new IngredientRef(List.of(DUST), 4))),
                new RecipeNode(DECOMPRESS, GLOWSTONE, 9,
                        List.of(new IngredientRef(List.of(COMPRESSED), 1)))));
        recipes.put(COMPRESSED, List.of(new RecipeNode(COMPRESS, COMPRESSED, 1,
                List.of(new IngredientRef(List.of(GLOWSTONE), 9)))));
        return new ImmutableRecipeGraph(recipes);
    }

    private static RecipeNode target(ResourceLocation recipeId) {
        RecipeNode node = graph().recipesById().get(recipeId);
        assertTrue(node != null, "配方应当存在: " + recipeId);
        return node;
    }

    private static PureRecipePlanner.Result plan(ResourceLocation recipeId,
                                                 Map<MaterialRef, Long> stock,
                                                 int repeatCount) {
        return AsyncPurePlanningService.resolveInScope(graph(), stock,
                target(recipeId), repeatCount, 200, 20_000, 5_000,
                Long.MAX_VALUE, Map.of(), NO_PREFERENCES);
    }

    @Test
    void decompressionTargetFallsBackToDirectRecipe() {
        PureRecipePlanner.Result result = plan(DECOMPRESS, Map.of(DUST, 64L), 1);

        assertEquals(PureRecipePlanner.Feasibility.FEASIBLE, result.feasibility());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(FOUR_TO_ONE, 9)), result.steps());
        assertEquals(28L, result.remaining().get(DUST));
        assertTrue(result.missing().isEmpty());
    }

    @Test
    void decompressionTargetReportsMissingDustInsteadOfCycle() {
        PureRecipePlanner.Result result = plan(DECOMPRESS, Map.of(), 1);

        // 退化后按“9 个萤石”的需求规划：给出 4 合 1 的尽力步骤，并直接指出缺萤石粉，
        // 而不是像闭环那样只要求 1 个压缩萤石。
        assertEquals(PureRecipePlanner.Feasibility.INFEASIBLE, result.feasibility());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(FOUR_TO_ONE, 9)), result.steps());
        assertEquals(1, result.missing().size());
        assertEquals(List.of(DUST), result.missing().get(0).alternatives());
        assertEquals(36L, result.missing().get(0).count());
    }

    @Test
    void decompressionTargetHonoursStockedCompressedInput() {
        PureRecipePlanner.Result result = plan(DECOMPRESS, Map.of(COMPRESSED, 1L), 1);

        // 压缩萤石有库存时不得退化成 4 合 1：输入已满足，不需要生产步骤。
        assertTrue(result.feasible());
        assertTrue(result.steps().isEmpty());
        assertEquals(0L, result.remaining().getOrDefault(COMPRESSED, 0L));
    }

    @Test
    void decompressionTargetWithRepeatsScalesDirectRecipe() {
        PureRecipePlanner.Result result = plan(DECOMPRESS, Map.of(DUST, 128L), 2);

        assertEquals(PureRecipePlanner.Feasibility.FEASIBLE, result.feasibility());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(FOUR_TO_ONE, 18)), result.steps());
        assertEquals(56L, result.remaining().get(DUST));
    }

    @Test
    void forwardTargetKeepsDirectRecipe() {
        PureRecipePlanner.Result result = plan(FOUR_TO_ONE, Map.of(DUST, 64L), 9);

        // 正向目标（4 萤石粉 → 1 萤石）本来就只消耗库存，不能被退化逻辑改写。
        assertTrue(result.feasible());
        assertTrue(result.steps().isEmpty());
        assertEquals(28L, result.remaining().get(DUST));
    }

    @Test
    void unseededReverseTargetDetection() {
        assertTrue(SelfAmplifyingRecipePolicy.isUnseededReverseTarget(
                target(DECOMPRESS), graph(), Map.of(DUST, 64L)));
        assertTrue(SelfAmplifyingRecipePolicy.isUnseededReverseTarget(
                target(DECOMPRESS), graph(), Map.of()));
        assertFalse(SelfAmplifyingRecipePolicy.isUnseededReverseTarget(
                target(DECOMPRESS), graph(), Map.of(COMPRESSED, 1L)));
        assertFalse(SelfAmplifyingRecipePolicy.isUnseededReverseTarget(
                target(FOUR_TO_ONE), graph(), Map.of(DUST, 64L)));
        assertFalse(SelfAmplifyingRecipePolicy.isUnseededReverseTarget(
                target(FOUR_TO_ONE), graph(), Map.of()));
    }
}
