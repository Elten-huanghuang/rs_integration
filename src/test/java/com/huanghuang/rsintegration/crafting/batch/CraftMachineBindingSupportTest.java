package com.huanghuang.rsintegration.crafting.batch;

import com.huanghuang.rsintegration.ModType;
import com.huanghuang.rsintegration.crafting.AsyncCraftManager;
import com.huanghuang.rsintegration.network.binding.AltarBindingRegistry;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.recipe.ModRecipeHandler;
import com.huanghuang.rsintegration.recipe.ModRecipeHandlers;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class CraftMachineBindingSupportTest extends BootstrapTest {
    @Test
    void executionTypeUsesTheWinningApiRecipeClaim() {
        ServerPlayer player = mock(ServerPlayer.class);
        Recipe<?> recipe = mock(Recipe.class);
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.modType())
                .thenReturn(ModType.EXTERNAL_INTEGRATION);

        try (MockedStatic<ModRecipeHandlers> claims =
                     mockStatic(ModRecipeHandlers.class)) {
            claims.when(() -> ModRecipeHandlers.claimFor(recipe))
                    .thenReturn(new ModRecipeHandlers.Claim(
                            handler,
                            List.of(handler)
                    ));

            assertEquals(
                    ModType.EXTERNAL_INTEGRATION,
                    CraftMachineBindingSupport.resolveExecutionModType(
                            player, recipe, null, null)
            );
        }
    }

    @Test
    void consumerAvailabilityRequiresAnUnleasedMachine() {
        ServerPlayer player = mock(ServerPlayer.class);
        Recipe<?> recipe = mock(Recipe.class);
        ResourceLocation recipeId =
                new ResourceLocation("test", "bound_recipe");
        when(recipe.getId()).thenReturn(recipeId);
        AltarBindingRegistry.BoundMachine machine =
                new AltarBindingRegistry.BoundMachine(
                        new ResourceLocation("minecraft", "overworld"),
                        BlockPos.ZERO,
                        ModType.EXTERNAL_INTEGRATION,
                        "test:machine"
                );
        AsyncCraftManager manager =
                mock(AsyncCraftManager.class);

        try (MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class);
             MockedStatic<AsyncCraftManager> managers =
                     mockStatic(AsyncCraftManager.class)) {
            bindings.when(() -> AltarBindingRegistry
                            .getBoundMachinesForRecipe(
                                    player,
                                    ModType.EXTERNAL_INTEGRATION,
                                    recipeId))
                    .thenReturn(List.of(machine));
            managers.when(AsyncCraftManager::getInstance)
                    .thenReturn(manager);

            when(manager.areAllMachinesLeased(
                    List.of(machine),
                    ModType.EXTERNAL_INTEGRATION.id()
            )).thenReturn(false);
            assertTrue(CraftMachineBindingSupport
                    .hasIdleBindingForExecutionType(
                            player,
                            recipe,
                            ModType.EXTERNAL_INTEGRATION
                    ));

            when(manager.areAllMachinesLeased(
                    List.of(machine),
                    ModType.EXTERNAL_INTEGRATION.id()
            )).thenReturn(true);
            assertFalse(CraftMachineBindingSupport
                    .hasIdleBindingForExecutionType(
                            player,
                            recipe,
                            ModType.EXTERNAL_INTEGRATION
                    ));
        }
    }

    @Test
    void consumerAvailabilityRejectsMissingBindings() {
        ServerPlayer player = mock(ServerPlayer.class);
        Recipe<?> recipe = mock(Recipe.class);
        ResourceLocation recipeId =
                new ResourceLocation("test", "unbound_recipe");
        when(recipe.getId()).thenReturn(recipeId);

        try (MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class)) {
            bindings.when(() -> AltarBindingRegistry
                            .getBoundMachinesForRecipe(
                                    player,
                                    ModType.EXTERNAL_INTEGRATION,
                                    recipeId))
                    .thenReturn(List.of());

            assertFalse(CraftMachineBindingSupport
                    .hasIdleBindingForExecutionType(
                            player,
                            recipe,
                            ModType.EXTERNAL_INTEGRATION
                    ));
        }
    }

    @Test
    void consumerGraphDropsAnIntermediateWhoseMachinesAreAllLeased() {
        ServerPlayer player = mock(ServerPlayer.class);
        ServerLevel level = mock(ServerLevel.class);
        RecipeManager recipes = mock(RecipeManager.class);
        Recipe<?> recipe = mock(Recipe.class);
        ResourceLocation recipeId =
                new ResourceLocation("test", "leased_recipe");
        MaterialRef output = new MaterialRef(
                new ResourceLocation("test", "leased_output"), "");
        when(player.serverLevel()).thenReturn(level);
        when(level.getRecipeManager()).thenReturn(recipes);
        when(recipe.getId()).thenReturn(recipeId);
        doReturn(Optional.of(recipe))
                .when(recipes)
                .byKey(recipeId);
        RecipeNode node = new RecipeNode(
                recipeId,
                output,
                1L,
                List.of(),
                ModType.EXTERNAL_INTEGRATION.id(),
                new ResourceLocation("test", "machine")
        );
        ImmutableRecipeGraph graph = new ImmutableRecipeGraph(
                Map.of(output, List.of(node)));
        AltarBindingRegistry.BoundMachine machine =
                new AltarBindingRegistry.BoundMachine(
                        new ResourceLocation("minecraft", "overworld"),
                        BlockPos.ZERO,
                        ModType.EXTERNAL_INTEGRATION,
                        "test:machine"
                );
        AsyncCraftManager manager =
                mock(AsyncCraftManager.class);

        try (MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class);
             MockedStatic<AsyncCraftManager> managers =
                     mockStatic(AsyncCraftManager.class)) {
            bindings.when(() -> AltarBindingRegistry
                            .getBoundMachinesForRecipe(
                                    player,
                                    ModType.EXTERNAL_INTEGRATION,
                                    recipeId))
                    .thenReturn(List.of(machine));
            managers.when(AsyncCraftManager::getInstance)
                    .thenReturn(manager);
            when(manager.areAllMachinesLeased(
                    List.of(machine),
                    ModType.EXTERNAL_INTEGRATION.id()
            )).thenReturn(true);

            GenericCraftPacket.BindingAwareGraph filtered =
                    GenericCraftPacket.consumerBindingAwareGraph(
                            player, graph);

            assertTrue(filtered.graph().recipesByOutput().isEmpty());
            assertTrue(filtered.blockedOutputIds()
                    .contains(output.itemId()));
        }
    }
}
