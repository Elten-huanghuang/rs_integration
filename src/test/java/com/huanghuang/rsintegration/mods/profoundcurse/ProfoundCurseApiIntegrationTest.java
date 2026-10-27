package com.huanghuang.rsintegration.mods.profoundcurse;

import com.example.profound_curse.block.CursedPedestalBlockEntity;
import com.example.profound_curse.block.QuartzDisplayStandBlockEntity;
import com.example.profound_curse.recipe.PedestalCraftingRecipe;
import com.huanghuang.rsintegration.api.IntegrationResourceKey;
import com.huanghuang.rsintegration.api.execution.CollectedOutput;
import com.huanghuang.rsintegration.api.execution.CraftObservation;
import com.huanghuang.rsintegration.api.execution.ExecutionPlan;
import com.huanghuang.rsintegration.api.execution.ExecutionPlanResult;
import com.huanghuang.rsintegration.api.execution.ExecutionRequestContext;
import com.huanghuang.rsintegration.api.execution.ExecutionResult;
import com.huanghuang.rsintegration.api.execution.ExecutionStartStatus;
import com.huanghuang.rsintegration.api.execution.InputRoute;
import com.huanghuang.rsintegration.api.execution.LifecyclePhase;
import com.huanghuang.rsintegration.api.execution.MaterialOutcome;
import com.huanghuang.rsintegration.api.execution.OperationCollection;
import com.huanghuang.rsintegration.api.execution.OperationContext;
import com.huanghuang.rsintegration.api.execution.RecipeExecutionIntegration;
import com.huanghuang.rsintegration.api.execution.ReservedMaterial;
import com.huanghuang.rsintegration.api.execution.ReservedMaterialView;
import com.huanghuang.rsintegration.api.machine.MachineContext;
import com.huanghuang.rsintegration.api.machine.ConcurrencyCapabilities;
import com.huanghuang.rsintegration.api.machine.MachineBinding;
import com.huanghuang.rsintegration.api.machine.MachineDescriptor;
import com.huanghuang.rsintegration.api.recipe.RecipeContext;
import com.huanghuang.rsintegration.api.recipe.RecipeExecutionKind;
import com.huanghuang.rsintegration.api.recipe.RecipeInspectionResult;
import com.huanghuang.rsintegration.api.recipe.RecipeIntegration;
import com.huanghuang.rsintegration.api.recipe.RecipeView;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProfoundCurseApiIntegrationTest extends BootstrapTest {
    @Test
    void orderedPedestalRecipeProjectsDisplaysAndCatalystForCustomExecution() {
        PedestalCraftingRecipe recipe = recipe(true, 3);
        RecipeIntegration<PedestalCraftingRecipe> integration =
                ProfoundCurseApiIntegration.recipeIntegration();

        assertTrue(integration.supports(recipe));
        assertEquals(
                RecipeExecutionKind.CUSTOM,
                integration.executionKind()
        );
        RecipeInspectionResult inspection = integration.inspect(
                new RecipeContext(
                        integration.id(),
                        RegistryAccess.EMPTY,
                        1L
                ),
                recipe
        );
        RecipeView view = inspection.view();
        assertNotNull(view, () -> inspection.diagnostics().toString());
        assertEquals(
                ProfoundCurseApiIntegration.PROCESS_ID,
                view.processId()
        );
        assertEquals(4, view.materials().size());
        assertEquals(
                List.of(0, 1, 2, CursedPedestalStructure.CATALYST_SLOT),
                view.materials().stream()
                        .map(material -> material.inputSlot())
                        .toList()
        );
        assertEquals(
                ProfoundCurseApiIntegration.CATALYST_ENTRY_ID,
                view.materials().get(3).entryId()
        );
        assertTrue(view.flags().orderedInputs());
        assertTrue(view.flags().targetProduction());
        assertTrue(view.flags().deterministicOutput());
        assertFalse(view.flags().supportsBatching());
        assertEquals(
                new ResourceLocation("minecraft", "diamond"),
                view.outputs().get(0).resource().id()
        );
    }

    @Test
    void machineDescriptorOwnsAllEightAdjacentDisplayStands() {
        MachineDescriptor descriptor =
                ProfoundCurseApiIntegration.descriptor();
        MachineBinding binding = ProfoundCurseApiIntegration
                .machineIntegration().bindings().get(0);

        assertEquals(
                CursedPedestalStructure.DISPLAY_SLOT_COUNT,
                descriptor.supportScope().supportOffsets().size()
        );
        assertTrue(descriptor.supportScope().structural());
        assertEquals(
                ConcurrencyCapabilities.SideEffects.ADJACENT_MACHINE,
                descriptor.concurrency().sideEffects()
        );
        assertEquals(
                ConcurrencyCapabilities.CleanupContract.ONLINE_ONLY,
                descriptor.concurrency().cleanup()
        );
        assertEquals(
                List.of(new ResourceLocation(
                        "profound_curse", "cursed_pedestal")),
                binding.blockIds()
        );
        assertFalse(binding.supportsGui());
        assertTrue(binding.acceptedProcesses().contains(
                ProfoundCurseApiIntegration.PROCESS_ID));
    }

    @Test
    void multipleResultCountIsRejectedBecauseNativeSlotStoresOne() {
        PedestalCraftingRecipe recipe = new PedestalCraftingRecipe(
                new ResourceLocation(
                        "profound_curse",
                        "stacked_pedestal_result"
                ),
                false,
                ingredients(3),
                Ingredient.of(Items.NETHERITE_INGOT),
                new ItemStack(Items.DIAMOND, 2)
        );

        RecipeInspectionResult inspection = ProfoundCurseApiIntegration
                .recipeIntegration().inspect(
                        new RecipeContext(
                                ProfoundCurseApiIntegration.RECIPE_INTEGRATION_ID,
                                RegistryAccess.EMPTY,
                                1L
                        ),
                        recipe
                );

        assertNull(inspection.view());
        assertFalse(inspection.diagnostics().isEmpty());
    }

    @Test
    void customExecutionPlacesMaterialsAndCollectsPedestalResult() {
        PedestalCraftingRecipe recipe = recipe(true, 3);
        RecipeView view = ProfoundCurseApiIntegration
                .recipeIntegration().inspect(
                        new RecipeContext(
                                ProfoundCurseApiIntegration.RECIPE_INTEGRATION_ID,
                                RegistryAccess.EMPTY,
                                1L
                        ),
                        recipe
                ).view();
        assertNotNull(view);

        ServerLevel level = mock(ServerLevel.class);
        ServerPlayer player = mock(ServerPlayer.class);
        BlockPos pedestalPos = new BlockPos(0, 64, 0);
        MachineContext machine = new MachineContext(
                ProfoundCurseApiIntegration.MACHINE_INTEGRATION_ID,
                level,
                pedestalPos,
                player
        );
        AtomicReference<ItemStack> pedestalItem =
                new AtomicReference<>(ItemStack.EMPTY);
        AtomicBoolean crafting = new AtomicBoolean();
        CursedPedestalBlockEntity pedestal =
                mock(CursedPedestalBlockEntity.class);
        stubHolder(pedestal, pedestalItem);
        doAnswer(invocation -> {
            crafting.set(true);
            return null;
        }).when(pedestal).tryStartCrafting(level, pedestalPos);
        when(pedestal.isCrafting()).thenAnswer(
                ignored -> crafting.get());

        when(level.hasChunkAt(any(BlockPos.class))).thenReturn(true);
        when(level.getBlockState(any())).thenReturn(
                Blocks.AIR.defaultBlockState());
        when(level.getBlockEntity(pedestalPos)).thenReturn(pedestal);
        when(level.getGameTime()).thenReturn(20L);
        RecipeManager recipeManager = mock(RecipeManager.class);
        doReturn(Optional.of(recipe)).when(recipeManager)
                .byKey(recipe.getId());
        doReturn(List.of(recipe)).when(recipeManager).getRecipes();
        when(level.getRecipeManager()).thenReturn(recipeManager);

        List<AtomicReference<ItemStack>> standItems = new ArrayList<>();
        List<BlockPos> standPositions = CursedPedestalStructure
                .displayPositions(pedestalPos);
        for (BlockPos pos : standPositions) {
            AtomicReference<ItemStack> item =
                    new AtomicReference<>(ItemStack.EMPTY);
            QuartzDisplayStandBlockEntity stand =
                    mock(QuartzDisplayStandBlockEntity.class);
            stubHolder(stand, item);
            standItems.add(item);
            when(level.getBlockEntity(pos)).thenReturn(stand);
        }

        ReservedMaterialView reserved = reservedMaterials(view);
        RecipeExecutionIntegration execution =
                ProfoundCurseApiIntegration.executionIntegration();
        UUID transactionId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        ExecutionPlanResult planResult = execution.createPlan(
                new ExecutionRequestContext(
                        new RecipeContext(
                                ProfoundCurseApiIntegration.RECIPE_INTEGRATION_ID,
                                RegistryAccess.EMPTY,
                                1L
                        ),
                        recipe,
                        view,
                        machine,
                        reserved,
                        transactionId,
                        operationId,
                        1,
                        view.outputs().get(0).resource()
                )
        );
        assertNotNull(planResult.plan());
        ExecutionPlan plan = planResult.plan();
        assertEquals(4, plan.itemInputs().size());
        assertEquals(
                List.of(0, 1, 2, CursedPedestalStructure.CATALYST_SLOT),
                plan.itemInputs().stream().map(InputRoute::slot).toList()
        );

        ExecutionResult started = execution.start(
                new OperationContext(
                        player,
                        transactionId,
                        operationId,
                        1,
                        reserved,
                        machine,
                        20L
                ),
                plan
        );
        assertEquals(ExecutionStartStatus.STARTED, started.status());
        assertEquals(Items.NETHERITE_INGOT,
                pedestalItem.get().getItem());
        assertEquals(Items.BLAZE_POWDER,
                standItems.get(0).get().getItem());
        assertEquals(Items.GHAST_TEAR,
                standItems.get(1).get().getItem());
        assertEquals(Items.BLAZE_POWDER,
                standItems.get(2).get().getItem());

        crafting.set(false);
        pedestalItem.set(new ItemStack(Items.DIAMOND));
        when(level.getGameTime()).thenReturn(120L);
        CraftObservation observation = started.operation().observe(level);
        assertEquals(LifecyclePhase.READY_TO_COLLECT,
                observation.phase());

        OperationCollection collection =
                started.operation().collect(player);
        assertTrue(collection.complete());
        assertEquals(1, collection.outputs().size());
        CollectedOutput output = collection.outputs().get(0);
        assertEquals(Items.DIAMOND, stack(output.resource()).getItem());
        assertEquals(1L, output.amount());
        assertTrue(collection.materials().stream().allMatch(
                disposition -> disposition.outcome()
                        == MaterialOutcome.CONSUMED));
        assertTrue(pedestalItem.get().isEmpty());
        started.operation().onSettled(player);
    }

    private static void stubHolder(
            CursedPedestalBlockEntity holder,
            AtomicReference<ItemStack> item
    ) {
        when(holder.hasItem()).thenAnswer(
                ignored -> !item.get().isEmpty());
        when(holder.getItem()).thenAnswer(
                ignored -> item.get().copy());
        doAnswer(invocation -> {
            item.set(invocation.<ItemStack>getArgument(0).copy());
            return null;
        }).when(holder).setItem(any());
        doAnswer(invocation -> {
            ItemStack removed = item.get().copy();
            item.set(ItemStack.EMPTY);
            return removed;
        }).when(holder).removeItem();
    }

    private static ReservedMaterialView reservedMaterials(RecipeView view) {
        List<ReservedMaterial> materials = view.materials().stream()
                .map(material -> new ReservedMaterial(
                        material.entryId().toString(),
                        material.ingredient().alternatives().get(0).resource(),
                        material.amount(),
                        material.consumption()
                ))
                .toList();
        return new ReservedMaterialView() {
            @Override
            public List<ReservedMaterial> materials() {
                return materials;
            }

            @Override
            public Optional<ReservedMaterial> find(String entryId) {
                return materials.stream()
                        .filter(material -> material.entryId().equals(entryId))
                        .findFirst();
            }
        };
    }

    private static ItemStack stack(IntegrationResourceKey resource) {
        ItemStack stack = new ItemStack(
                ForgeRegistries.ITEMS.getValue(resource.id()));
        if (!resource.identity().isEmpty()) {
            stack.setTag(resource.identity());
        }
        return stack;
    }

    private static PedestalCraftingRecipe recipe(
            boolean ordered,
            int ingredientCount
    ) {
        return new PedestalCraftingRecipe(
                new ResourceLocation(
                        "profound_curse",
                        "test_pedestal"
                ),
                ordered,
                ingredients(ingredientCount),
                Ingredient.of(Items.NETHERITE_INGOT),
                new ItemStack(Items.DIAMOND)
        );
    }

    private static NonNullList<Ingredient> ingredients(
            int ingredientCount
    ) {
        NonNullList<Ingredient> ingredients = NonNullList.create();
        for (int index = 0; index < ingredientCount; index++) {
            ingredients.add(Ingredient.of(
                    index % 2 == 0
                            ? Items.BLAZE_POWDER
                            : Items.GHAST_TEAR));
        }
        return ingredients;
    }
}
