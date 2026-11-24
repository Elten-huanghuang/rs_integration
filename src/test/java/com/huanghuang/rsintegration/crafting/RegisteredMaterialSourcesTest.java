package com.huanghuang.rsintegration.crafting;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.graph.MaterialSource;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisteredMaterialSourcesTest extends BootstrapTest {
    @BeforeAll
    static void initializeMod() {
        DeepPlanningFixture.initializeMod();
    }

    @Test
    void sharesOneBudgetAcrossItemsAndRestoresItOnCandidateRollback() {
        var iron = StackKey.of(new ItemStack(Items.IRON_INGOT), true);
        var gold = StackKey.of(new ItemStack(Items.GOLD_INGOT), true);
        var source = ResourceLocation.parse("test:materials");
        var pool = new RegisteredMaterialSources.Pool(source, ResourceLocation.parse("test:balance"));
        var stock = new RegisteredMaterialSources.StockSnapshot(Map.of(pool, BigInteger.TEN), Map.of(
                iron, List.of(new RegisteredMaterialSources.Stock(source, iron, pool, BigInteger.ONE, 10)),
                gold, List.of(new RegisteredMaterialSources.Stock(source, gold, pool, BigInteger.ONE, 10))));
        var plan = new ResolutionContext(null, Map.of(), Map.of(iron, 2L), null, false, null);
        plan.setExtensionStock(stock);

        var ironIngredient = Ingredient.of(Items.IRON_INGOT);
        var goldIngredient = Ingredient.of(Items.GOLD_INGOT);
        assertEquals(12L, plan.countMatching(Ingredient.of(Items.IRON_INGOT, Items.GOLD_INGOT)));
        plan.beginUndo();
        var mixed = plan.consumeMatchingDetailed(ironIngredient, 8L);
        assertTrue(mixed.complete());
        assertEquals(2L, mixed.slices().get(0).quantity());
        assertNull(((MaterialSource.InitialPool) mixed.slices().get(0).source()).extensionId());
        assertEquals(6L, mixed.slices().get(1).quantity());
        assertEquals(source, ((MaterialSource.InitialPool) mixed.slices().get(1).source()).extensionId());
        assertEquals(4L, plan.countMatching(goldIngredient));
        plan.rollback();
        assertEquals(10L, plan.countMatching(goldIngredient));
        assertEquals(12L, plan.countMatching(ironIngredient));

        assertTrue(stock.covers(Map.of(iron, 8L, gold, 4L), Map.of(iron, 2L)));
        assertFalse(stock.covers(Map.of(iron, 8L, gold, 5L), Map.of(iron, 2L)));
        var required = Map.of(iron, 8L, gold, 4L);
        var quoted = List.of(new RegisteredMaterialSources.Share(source, iron, 6),
                new RegisteredMaterialSources.Share(source, gold, 4));
        assertTrue(stock.matchesAllocation(required, Map.of(iron, 2L), quoted));
        assertFalse(stock.matchesAllocation(required, Map.of(iron, 3L), quoted));
        assertTrue(stock.covers(required, Map.of(iron, 3L)));
    }

    @Test
    void quotesPreserveNativePlanningAndOnlyPromptFallbackForMatchingShortages() {
        var recipe = ResourceLocation.parse("test:recipe");
        var source = ResourceLocation.parse("test:materials");
        var item = StackKey.of(new ItemStack(Items.IRON_INGOT), true);
        var pool = new RegisteredMaterialSources.Pool(source, ResourceLocation.parse("test:balance"));
        var stock = new RegisteredMaterialSources.StockSnapshot(Map.of(pool, BigInteger.TEN),
                Map.of(item, List.of(new RegisteredMaterialSources.Stock(source, item, pool, BigInteger.ONE, 10))));
        var snapshot = new PlanningSnapshot(UUID.randomUUID(), 1, 1, recipe, Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of()), "", "", false).withExtensionStock(stock);
        var iron = new ImmutableRecipeGraph.IngredientRef(List.of(
                new ImmutableRecipeGraph.MaterialRef(ResourceLocation.parse("minecraft:iron_ingot"), "")), 2);
        var gold = new ImmutableRecipeGraph.IngredientRef(List.of(
                new ImmutableRecipeGraph.MaterialRef(ResourceLocation.parse("minecraft:gold_ingot"), "")), 2);
        assertFalse(snapshot.mainThreadOnly());
        assertFalse(snapshot.canSupplement(new PureRecipePlanner.Result(true, List.of(), List.of(), Map.of())));
        assertTrue(snapshot.canSupplement(new PureRecipePlanner.Result(false, List.of(), List.of(iron), Map.of())));
        assertFalse(snapshot.canSupplement(new PureRecipePlanner.Result(false, List.of(), List.of(gold), Map.of())));
    }
}
