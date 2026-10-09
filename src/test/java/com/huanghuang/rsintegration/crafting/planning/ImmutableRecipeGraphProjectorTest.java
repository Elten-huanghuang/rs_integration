package com.huanghuang.rsintegration.crafting.planning;

import com.google.gson.JsonParser;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.common.crafting.StrictNBTIngredient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ImmutableRecipeGraphProjectorTest extends BootstrapTest {
    @Test
    void projectsReusableCatalystAndPreservesItsRole() {
        IngredientRef projected = ImmutableRecipeGraphProjector.projectIngredient(
                new IngredientSpec(Ingredient.of(Items.IRON_BLOCK), 1,
                        DemandRole.CATALYST));

        assertNotNull(projected);
        assertEquals(DemandRole.CATALYST, projected.role());
        assertEquals(1, projected.count());
    }

    @Test
    void containerReturningInputProjectsAsPerExecutionConsumption() {
        IngredientRef projected = ImmutableRecipeGraphProjector.projectIngredient(
                new IngredientSpec(Ingredient.of(Items.WATER_BUCKET), 3,
                        DemandRole.CONTAINER_RETURNING));

        assertNotNull(projected);
        assertEquals(3, projected.count());
        assertEquals(DemandRole.CONTAINER_RETURNING, projected.role());
        assertEquals(new ResourceLocation("minecraft", "water_bucket"),
                projected.alternatives().get(0).itemId());
    }

    @Test
    void transformedInputRemainsTypedOnly() {
        assertNull(ImmutableRecipeGraphProjector.projectIngredient(new IngredientSpec(
                Ingredient.of(Items.SHEARS), 1, DemandRole.TRANSFORMED)));
    }

    @Test
    void containerReturningRecipeStaysOnPureMissingMaterialRoute() {
        ResourceLocation recipeId = new ResourceLocation("test", "returned_container");
        ImmutableRecipeGraph.RecipeNode projected =
                ImmutableRecipeGraphProjector.projectRecipe(
                        recipeId, new ItemStack(Items.DIAMOND),
                        java.util.List.of(new IngredientSpec(
                                Ingredient.of(Items.WATER_BUCKET), 1,
                                DemandRole.CONTAINER_RETURNING)),
                        "generic", new ResourceLocation("minecraft", "crafting"));

        assertNotNull(projected);
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(java.util.Map.of(
                projected.output(), java.util.List.of(projected)));
        PureDemandTreeInspector.Result route = PureDemandTreeInspector.inspect(
                graph, java.util.Map.of(), recipeId, 1);

        assertEquals(PureDemandTreeInspector.Status.MISSING_MATERIALS, route.status());
        assertTrue(route.pureCompatible());
        assertEquals(new ResourceLocation("minecraft", "water_bucket"),
                route.unresolved().itemId());
    }


    @Test
    void vanillaIngredientDoesNotProjectDisplayNbtOrDamageAsARequirement() {
        ItemStack damaged = new ItemStack(Items.IRON_HELMET);
        damaged.setDamageValue(37);

        IngredientRef projected = ImmutableRecipeGraphProjector.projectIngredient(
                new IngredientSpec(Ingredient.of(damaged), 1));

        assertTrue(projected.alternatives().get(0).nbt().isEmpty());
    }

    @Test
    void strictIngredientStillProjectsExactNbt() {
        ItemStack damaged = new ItemStack(Items.IRON_HELMET);
        damaged.setDamageValue(37);

        IngredientRef projected = ImmutableRecipeGraphProjector.projectIngredient(
                new IngredientSpec(StrictNBTIngredient.of(damaged), 1));

        assertEquals(damaged.getTag().toString(), projected.alternatives().get(0).nbt());
        assertEquals(NbtMatchMode.EXACT, projected.nbtMatchMode());
    }

    @Test
    void craftTweakerPartialTagAcceptsSupersetNbtWithoutAcceptingWrongValue() {
        assertTrue(ImmutableRecipeGraphProjector.isPartialNbtIngredientClass(
                "com.blamejared.crafttweaker.api.ingredient.type.IngredientPartialTag"));
        IngredientRef projected = new IngredientRef(
                java.util.List.of(material("{level:1}")), 1, NbtMatchMode.PARTIAL);
        assertEquals(NbtMatchMode.PARTIAL, projected.nbtMatchMode());

        MaterialRef superset = material("{level:1,owner:\"player\"}");
        MaterialRef wrong = material("{level:2,owner:\"player\"}");
        IngredientRef bound = ImmutableRecipeGraphProjector.bindIngredient(
                projected, java.util.Map.of(superset, 1, wrong, 1));

        assertTrue(bound.alternatives().contains(superset));
        assertFalse(bound.alternatives().contains(wrong));
    }

    @Test
    void duplicateAlternativesDoNotHideAReallyAddedInventoryVariant() {
        MaterialRef expected = material("{level:1}");
        MaterialRef actual = material("{level:1,owner:\"player\"}");
        IngredientRef input = new IngredientRef(java.util.List.of(expected, expected),
                1, NbtMatchMode.PARTIAL);

        IngredientRef bound = ImmutableRecipeGraphProjector.bindIngredient(
                input, java.util.Map.of(actual, 1));

        assertEquals(java.util.List.of(expected, actual), bound.alternatives());
    }

    @Test
    void findsPartialTagInsideCraftTweakerWrapperJson() {
        assertTrue(ImmutableRecipeGraphProjector.containsPartialTagType(JsonParser.parseString("""
                {"type":"crafttweaker:transformed","ingredient":{
                  "type":"crafttweaker:conditioned","ingredient":{
                    "type":"crafttweaker:partial_tag","item":"minecraft:diamond_sword"
                  }
                }}
                """)));
        assertFalse(ImmutableRecipeGraphProjector.containsPartialTagType(JsonParser.parseString("""
                {"type":"forge:nbt","item":"minecraft:diamond_sword"}
                """)));
    }

    @Test
    void strictNbtDoesNotBindAStackWithExtraData() {
        ItemStack expected = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag expectedTag = new CompoundTag();
        expectedTag.putInt("level", 1);
        expected.setTag(expectedTag);
        IngredientRef projected = ImmutableRecipeGraphProjector.projectIngredient(
                new IngredientSpec(StrictNBTIngredient.of(expected), 1));
        MaterialRef superset = material("{level:1,owner:\"player\"}");

        IngredientRef bound = ImmutableRecipeGraphProjector.bindIngredient(
                projected, java.util.Map.of(superset, 1));

        assertFalse(bound.alternatives().contains(superset));
    }

    @Test
    void partialNbtBindingMakesTheBackgroundPlanFeasible() {
        MaterialRef output = new MaterialRef(
                new ResourceLocation("minecraft", "nether_star"), "");
        IngredientRef partial = new IngredientRef(
                java.util.List.of(material("{level:1}")), 1, NbtMatchMode.PARTIAL);
        ResourceLocation recipeId = new ResourceLocation("test", "partial_nbt");
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(java.util.Map.of(
                output, java.util.List.of(new ImmutableRecipeGraph.RecipeNode(
                        recipeId, output, 1, java.util.List.of(partial)))));
        MaterialRef actual = material("{level:1,owner:\"player\"}");
        java.util.Map<MaterialRef, Integer> stock = java.util.Map.of(actual, 1);

        ImmutableRecipeGraph bound = ImmutableRecipeGraphProjector.bindAvailability(graph, stock);
        PureRecipePlanner.Result result = PureRecipePlanner.resolve(bound, stock,
                bound.recipesById().get(recipeId).inputs(), 8);

        assertTrue(result.feasible());
    }

    @Test
    void taggedAvailabilityIsNotDuplicatedAsTaglessStock() {
        com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey tagged =
                new com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey(
                        Items.DIAMOND_SWORD, "{level:1}");

        java.util.Map<MaterialRef, Integer> projected =
                ImmutableRecipeGraphProjector.projectAvailability(java.util.Map.of(tagged, 1));

        assertEquals(1, projected.size());
        assertEquals(1, projected.get(material("{level:1}")));
    }

    @Test
    void genericProjectionCarriesTypedExecutionMetadata() {
        ResourceLocation recipeId = new ResourceLocation("test", "smithing_intermediate");
        ResourceLocation recipeTypeId = new ResourceLocation("minecraft", "smithing");

        ImmutableRecipeGraph.RecipeNode projected =
                ImmutableRecipeGraphProjector.projectRecipe(
                        recipeId, new ItemStack(Items.DIAMOND),
                        java.util.List.of(new IngredientSpec(
                                Ingredient.of(Items.IRON_INGOT), 1)),
                        "smithing", recipeTypeId);

        assertEquals("smithing", projected.modTypeId());
        assertEquals(recipeTypeId, projected.recipeTypeId());
        assertEquals(NbtMatchMode.ANY, projected.inputs().get(0).nbtMatchMode());
    }

    @Test
    void publishedDependencyGraphsAndIndexesAreReusedOnlyWithinOneGeneration() {
        ResourceLocation rawId = new ResourceLocation("test", "raw");
        ResourceLocation middleId = new ResourceLocation("test", "middle");
        ResourceLocation firstOutputId = new ResourceLocation("test", "first_output");
        ResourceLocation secondOutputId = new ResourceLocation("test", "second_output");
        MaterialRef raw = new MaterialRef(rawId, "");
        MaterialRef middle = new MaterialRef(middleId, "");
        MaterialRef firstOutput = new MaterialRef(firstOutputId, "");
        MaterialRef secondOutput = new MaterialRef(secondOutputId, "");
        ResourceLocation middleRecipeId = new ResourceLocation("test", "make_middle");
        ResourceLocation firstRecipeId = new ResourceLocation("test", "make_first");
        ResourceLocation secondRecipeId = new ResourceLocation("test", "make_second");
        ImmutableRecipeGraph.RecipeNode middleRecipe = new ImmutableRecipeGraph.RecipeNode(
                middleRecipeId, middle, 1, java.util.List.of(any(raw)));
        ImmutableRecipeGraph.RecipeNode firstRecipe = new ImmutableRecipeGraph.RecipeNode(
                firstRecipeId, firstOutput, 1, java.util.List.of(any(middle)));
        ImmutableRecipeGraph.RecipeNode secondRecipe = new ImmutableRecipeGraph.RecipeNode(
                secondRecipeId, secondOutput, 1, java.util.List.of(any(raw)));
        ImmutableRecipeGraph firstGeneration = new ImmutableRecipeGraph(java.util.Map.of(
                middle, java.util.List.of(middleRecipe),
                firstOutput, java.util.List.of(firstRecipe),
                secondOutput, java.util.List.of(secondRecipe)));

        try {
            ImmutableRecipeGraphProjector.publishCompiled(null, 1L, firstGeneration, 0L);
            ImmutableRecipeGraph firstScoped = ImmutableRecipeGraphProjector.restrictToDependencies(
                    firstGeneration, firstRecipeId);
            assertSame(firstScoped, ImmutableRecipeGraphProjector.restrictToDependencies(
                    firstGeneration, firstRecipeId));
            assertNotSame(firstScoped, ImmutableRecipeGraphProjector.restrictToDependencies(
                    firstGeneration, secondRecipeId));

            PlanningLookupCache.run(() -> {
                assertEquals(java.util.List.of(firstRecipe),
                        PlanningLookupCache.producers(firstScoped, firstOutputId));
                assertEquals(java.util.List.of(firstOutput),
                        PlanningLookupCache.outputVariants(firstScoped, firstOutputId));
                assertEquals(0, PlanningLookupCache.currentStats().outputIndexBuilds());
                return null;
            });

            ImmutableRecipeGraph secondGeneration = new ImmutableRecipeGraph(java.util.Map.of(
                    firstOutput, java.util.List.of(firstRecipe)));
            ImmutableRecipeGraphProjector.publishCompiled(null, 2L, secondGeneration, 0L);
            ImmutableRecipeGraph replacementScoped =
                    ImmutableRecipeGraphProjector.restrictToDependencies(
                            secondGeneration, firstRecipeId);
            assertNotSame(firstScoped, replacementScoped);
            assertSame(replacementScoped, ImmutableRecipeGraphProjector.restrictToDependencies(
                    secondGeneration, firstRecipeId));
        } finally {
            ImmutableRecipeGraphProjector.clearCache();
        }
    }

    private static IngredientRef any(MaterialRef material) {
        return new IngredientRef(java.util.List.of(material), 1, NbtMatchMode.ANY);
    }

    private static MaterialRef material(String nbt) {
        return new MaterialRef(new ResourceLocation("minecraft", "diamond_sword"), nbt);
    }
}
