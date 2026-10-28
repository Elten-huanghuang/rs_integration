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
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

class CraftMachineBindingSupportTest extends BootstrapTest {

    // 便于复现“模组级兜底类型 vs 具体机器子类型”的纯类型选择
    private static final ModType COARSE = ModType.register(
            "testutil_coarse", new String[0], new String[0], new String[0],
            GenericBatchDelegate::new);
    private static final ModType SPECIFIC = ModType.register(
            "testutil_coarse_pot", new String[0], new String[0], new String[0],
            GenericBatchDelegate::new);
    private static final ModType OTHER_MOD = ModType.register(
            "testutil_other_pot", new String[0], new String[0], new String[0],
            GenericBatchDelegate::new);

    // 复现原版机器家族：处理器只能声明兜底类型 vanilla_furnace，其余类型靠配方类细分
    private static final ModType VANILLA_FURNACE = ModType.register(
            "vanilla_furnace",
            new String[]{"net.minecraft.world.item.crafting.SmeltingRecipe"},
            new String[]{"furnace"}, new String[]{"vanilla_furnace"},
            GenericBatchDelegate::new);
    private static final ModType VANILLA_BLAST_FURNACE = ModType.register(
            "vanilla_blast_furnace",
            new String[]{"net.minecraft.world.item.crafting.BlastingRecipe"},
            new String[]{"blast_furnace"}, new String[]{"vanilla_blast_furnace"},
            GenericBatchDelegate::new);
    private static final ModType VANILLA_STONECUTTER = ModType.register(
            "vanilla_stonecutter",
            new String[]{"net.minecraft.world.item.crafting.StonecutterRecipe"},
            new String[]{"stonecutter"}, new String[]{"vanilla_stonecutter"},
            GenericBatchDelegate::new);

    @Test
    void unboundCoarseTypeFallsBackToTheSpecificSibling() {
        assertSame(SPECIFIC, CraftMachineBindingSupport.preferSpecificExecutionType(
                COARSE, SPECIFIC, false));
    }

    @Test
    void boundCoarseTypeKeepsTheExistingBinding() {
        assertSame(COARSE, CraftMachineBindingSupport.preferSpecificExecutionType(
                COARSE, SPECIFIC, true));
    }

    @Test
    void otherModTypeIsNotTreatedAsASibling() {
        assertSame(COARSE, CraftMachineBindingSupport.preferSpecificExecutionType(
                COARSE, OTHER_MOD, false));
    }

    @Test
    void genericOrMissingClaimFallsBackToTheClassifiedType() {
        assertSame(SPECIFIC, CraftMachineBindingSupport.preferSpecificExecutionType(
                ModType.GENERIC, SPECIFIC, false));
        assertSame(SPECIFIC, CraftMachineBindingSupport.preferSpecificExecutionType(
                null, SPECIFIC, false));
        assertSame(COARSE, CraftMachineBindingSupport.preferSpecificExecutionType(
                COARSE, null, false));
    }

    @Test
    void cachedExecutionTypeAlsoPrefersTheSpecificSibling() {
        assertSame(SPECIFIC, GenericCraftPacket.modTypeForCachedExecution(
                COARSE, SPECIFIC));
        assertSame(ModType.GENERIC, GenericCraftPacket.modTypeForCachedExecution(
                null, null));
    }

