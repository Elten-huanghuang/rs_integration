package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CandidateEngineTest extends BootstrapTest {

    @Test
    void timedOutScoringTailUsesNeutralDefaults() {
        ResourceLocation scored = new ResourceLocation("example", "scored");
        ResourceLocation timedOut = new ResourceLocation("example", "timed_out");
        Map<ResourceLocation, Integer> scores = new HashMap<>();
        Map<ResourceLocation, Integer> availability = new HashMap<>();
        scores.put(scored, 20);
        availability.put(scored, 1);

        int comparison = assertDoesNotThrow(() -> CandidateEngine.compareCandidateIds(
                scored, timedOut, scores, availability));

        assertTrue(comparison < 0);
    }

    @Test
    void repeatedCraftingSlotsRequireTheirFullQuantity() {
        ShapedRecipe recipe = new ShapedRecipe(
                new ResourceLocation("test", "four_iron"), "", CraftingBookCategory.MISC,
                2, 2, NonNullList.withSize(4, Ingredient.of(Items.IRON_INGOT)),
                new ItemStack(Items.IRON_BLOCK));

        assertEquals(1, CandidateEngine.craftingDemands(recipe).size());
        assertEquals(4, CandidateEngine.craftingDemands(recipe).values().iterator().next().required());
    }

    @Test
    void sameIngredientCatalystAndConsumedDemandStaySeparate() {
        Ingredient iron = Ingredient.of(Items.IRON_INGOT);
        Map<String, CandidateEngine.IngredientDemand> demands = CandidateEngine.specDemands(List.of(
                new IngredientSpec(iron, 1, DemandRole.CATALYST),
                new IngredientSpec(iron, 1, DemandRole.CATALYST),
                new IngredientSpec(iron, 1, DemandRole.CONSUMED)));

        assertEquals(2, demands.size());
        assertTrue(demands.values().stream().anyMatch(d -> d.required() == 2));
        assertTrue(demands.values().stream().anyMatch(d -> d.required() == 1));
    }
}
