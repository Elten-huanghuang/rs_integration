package com.huanghuang.rsintegration.mods.embers;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraphProjector;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.rekindled.embers.recipe.FluidIngredient;
import com.rekindled.embers.recipe.StampingRecipe;
import com.rekindled.embers.util.Misc;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class EmbersStampingPlanningTest extends BootstrapTest {
    private static final TagKey<Item> LEAD = TagKey.create(Registries.ITEM,
            new ResourceLocation("forge:ingots/lead"));
    private final EmbersStampingRecipeHandler handler = new EmbersStampingRecipeHandler();

    private StampingRecipe recipe(int count) {
        return new StampingRecipe(new ResourceLocation("embers:stamping/ingots/lead"),
                Ingredient.of(Items.BRICK), Ingredient.EMPTY,
                FluidIngredient.of(Fluids.WATER, 90), new StampingRecipe.TagAmount(LEAD, count));
    }

    @Test
    void tagOutputProjectsOnlyTheItemActuallySelectedByEmbers() {
        try (var selected = mockStatic(Misc.class); var tokens = EmbersBoilingRecipeHandlerTest.tokens()) {
            selected.when(() -> Misc.getTaggedItem(LEAD)).thenReturn(Items.IRON_INGOT);
            StampingRecipe recipe = recipe(3);
            assertTrue(handler.supportsBackgroundPlanning(recipe));
            assertTrue(handler.supportsIntermediateProjection(recipe));
            ItemStack output = handler.getResultItem(recipe, RegistryAccess.EMPTY);
            assertTrue(ItemStack.matches(output, recipe.getOutput(null)));
            RecipeNode node = project(recipe);
            assertNotNull(node);
            assertEquals(new ResourceLocation("minecraft:iron_ingot"), node.output().itemId());
            assertEquals(3, node.outputCount());
            assertEquals(90, node.inputs().get(0).count());
            assertEquals(DemandRole.CATALYST, node.inputs().get(1).role());
        }
    }

    @Test
    void emptyOutputTagDoesNotInventAProducer() {
        try (var selected = mockStatic(Misc.class)) {
            selected.when(() -> Misc.getTaggedItem(LEAD)).thenReturn(Items.AIR);
            assertFalse(handler.supportsIntermediateProjection(recipe(1)));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recursiveNecklacePreviewKeepsMeltingAndStampingEvenWithAnotherRawShortage(boolean missingOther) {
        // 使用原生标签输出配方；普通物品代替测试环境未注册的模组物品。
        try (var selected = mockStatic(Misc.class); var tokens = EmbersBoilingRecipeHandlerTest.tokens()) {
            selected.when(() -> Misc.getTaggedItem(LEAD)).thenReturn(Items.IRON_INGOT);
            StampingRecipe recipe = recipe(1);
            assertTrue(handler.supportsIntermediateProjection(recipe));
            RecipeNode stamp = project(recipe);
            MaterialRef fluid = stamp.inputs().get(0).alternatives().get(0);
            MaterialRef ore = material("iron_ore");
            MaterialRef other = material("echo_shard");
            RecipeNode melting = new RecipeNode(new ResourceLocation("embers:melting/ores/lead_silver"),
                    fluid, 240, List.of(new IngredientRef(List.of(ore), 1)));
            ImmutableRecipeGraph graph = new ImmutableRecipeGraph(Map.of(
                    stamp.output(), List.of(stamp), fluid, List.of(melting)));
            Map<MaterialRef, Long> stock = missingOther
                    ? Map.of(ore, 1L, material("brick"), 1L)
                    : Map.of(ore, 1L, material("brick"), 1L, other, 1L);
            var plan = PureRecipePlanner.resolve(graph, stock, List.of(
                    new IngredientRef(List.of(other), 1),
                    new IngredientRef(List.of(stamp.output()), 2)), 100);
            assertEquals(!missingOther, plan.feasible());
            assertTrue(plan.steps().stream().anyMatch(step -> step.recipeId().equals(melting.recipeId())));
            assertEquals(2, plan.steps().stream().filter(step -> step.recipeId().equals(stamp.recipeId()))
                    .mapToLong(PureRecipePlanner.PlannedStep::batches).sum());
            assertTrue(plan.missing().stream().noneMatch(input -> input.alternatives().contains(stamp.output())));
            if (missingOther) assertTrue(plan.missing().stream()
                    .anyMatch(input -> input.alternatives().contains(other)));
        }
    }

    private RecipeNode project(StampingRecipe recipe) {
        return ImmutableRecipeGraphProjector.projectRecipe(recipe.getId(),
                handler.getResultItem(recipe, RegistryAccess.EMPTY), handler.getIngredients(recipe),
                "embers_stamper", new ResourceLocation("embers:stamping"));
    }

    private static MaterialRef material(String name) {
        return new MaterialRef(new ResourceLocation("minecraft", name), "");
    }
}