    @Test
    void executionTypeUsesTheWinningApiRecipeClaim() {
        ServerPlayer player = mock(ServerPlayer.class);
        Recipe<?> recipe = mock(Recipe.class);
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.modType(recipe))
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
    void blastingRecipeUsesTheBlastFurnaceExecutionType() {
        // 玩家绑定了高炉但没绑定熔炉时，不能再拿兜底类型 vanilla_furnace 去查绑定
        ServerPlayer player = mock(ServerPlayer.class);
        Recipe<?> recipe = mock(BlastingRecipe.class);

        try (MockedStatic<ModRecipeHandlers> claims = claimForVanillaFurnace(recipe);
             MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class)) {
            assertEquals(
                    VANILLA_BLAST_FURNACE,
                    CraftMachineBindingSupport.resolveExecutionModType(
                            player, recipe, null, null)
            );
        }
    }

    @Test
    void stonecuttingRecipeUsesTheStonecutterExecutionType() {
        ServerPlayer player = mock(ServerPlayer.class);
        Recipe<?> recipe = mock(StonecutterRecipe.class);

        try (MockedStatic<ModRecipeHandlers> claims = claimForVanillaFurnace(recipe);
             MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class)) {
            assertEquals(
                    VANILLA_STONECUTTER,
                    CraftMachineBindingSupport.resolveExecutionModType(
                            player, recipe, null, null)
            );
        }
    }

    @Test
    void existingFurnaceBindingKeepsTheFamilyExecutionType() {
        // 旧存档已经把 vanilla_furnace 绑到机器上时保持原类型，避免破坏既有绑定
        ServerPlayer player = mock(ServerPlayer.class);
        Recipe<?> recipe = mock(BlastingRecipe.class);

        try (MockedStatic<ModRecipeHandlers> claims = claimForVanillaFurnace(recipe);
             MockedStatic<AltarBindingRegistry> bindings =
                     mockStatic(AltarBindingRegistry.class)) {
            bindings.when(() -> AltarBindingRegistry.hasBindingForRecipe(
                            player, recipe, VANILLA_FURNACE))
                    .thenReturn(true);

            assertEquals(
                    VANILLA_FURNACE,
                    CraftMachineBindingSupport.resolveExecutionModType(
                            player, recipe, null, null)
            );
        }
    }

    @Test
    void smeltingRecipeKeepsTheFurnaceExecutionType() {
        ServerPlayer player = mock(ServerPlayer.class);
        Recipe<?> recipe = mock(SmeltingRecipe.class);

        try (MockedStatic<ModRecipeHandlers> claims = claimForVanillaFurnace(recipe)) {
            assertEquals(
                    VANILLA_FURNACE,
                    CraftMachineBindingSupport.resolveExecutionModType(
                            player, recipe, null, null)
            );
        }
    }

    @Test
    void vanillaFamilyHelperOnlyRefinesTheFamilyRoot() {
        assertSame(VANILLA_BLAST_FURNACE,
                CraftMachineBindingSupport.preferVanillaFamilyType(
                        VANILLA_FURNACE, VANILLA_BLAST_FURNACE, false));
        assertSame(VANILLA_FURNACE,
                CraftMachineBindingSupport.preferVanillaFamilyType(
                        VANILLA_FURNACE, VANILLA_STONECUTTER, true));
        assertSame(VANILLA_FURNACE,
                CraftMachineBindingSupport.preferVanillaFamilyType(
                        VANILLA_FURNACE, COARSE, false));
        assertSame(VANILLA_FURNACE,
                CraftMachineBindingSupport.preferVanillaFamilyType(
                        VANILLA_FURNACE, null, false));
        assertSame(VANILLA_BLAST_FURNACE,
                CraftMachineBindingSupport.preferVanillaFamilyType(
                        VANILLA_BLAST_FURNACE, VANILLA_STONECUTTER, false));
        assertTrue(CraftMachineBindingSupport.isVanillaMachineFamilyType(
                VANILLA_STONECUTTER));
        assertFalse(CraftMachineBindingSupport.isVanillaMachineFamilyType(
                VANILLA_FURNACE));
    }

    @Test
    void cachedExecutionTypeAlsoRefinesTheVanillaFamily() {
        assertEquals(VANILLA_STONECUTTER,
                GenericCraftPacket.modTypeForCachedExecution(
                        VANILLA_FURNACE, VANILLA_STONECUTTER));
    }

    private static MockedStatic<ModRecipeHandlers> claimForVanillaFurnace(
            Recipe<?> recipe) {
        ModRecipeHandler handler = mock(ModRecipeHandler.class);
        when(handler.modType(recipe)).thenReturn(VANILLA_FURNACE);
        MockedStatic<ModRecipeHandlers> claims =
                mockStatic(ModRecipeHandlers.class);
        claims.when(() -> ModRecipeHandlers.claimFor(recipe))
                .thenReturn(new ModRecipeHandlers.Claim(handler, List.of(handler)));
        return claims;
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
