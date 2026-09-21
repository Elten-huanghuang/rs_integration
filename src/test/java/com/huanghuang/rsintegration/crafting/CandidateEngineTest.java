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
    void typedSmithingCandidatesCarryTheModifiedWoodenSwordAcrossTwoUpgrades() throws Exception {
        var template = Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
        var addition = Ingredient.of(Items.NETHERITE_INGOT);
        var stoneRecipe = new net.minecraft.world.item.crafting.SmithingTransformRecipe(
                new ResourceLocation("test:modified_stone"), template,
                Ingredient.of(Items.WOODEN_SWORD), addition, new ItemStack(Items.STONE_SWORD));
        var ironRecipe = new net.minecraft.world.item.crafting.SmithingTransformRecipe(
                new ResourceLocation("test:modified_iron"), template,
                Ingredient.of(Items.STONE_SWORD), addition, new ItemStack(Items.IRON_SWORD));
        ItemStack actual = new ItemStack(Items.WOODEN_SWORD);
        actual.setTag(net.minecraft.nbt.TagParser.parseTag(
                "{Unbreakable:1b,RepairCost:8,Damage:0,itemModifier:\"celestial_forge:vicious\"}"));
        var demand = net.minecraftforge.common.crafting.PartialNBTIngredient.of(Items.IRON_SWORD,
                net.minecraft.nbt.TagParser.parseTag("{Unbreakable:1}"));
        var index = Map.of(Items.STONE_SWORD, List.of(new RecipeIndex.Entry(stoneRecipe,
                com.huanghuang.rsintegration.ModType.GENERIC, new ResourceLocation("minecraft:smithing"))));
        ItemStack inherited = CandidateEngine.findStockedSmithingOutput(ironRecipe,
                stack -> IngredientMatcher.test(demand, stack),
                Map.of(new CraftingResolver.StackKey(actual.getItem(), actual.getTag().toString()), 1),
                index, net.minecraft.core.RegistryAccess.EMPTY, new java.util.HashSet<>(), () -> false);
        assertTrue(inherited.is(Items.IRON_SWORD));
        assertEquals(actual.getTag(), inherited.getTag());
        var pinned = com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler.requireDemandedOutputTag(
                ironRecipe, new com.huanghuang.rsintegration.mods.vanilla.SmithingRecipeHandler()
                        .getIngredients(ironRecipe), inherited);
        ItemStack stone = new ItemStack(Items.STONE_SWORD);
        stone.setTag(actual.getTag().copy());
        assertTrue(IngredientMatcher.test(pinned.get(1).ingredient(), stone));
        stone.getTag().putString("itemModifier", "celestial_forge:other");
        org.junit.jupiter.api.Assertions.assertFalse(IngredientMatcher.test(pinned.get(1).ingredient(), stone));
    }

    @Test
    void smithingCandidateInheritsDemandEvenWhenDeclaredResultContainsDefaultDamage() {
        ItemStack declared = new ItemStack(Items.STONE_SWORD);
        declared.getOrCreateTag().putInt("Damage", 0);
        var recipe = new net.minecraft.world.item.crafting.SmithingTransformRecipe(
                new ResourceLocation("minecraft:stone_sword_smithing"),
                Ingredient.of(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE),
                Ingredient.of(Items.WOODEN_SWORD), Ingredient.of(Items.STONE), declared);
        ItemStack required = new ItemStack(Items.STONE_SWORD);
        required.getOrCreateTag().putInt("Unbreakable", 1);
        Ingredient demand = net.minecraftforge.common.crafting.StrictNBTIngredient.of(required);

        ItemStack candidate = CandidateEngine.inheritSmithingBaseTag(recipe, declared, demand);

        assertTrue(IngredientMatcher.test(demand, candidate));
        assertTrue(candidate.getTag().getBoolean("Unbreakable"));
        assertEquals(0, declared.getTag().getInt("Unbreakable"));
    }

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
    void equalScoreAndCoveragePreferFewerIndependentInputGroups() {
        ResourceLocation complex = new ResourceLocation("example", "a_complex");
        ResourceLocation simple = new ResourceLocation("example", "z_simple");
        Map<ResourceLocation, Integer> scores = Map.of(complex, 20, simple, 20);
        Map<ResourceLocation, Integer> availability = Map.of(complex, 0, simple, 0);
        Map<ResourceLocation, Integer> inputGroups = Map.of(complex, 9, simple, 2);

        int comparison = CandidateEngine.compareCandidateMetrics(
                simple, complex, scores, availability, inputGroups);

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
        assertTrue(demands.values().stream().anyMatch(d -> d.required() == 2
                && d.role() == DemandRole.CATALYST));
        assertTrue(demands.values().stream().anyMatch(d -> d.required() == 1
                && d.role() == DemandRole.CONSUMED));
    }

    @Test
    void cycleGuardDoesNotTreatOrIngredientVariantsAsConsumedInputs() {
        assertEquals(1, CraftingResolver.cycleGuardInputKeys(
                Ingredient.of(Items.QUARTZ_BLOCK)).size());
        assertTrue(CraftingResolver.cycleGuardInputKeys(
                Ingredient.of(Items.CHISELED_QUARTZ_BLOCK, Items.QUARTZ_BLOCK)).isEmpty());
    }
}
