package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.OutputDestination;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.crafting.planning.PureRecipePlanner;
import com.huanghuang.rsintegration.crafting.planning.AsyncPlanningCoordinator;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.PlanningSnapshot;
import com.huanghuang.rsintegration.mods.farmingforblockheads.MarketRecipeWrapper;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericCraftPacketTest extends BootstrapTest {

    @Test
    void infeasiblePurePlanFallsBackToTypedResolverForVirtualIntermediates() {
        PureRecipePlanner.Result incomplete = new PureRecipePlanner.Result(false,
                List.of(), List.of(), Map.of());

        assertFalse(GenericCraftPacket.canUsePrecomputedPlan(incomplete));
        assertFalse(GenericCraftPacket.canUsePrecomputedPlan(null));
    }

    @Test
    void feasiblePurePlanCanBeUsedWithoutMainThreadReplanning() {
        PureRecipePlanner.Result complete = new PureRecipePlanner.Result(true,
                List.of(), List.of(), Map.of());

        assertTrue(GenericCraftPacket.canUsePrecomputedPlan(complete));
    }

    @Test
    void runtimeVirtualRecipesDoNotUsePhysicalMachineSlotPlanning() {
        MarketRecipeWrapper market = new MarketRecipeWrapper(UUID.randomUUID(),
                new ItemStack(Items.DIAMOND), new ItemStack(Items.EMERALD));

        assertFalse(GenericCraftPacket.usesPhysicalMachineInputSlots(market));
    }

    @Test
    void asyncResultMustRemainBoundToItsOriginalRequest() {
        UUID playerId = UUID.randomUUID();
        ResourceLocation recipeId = new ResourceLocation("test", "recipe");
        ResourceLocation forcedItem = new ResourceLocation("minecraft", "diamond");
        ResourceLocation forcedRecipe = new ResourceLocation("test", "diamond_recipe");
        Map<ResourceLocation, ResourceLocation> overrides = Map.of(forcedItem, forcedRecipe);
        PlanningSnapshot snapshot = new PlanningSnapshot(playerId, 7, 3, recipeId,
                Map.of(), overrides, new ImmutableRecipeGraph(Map.of()),
                "network", "binding", false);

        assertTrue(GenericCraftPacket.matchesAsyncRequest(
                snapshot, playerId, recipeId, 7, overrides));
        assertFalse(GenericCraftPacket.matchesAsyncRequest(
                snapshot, playerId, recipeId, 8, overrides));
        assertFalse(GenericCraftPacket.matchesAsyncRequest(
                snapshot, playerId, recipeId, 7, Map.of()));
    }

    @Test
    void cancelledAndStaleAsyncPlansNeverTriggerSynchronousFallback() {
        assertFalse(GenericCraftPacket.shouldFallbackAfterAsyncFailure(
                new CancellationException("superseded")));
        PlanningSnapshot snapshot = new PlanningSnapshot(UUID.randomUUID(), 1, 1,
                new ResourceLocation("test", "recipe"), Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of()), "network", "binding", false);
        assertFalse(GenericCraftPacket.shouldFallbackAfterAsyncFailure(
                new AsyncPlanningCoordinator.StalePlanningResultException(snapshot)));
        assertFalse(GenericCraftPacket.shouldFallbackAfterAsyncFailure(
                new RejectedExecutionException("planner queue full")));
        assertTrue(GenericCraftPacket.shouldFallbackAfterAsyncFailure(
                new IllegalStateException("planner failed")));
    }

    @Test
    void synchronousTerminalStepUsesRequestedExecutionCount() {
        var step = GenericCraftPacket.genericTerminalStep(
                new ResourceLocation("minecraft", "iron_ingot"), 6);

        assertEquals(6, step.executions());
    }

    @Test
    void smithingWaitsForAsynchronousIntermediateBeforeTerminalStep() {
        ResourceLocation intermediateId = new ResourceLocation("test", "dark_helmet");
        ResourceLocation smithingId = new ResourceLocation("test", "divine_gold_helmet");
        var intermediate = new CraftingResolver.ResolutionStep(
                intermediateId, ModType.CUSTOM_GUI, new ResourceLocation("test", "machine"));

        List<CraftingResolver.ResolutionStep> chain = GenericCraftPacket.smithingAsyncSteps(
                List.of(intermediate), smithingId, 3);

        assertEquals(2, chain.size());
        assertEquals(intermediateId, chain.get(0).recipeId());
        assertEquals(smithingId, chain.get(1).recipeId());
        assertEquals(ModType.GENERIC, chain.get(1).modType());
        assertEquals(3, chain.get(1).executions());
    }

    @Test
    void genericOnlySmithingIntermediatesStaySynchronous() {
        var intermediate = new CraftingResolver.ResolutionStep(
                new ResourceLocation("test", "dark_helmet"), ModType.GENERIC,
                new ResourceLocation("minecraft", "crafting"));

        assertTrue(GenericCraftPacket.smithingAsyncSteps(
                List.of(intermediate), new ResourceLocation("test", "divine_gold_helmet"), 1)
                .isEmpty());
    }

    @Test
    void terminalInputGraphCoversWholeBatchWithoutScalingCatalysts() {
        List<IngredientSpec> scaled = GenericCraftPacket.scaleIngredientSpecs(List.of(
                new IngredientSpec(Ingredient.of(Items.IRON_INGOT), 1, DemandRole.CONSUMED),
                new IngredientSpec(Ingredient.of(Items.BUCKET), 1, DemandRole.CATALYST)), 6);

        assertEquals(6, scaled.get(0).count());
        assertEquals(DemandRole.CONSUMED, scaled.get(0).role());
        assertEquals(1, scaled.get(1).count());
        assertEquals(DemandRole.CATALYST, scaled.get(1).role());
    }

    @Test
    void outputDestinationRoundTripsAndInvalidOrdinalsDefaultToRs() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        OutputDestination.PLAYER_INVENTORY.write(buffer);

        assertEquals(OutputDestination.PLAYER_INVENTORY,
                OutputDestination.read(buffer));
        assertEquals(OutputDestination.RS_NETWORK, OutputDestination.byOrdinal(-1));
        assertEquals(OutputDestination.RS_NETWORK, OutputDestination.byOrdinal(99));
    }
}
