package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.crafting.RecoveryRoutePolicy;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AdventureRoutePlanningTest extends BootstrapTest {
    private static MaterialRef item(String id) { return new MaterialRef(ResourceLocation.parse(id), ""); }
    private static IngredientRef need(MaterialRef item, long count) { return new IngredientRef(List.of(item), count); }

    private static final MaterialRef IRON = item("minecraft:iron_ingot"), RAW = item("minecraft:raw_iron"),
            CAN = item("youkaishomecoming:can"), BLADE = item("legendary_monsters:knights_sword_blade_half");
    private static RecipeNode furnace(String id, MaterialRef input) {
        return new RecipeNode(ResourceLocation.parse(id), IRON, 1, List.of(need(input, 1)),
                "vanilla_furnace", ResourceLocation.parse("minecraft:smelting"));
    }
    private static final RecipeNode CANS = furnace("youkaishomecoming:iron_ingot_from_can_smelting", CAN),
            ORE = furnace("minecraft:iron_ingot_from_smelting_raw_iron", RAW),
            SWORD = new RecipeNode(ResourceLocation.parse("legendary_monsters:iron_ingot_knights_sword_half"),
                    IRON, 1, List.of(need(BLADE, 1)));

    @Test
    void usesRawIronBeforeRecyclingEvenIfTheBladeIsAlreadyStocked() {
        for (var routes : List.of(List.of(SWORD, ORE, CANS), List.of(CANS, ORE, SWORD))) {
            var graph = new ImmutableRecipeGraph(Map.of(IRON, routes));
            var result = PureRecipePlanner.resolve(graph, Map.of(BLADE, 1, CAN, 1, RAW, 1), List.of(need(IRON, 1)), 16);
            assertTrue(result.feasible());
            assertEquals(ORE.recipeId(), result.steps().get(0).recipeId());
            assertEquals(Map.of(CAN, 1L, BLADE, 1L), result.remaining());
        }
    }

    @Test
    void oneAvailableCanOffsetsOneIronWithoutRecommendingSixtyThreeCans() {
        var graph = new ImmutableRecipeGraph(Map.of(IRON, List.of(CANS, ORE, SWORD)));
        var result = PureRecipePlanner.resolve(graph, Map.of(CAN, 1), List.of(need(IRON, 64)), 16);
        assertFalse(result.feasible());
        assertEquals(List.of(need(IRON, 63)), result.missing());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(CANS.recipeId(), 1)), result.steps());
        assertEquals(64L, result.previewTrace().roots().stream().mapToLong(PureRecipePlanner.PreviewDemand::quantity).sum());
        var forced = PureRecipePlanner.resolve(graph, Map.of(CAN, 1), List.of(need(IRON, 64)), 16,
                1000, 256, Long.MAX_VALUE, System::nanoTime, Set.of(), Map.of(IRON.itemId(), CANS.recipeId()));
        assertEquals(List.of(need(CAN, 63)), forced.missing());
    }

    @Test
    void sevenBlocksCanBeCombinedWithAnotherSourceOrLeaveExactlyOneIronMissing() {
        var block = item("minecraft:iron_block");
        var unpack = new RecipeNode(ResourceLocation.parse("minecraft:iron_ingot_from_iron_block"), IRON, 9, List.of(need(block, 1)));
        var graph = new ImmutableRecipeGraph(Map.of(IRON, List.of(CANS, ORE, unpack, SWORD)));
        for (var last : List.of(CAN, RAW)) {
            var result = PureRecipePlanner.resolve(graph, Map.of(block, 7, last, 1), List.of(need(IRON, 64)), 16);
            assertTrue(result.feasible(), result.toString());
            assertEquals(Map.of(), result.remaining());
        }
        var partial = PureRecipePlanner.resolve(graph, Map.of(block, 7), List.of(need(IRON, 64)), 16);
        assertFalse(partial.feasible());
        assertEquals(List.of(need(IRON, 1)), partial.missing());
        assertEquals(List.of(new PureRecipePlanner.PlannedStep(unpack.recipeId(), 7)), partial.steps());
    }

    @Test
    void recoveryRuleDoesNotFollowAReplacedRecipeIdOrHideTheOnlyStrictRoute() {
        assertEquals(RecoveryRoutePolicy.NORMAL, RecoveryRoutePolicy.classify(furnace(CANS.recipeId().toString(), RAW)));
        assertEquals(RecoveryRoutePolicy.NORMAL, RecoveryRoutePolicy.classify(
                new RecipeNode(CANS.recipeId(), IRON, 1, List.of(need(CAN, 1)))));
        var graph = new ImmutableRecipeGraph(Map.of(IRON, List.of(SWORD)));
        var result = PureRecipePlanner.resolve(graph, Map.of(BLADE, 1), List.of(need(IRON, 1)), 16);
        assertTrue(result.feasible());
        assertEquals(SWORD.recipeId(), result.steps().get(0).recipeId());
        var excluded = new PlanningPreferences(Map.of(), Map.of(), Set.of(SWORD.recipeId()));
        var forbidden = PureRecipePlanner.resolve(graph, Map.of(BLADE, 1), List.of(need(IRON, 1)), 16,
                1000, 256, Long.MAX_VALUE, System::nanoTime, Set.of(), Map.of(), excluded);
        assertFalse(forbidden.feasible());
        assertTrue(forbidden.steps().isEmpty());
    }

    @Test
    void aSingleCanIsNotAllocatedTwiceAndEitherRootOrderWorksWithRawIron() {
        var food = item("youkaishomecoming:canned_flesh");
        var cook = new RecipeNode(ResourceLocation.parse("youkaishomecoming:canned_flesh"), food, 1, List.of(need(CAN, 1)));
        var graph = new ImmutableRecipeGraph(Map.of(IRON, List.of(CANS, ORE), food, List.of(cook)));
        for (var roots : List.of(List.of(need(IRON, 1), need(food, 1)), List.of(need(food, 1), need(IRON, 1)))) {
            var result = PureRecipePlanner.resolve(graph, Map.of(CAN, 1, RAW, 1), roots, 16);
            assertTrue(result.feasible());
            assertFalse(result.steps().stream().anyMatch(s -> s.recipeId().equals(CANS.recipeId())));
            assertFalse(PureRecipePlanner.resolve(graph, Map.of(CAN, 1), roots, 16).feasible());
        }
    }

    @Test
    void independentInfusionCanEnterCompressionCycleIncludingAnAcceptedNbtVariant() {
        var gem = item("goety:magic_emerald");
        var block = item("goety:awakened_emerald_block");
        var ordinary = item("minecraft:emerald_block");
        var unpack = new RecipeNode(ResourceLocation.parse("goety:magic_emerald_from_block"), gem, 9,
                List.of(new IngredientRef(List.of(block), 1, NbtMatchMode.ANY)));
        var pack = new RecipeNode(block.itemId(), block, 1, List.of(need(gem, 9)));
        for (var fromInfuser : List.of(block, new MaterialRef(block.itemId(), "{variant:1}"))) {
            var infuse = new RecipeNode(ResourceLocation.parse("goety:awakened_emerald_block_burner"), fromInfuser, 1,
                    List.of(need(ordinary, 1)), "goety_cursed_infuser", ResourceLocation.parse("goety:cursed_infuser_recipes"));
            var entries = new HashMap<MaterialRef, List<RecipeNode>>();
            entries.put(gem, List.of(unpack));
            entries.put(block, List.of(pack));
            entries.put(fromInfuser, fromInfuser.equals(block) ? List.of(pack, infuse) : List.of(infuse));
            var graph = new ImmutableRecipeGraph(entries);
            var roots = List.of(new IngredientRef(List.of(gem), 9, NbtMatchMode.ANY));
            var result = PureRecipePlanner.resolve(graph, Map.of(ordinary, 1), roots, 16);
            assertTrue(result.feasible(), result.toString());
            assertEquals(List.of(infuse.recipeId(), unpack.recipeId()), result.steps().stream().map(PureRecipePlanner.PlannedStep::recipeId).toList());
            assertFalse(PureRecipePlanner.resolve(graph, Map.of(), roots, 16).feasible());
        }
    }
}
