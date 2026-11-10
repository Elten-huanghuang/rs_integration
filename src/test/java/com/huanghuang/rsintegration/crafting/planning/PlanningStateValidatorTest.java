package com.huanghuang.rsintegration.crafting.planning;

import com.huanghuang.rsintegration.crafting.CraftingResolver.StackKey;
import com.huanghuang.rsintegration.crafting.CraftPlanningRevision;
import com.huanghuang.rsintegration.crafting.MaterialSources;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoint;
import com.huanghuang.rsintegration.crafting.endpoint.CraftStorageEndpoints;
import com.huanghuang.rsintegration.crafting.plan.PlanOutcome;
import com.huanghuang.rsintegration.crafting.plan.PlanGraphView;
import com.huanghuang.rsintegration.crafting.plan.PlanResponse;
import com.huanghuang.rsintegration.crafting.plan.PlanResponseDraft;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.storage.StorageBackendId;
import com.huanghuang.rsintegration.storage.StorageReference;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class PlanningStateValidatorTest extends BootstrapTest {
    @Test
    void cachedGraphIgnoresUnrelatedInventoryChurn() {
        PlanningSnapshot cached = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L,
                new StackKey(Items.DIAMOND, null), 64L), "network:old");
        PlanningSnapshot current = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L,
                new StackKey(Items.EMERALD, null), 12L), "network:new");

        assertTrue(PlanningStateValidator.sameRelevantState(cached, current,
                planRequiring(new ItemStack(Items.COAL), 1)));
    }

    @Test
    void cachedGraphRejectsMissingPlannedSupply() {
        PlanningSnapshot cached = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L), "network:old");
        PlanningSnapshot current = snapshot(Map.of(
                new StackKey(Items.DIAMOND, null), 64L), "network:new");

        assertFalse(PlanningStateValidator.sameRelevantState(cached, current,
                planRequiring(new ItemStack(Items.COAL), 1)));
    }

    @Test
    void cachedGraphRejectsDifferentNetworkIdentity() {
        PlanningSnapshot cached = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L), "first:inventory");
        PlanningSnapshot current = snapshot(Map.of(
                new StackKey(Items.COAL, null), 1L), "second:inventory");

        assertFalse(PlanningStateValidator.sameRelevantState(cached, current,
                planRequiring(new ItemStack(Items.COAL), 1)));
    }

    @Test
    void completeStateRejectsChangedInventoryQuantity() {
        PlanningSnapshot cached = snapshot(Map.of(
                new StackKey(Items.COAL, null), 8L), "network:inventory");
        PlanningSnapshot current = snapshot(Map.of(
                new StackKey(Items.COAL, null), 3L), "network:inventory");

        assertFalse(PlanningStateValidator.sameState(cached, current));
    }

    @Test
    void failedPreviewStateIgnoresOnlyUnrelatedItemTypes() {
        Map<StackKey, Long> previous = Map.of(
                new StackKey(Items.COAL, null), 2L,
                new StackKey(Items.DIAMOND, null), 64L);
        Map<StackKey, Long> unrelatedChange = Map.of(
                new StackKey(Items.COAL, null), 2L,
                new StackKey(Items.EMERALD, null), 12L);
        Map<StackKey, Long> relevantChange = Map.of(
                new StackKey(Items.COAL, null), 1L,
                new StackKey(Items.DIAMOND, null), 64L);

        assertTrue(PlanningStateValidator.sameRelevantInventory(
                previous, unrelatedChange, Set.of(Items.COAL)));
        assertFalse(PlanningStateValidator.sameRelevantInventory(
                previous, relevantChange, Set.of(Items.COAL)));
    }

    @Test
    void successfulPreviewIgnoresChurnWhileRequiredQuantityRemainsAvailable() {
        PlanningSnapshot original = snapshot(Map.of(new StackKey(Items.COAL, null), 64L), "network:old");
        PlanResponse plan = planRequiring(new ItemStack(Items.COAL), 8);
        assertFalse(PlanningStateValidator.previewNeedsRefresh(original, plan,
                Map.of(new StackKey(Items.COAL, null), 12L, new StackKey(Items.DIAMOND, null), 100L)));
        assertFalse(PlanningStateValidator.previewNeedsRefresh(original, plan,
                Map.of(new StackKey(Items.COAL, null), 8L)));
        assertTrue(PlanningStateValidator.previewNeedsRefresh(original, plan,
                Map.of(new StackKey(Items.COAL, null), 7L)));
        assertTrue(PlanningStateValidator.previewNeedsRefresh(original, plan, Map.of()));
    }

    @Test
    void differentNbtCannotReplaceThePlannedSupply() {
        ItemStack required = new ItemStack(Items.COAL);
        required.getOrCreateTag().putString("grade", "a");
        PlanningSnapshot original = snapshot(Map.of(new StackKey(Items.COAL, "{grade:\"a\"}"), 8L), "network:old");
        assertTrue(PlanningStateValidator.previewNeedsRefresh(original, planRequiring(required, 8),
                Map.of(new StackKey(Items.COAL, "{grade:\"b\"}"), 64L)));
    }

    @Test
    void initialSupplySumsAllEdgesAndRootAllocationsButExcludesProducedItems() {
        ItemStack coal = new ItemStack(Items.COAL);
        PlanGraphView graph = new PlanGraphView(1, List.of(), List.of(
                new PlanGraphView.EdgeView(0, 0, new PlanGraphView.SourceView(true, -1, -1), coal, 3),
                new PlanGraphView.EdgeView(1, 0, new PlanGraphView.SourceView(true, -1, -1), coal, 4),
                new PlanGraphView.EdgeView(2, 0, new PlanGraphView.SourceView(false, 0, 0), coal, 100)),
                List.of(new PlanGraphView.RootView(coal, 2, 0, List.of(
                        new PlanGraphView.RootEdgeView(new PlanGraphView.SourceView(true, -1, -1), coal, 2)))),
                List.of(), List.of());
        PlanResponse plan = planWithGraph(graph);
        assertFalse(PlanningStateValidator.hasRequiredInitialSupply(plan, Map.of(new StackKey(Items.COAL, null), 8L)));
        assertTrue(PlanningStateValidator.hasRequiredInitialSupply(plan, Map.of(new StackKey(Items.COAL, null), 9L)));
    }

    @Test
    void missingPreviewRefreshesForSuppliedMaterialsButNotUnrelatedChurn() {
        PlanningSnapshot original = snapshot(Map.of(new StackKey(Items.COAL, null), 2L), "network:old");
        PlanResponse plan = missingCoal();
        assertTrue(PlanningStateValidator.previewNeedsRefresh(original, plan,
                Map.of(new StackKey(Items.COAL, null), 8L)));
        assertFalse(PlanningStateValidator.previewNeedsRefresh(original, plan,
                Map.of(new StackKey(Items.COAL, null), 2L, new StackKey(Items.DIAMOND, null), 64L)));
        assertFalse(PlanningStateValidator.previewNeedsRefresh(original, plan, Map.of()));
    }

    @Test
    void missingPreviewRecognizesNewAlternativesAndIntermediateProducts() {
        MaterialRef coal = new MaterialRef(BuiltInRegistries.ITEM.getKey(Items.COAL), "");
        MaterialRef charcoal = new MaterialRef(BuiltInRegistries.ITEM.getKey(Items.CHARCOAL), "");
        MaterialRef intermediate = new MaterialRef(BuiltInRegistries.ITEM.getKey(Items.COAL_BLOCK), "");
        RecipeNode node = new RecipeNode(new ResourceLocation("test", "intermediate"), intermediate, 1,
                List.of(new IngredientRef(List.of(coal, charcoal), 9)));
        PlanningSnapshot original = new PlanningSnapshot(UUID.randomUUID(), 1, 7,
                new ResourceLocation("test", "target"), Map.of(), Map.of(),
                new ImmutableRecipeGraph(Map.of(intermediate, List.of(node))), "network:old", "binding", false);
        assertTrue(PlanningStateValidator.previewNeedsRefresh(original, missingCoal(),
                Map.of(new StackKey(Items.CHARCOAL, null), 9L)));
        assertTrue(PlanningStateValidator.previewNeedsRefresh(original, missingCoal(),
                Map.of(new StackKey(Items.COAL_BLOCK, null), 1L)));
        assertTrue(PlanningStateValidator.previewNeedsRefresh(original, missingCoal(),
                Map.of(new StackKey(Items.COAL, "{grade:\"new\"}"), 9L)));
    }

    @Test
    void networkIdsWithColonsRetainTheirEntireIdentity() {
        Map<StackKey, Long> available = Map.of(new StackKey(Items.COAL, null), 8L);
        StorageBackendId backend = new StorageBackendId("rs");
        StorageReference first = new StorageReference(backend, "minecraft:overworld:10:20:30");
        StorageReference second = new StorageReference(backend, "minecraft:overworld:11:20:30");
        PlanningSnapshot original = snapshot(available, PlanningStateValidator.networkFingerprint(first, available));
        assertTrue(PlanningStateValidator.sameRelevantState(original,
                snapshot(available, PlanningStateValidator.networkFingerprint(first, Map.of())),
                planRequiring(new ItemStack(Items.COAL), 8)));
        assertFalse(PlanningStateValidator.sameRelevantState(original,
                snapshot(available, PlanningStateValidator.networkFingerprint(second, available)),
                planRequiring(new ItemStack(Items.COAL), 8)));
    }

    @Test
    void livePreviewSurvivesSupplyLossButExecutionAndContextChecksStayStrict() {
        ServerPlayer player = mock(ServerPlayer.class);
        UUID playerId = UUID.randomUUID();
        when(player.getUUID()).thenReturn(playerId);
        PlanRequestService requests = mock(PlanRequestService.class);
        when(requests.isCurrent(playerId, 1)).thenReturn(true);
        StorageReference reference = new StorageReference(new StorageBackendId("test"), "dimension:network:1");
        CraftStorageEndpoint endpoint = mock(CraftStorageEndpoint.class);
        PlanningSnapshot original = new PlanningSnapshot(playerId, 1, CraftPlanningRevision.current(),
                new ResourceLocation("test", "target"), Map.of(new StackKey(Items.COAL, null), 8L), Map.of(),
                new ImmutableRecipeGraph(Map.of()), PlanningStateValidator.networkFingerprint(reference, Map.of()),
                "binding", false);
        PlanResponse plan = planRequiring(new ItemStack(Items.COAL), 8);
        PlanResponseDraft draft = mock(PlanResponseDraft.class);
        when(draft.graph()).thenReturn(plan.graph());
        when(draft.success()).thenReturn(true);
        try (var endpoints = mockStatic(CraftStorageEndpoints.class);
             var materials = mockStatic(MaterialSources.class);
             var validator = mockStatic(PlanningStateValidator.class, CALLS_REAL_METHODS)) {
            endpoints.when(() -> CraftStorageEndpoints.resolve(reference, player)).thenReturn(Optional.of(endpoint));
            validator.when(() -> PlanningStateValidator.bindingFingerprint(player, Level.OVERWORLD, BlockPos.ZERO))
                    .thenReturn("binding");
            materials.when(() -> MaterialSources.listAvailableForTypes(player, endpoint, Set.of(Items.COAL)))
                    .thenReturn(Map.of());

            assertTrue(PlanningStateValidator.revalidatePreview(player, original, draft,
                    Level.OVERWORLD, BlockPos.ZERO, requests, reference));
            assertTrue(PlanningStateValidator.previewInventoryChanged(player, original, plan,
                    Level.OVERWORLD, BlockPos.ZERO, reference));
            assertFalse(PlanningStateValidator.revalidateForExecution(player, original, plan,
                    Level.OVERWORLD, BlockPos.ZERO, reference));

            StorageReference other = new StorageReference(reference.backendId(), "dimension:network:2");
            endpoints.when(() -> CraftStorageEndpoints.resolve(other, player)).thenReturn(Optional.of(endpoint));
            assertFalse(PlanningStateValidator.revalidatePreview(player, original, draft,
                    Level.OVERWORLD, BlockPos.ZERO, requests, other));
            when(requests.isCurrent(playerId, 1)).thenReturn(false);
            assertFalse(PlanningStateValidator.revalidatePreview(player, original, draft,
                    Level.OVERWORLD, BlockPos.ZERO, requests, reference));
            when(requests.isCurrent(playerId, 1)).thenReturn(true);
            validator.when(() -> PlanningStateValidator.bindingFingerprint(player, Level.OVERWORLD, BlockPos.ZERO))
                    .thenReturn("new_binding");
            assertFalse(PlanningStateValidator.revalidatePreview(player, original, draft,
                    Level.OVERWORLD, BlockPos.ZERO, requests, reference));
        }
    }

    private static PlanResponse missingCoal() {
        return new PlanResponse(false, "", ItemStack.EMPTY, List.of(),
                Map.of(IngredientKey.of(new ItemStack(Items.COAL)), new PlanResponse.Availability(8, 2)),
                List.of("coal"), "test:target", List.of(), 1, PlanOutcome.MISSING_INPUTS, List.of());
    }

    private static PlanningSnapshot snapshot(Map<StackKey, Long> available,
                                             String networkFingerprint) {
        return new PlanningSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1L, 7L, new ResourceLocation("test", "target"), available, Map.of(),
                new ImmutableRecipeGraph(Map.of()), networkFingerprint, "binding", false);
    }

    private static PlanResponse planRequiring(ItemStack stack, int quantity) {
        PlanGraphView graph = new PlanGraphView(1, List.of(), List.of(
                new PlanGraphView.EdgeView(0, 0,
                        new PlanGraphView.SourceView(true, -1, -1), stack, quantity)),
                List.of(), List.of(), List.of());
        return planWithGraph(graph);
    }

    private static PlanResponse planWithGraph(PlanGraphView graph) {
        return new PlanResponse(true, "", ItemStack.EMPTY, List.of(), Map.of(), List.of(),
                "test:target", null, null, 0, 0, 0, List.of(), 1,
                null, null, null, 0L, false, false, false, null, Set.of(), Map.of(),
                null, graph);
    }
}
