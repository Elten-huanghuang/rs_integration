package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.CraftingResolver.ResolutionStep;
import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.DirectMaterialAllocator;
import com.huanghuang.rsintegration.crafting.ExecutionEquivalence;
import com.huanghuang.rsintegration.crafting.IngredientSpec;
import com.huanghuang.rsintegration.crafting.ScaledIngredientSpec;
import com.huanghuang.rsintegration.crafting.graph.CraftPlanGraph;
import com.huanghuang.rsintegration.crafting.graph.DemandRole;
import com.huanghuang.rsintegration.mods.summoningrituals.SummoningRitualAltarBatchDelegate;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SummoningRitualTerminalTest extends BootstrapTest {
    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void altarPreparationComposesWithoutClaimingWorldOutputs(
            boolean alreadyStocked, boolean itemOutput) {
        ModType type = ModType.findById("summoning_rituals");
        if (type == null) {
            type = ModType.register("summoning_rituals", new String[0], new String[0],
                    new String[0], SummoningRitualAltarBatchDelegate::new);
        }
        List<ScaledIngredientSpec> specs = alreadyStocked ? List.of() : List.of(
                ScaledIngredientSpec.of(new IngredientSpec(
                        Ingredient.of(Items.STICK), 1, DemandRole.CATALYST), 1),
                ScaledIngredientSpec.of(new IngredientSpec(Ingredient.of(Items.DIAMOND), 1), 6));
        Map<StackKey, Long> stock = alreadyStocked ? Map.of()
                : Map.of(new StackKey(Items.STICK, null), 1L,
                        new StackKey(Items.DIAMOND, null), 6L);
        var allocation = DirectMaterialAllocator.allocateScaledSingleVariant(specs, stock);
        assertTrue(allocation.feasible());
        CraftPlanGraph inputs = CraftIngredientPlanMath.directTerminalInputGraph(specs, allocation);
        ResourceLocation recipeId = new ResourceLocation("test", "altar");
        ResolutionStep step = new ResolutionStep(
                recipeId, type, recipeId, List.of(), List.of(), false, 6);
        ItemStack output = itemOutput ? new ItemStack(Items.EMERALD) : ItemStack.EMPTY;
        ServerLevel level = mock(ServerLevel.class);
        when(level.getRecipeManager()).thenReturn(mock(RecipeManager.class));

        CraftPlanGraph graph = CraftTerminalSteps.composeEquivalentTerminalGraph(
                inputs, step, output, level);

        assertEquals(1, graph.nodes().size());
        assertEquals(6, graph.nodes().get(0).executions());
        assertEquals(alreadyStocked ? 0 : 2, graph.nodes().get(0).inputs().size());
        assertTrue(graph.nodes().get(0).outputs().isEmpty());
        assertTrue(graph.rootDemands().isEmpty());
        assertNull(ExecutionEquivalence.projectFlatSteps(graph).get(0).productionTarget());
        assertTrue(ExecutionEquivalence.compare(graph, List.of(step)).equivalent());
    }
}
