package com.huanghuang.rsintegration.crafting.planning;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.PurePlannerLimits;
import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.IngredientRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.MaterialRef;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.NbtMatchMode;
import com.huanghuang.rsintegration.crafting.planning.ImmutableRecipeGraph.RecipeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PurePlannerConfigTest extends BootstrapTest {
    @BeforeEach
    void loadServerConfig() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
    }

    @AfterEach
    void unloadServerConfig() {
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
    }

    @Test
    void livePreviewCadenceCanBeConfiguredIndependentlyOfPlanningLimits() {
        assertTrue(RSIntegrationConfig.CRAFTING_PREVIEW_LIVE_INVENTORY.get());
        assertEquals(20, RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_POLL_TICKS));
        assertEquals(1200, RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_PENDING_TICKS));
        RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_POLL_TICKS.set(5);
        RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_PENDING_TICKS.set(400);
        assertEquals(5, RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_POLL_TICKS));
        assertEquals(400, RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_PENDING_TICKS));
    }

    @Test
    void auxiliaryLimitsReadConfigAndSnapshotDoesNotChangeAfterReload() {
        RSIntegrationConfig.CRAFTING_PURE_AUXILIARY_MAX_DEPTH.set(7);
        RSIntegrationConfig.CRAFTING_PURE_SEED_PROBE_MAX_NODES.set(11);
        RSIntegrationConfig.CRAFTING_PURE_DIAGNOSTIC_MAX_OPERATIONS.set(19);
        PurePlannerLimits snapshot = PurePlannerLimits.load();
        assertEquals(new PurePlannerLimits(7, 11, 19), snapshot);
        RSIntegrationConfig.CRAFTING_PURE_AUXILIARY_MAX_DEPTH.set(3);
        assertEquals(7, snapshot.auxiliaryMaxDepth());
        assertEquals(3, PurePlannerLimits.load().auxiliaryMaxDepth());
    }

    @Test
    void defaultEntryPointUsesConfiguredStateBudget() {
        RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_STATES.set(256);
        List<IngredientRef> roots = new ArrayList<>();
        Map<MaterialRef, Long> stock = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) {
            MaterialRef material = material("stored_" + i);
            roots.add(new IngredientRef(List.of(material), 1L));
            stock.put(material, 1L);
        }
        var graph = new ImmutableRecipeGraph(Map.of());
        assertEquals(PureRecipePlanner.Status.SEARCH_LIMIT, PureRecipePlanner.resolve(graph, stock, roots, 1).status());
        RSIntegrationConfig.CRAFTING_PURE_SEARCH_MAX_STATES.set(1_000);
        assertEquals(PureRecipePlanner.Status.SUCCESS, PureRecipePlanner.resolve(graph, stock, roots, 1).status());
    }

    @Test
    void smallAuxiliaryBudgetDoesNotLimitMainSearchDepth() {
        RSIntegrationConfig.CRAFTING_PURE_AUXILIARY_MAX_DEPTH.set(1);
        RSIntegrationConfig.CRAFTING_PURE_SEED_PROBE_MAX_NODES.set(1);
        MaterialRef raw = material("raw");
        MaterialRef previous = raw;
        Map<MaterialRef, List<RecipeNode>> recipes = new LinkedHashMap<>();
        for (int i = 0; i < 600; i++) {
            MaterialRef output = material("output_" + i);
            recipes.put(output, List.of(new RecipeNode(output.itemId(), output, 1L,
                    List.of(new IngredientRef(List.of(previous), 1L)))));
            previous = output;
        }
        var result = PureRecipePlanner.resolve(new ImmutableRecipeGraph(recipes), Map.of(raw, 1L),
                List.of(new IngredientRef(List.of(previous), 1L, NbtMatchMode.ANY)), 600);
        assertEquals(PureRecipePlanner.Status.SUCCESS, result.status());
        assertEquals(600, result.steps().size());
    }

    @Test
    void stepBudgetAcceptsTenThousandNodesWithinExistingProtocolLimit() {
        CommentedConfig config = CommentedConfig.inMemory();
        RSIntegrationConfig.SERVER_SPEC.correct(config);
        config.set("autoCrafting.craftingMaxSteps", 10_000);
        assertTrue(RSIntegrationConfig.SERVER_SPEC.isCorrect(config));
        RSIntegrationConfig.SERVER_SPEC.setConfig(config);
        assertEquals(10_000, RSIntegrationConfig.CRAFTING_MAX_STEPS.get());
    }

    @Test
    void unloadedConfigUsesTheRegisteredDefaults() {
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
        assertEquals(new PurePlannerLimits(PurePlannerLimits.DEFAULT_AUXILIARY_MAX_DEPTH,
                PurePlannerLimits.DEFAULT_SEED_PROBE_MAX_NODES,
                PurePlannerLimits.DEFAULT_DIAGNOSTIC_MAX_OPERATIONS), PurePlannerLimits.load());
    }

    @Test
    void inventoryRefreshBudgetCanBeDisabledOrChangedThroughConfig() {
        assertEquals(1, RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_REFRESHES));
        RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_REFRESHES.set(0);
        assertEquals(0, RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_REFRESHES));
        RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_REFRESHES.set(3);
        assertEquals(3, RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_REFRESHES));
        RSIntegrationConfig.SERVER_SPEC.setConfig(null);
        assertEquals(1, RSIntegrationConfig.serverInt(RSIntegrationConfig.CRAFTING_PREVIEW_INVENTORY_REFRESHES));
    }

    private static MaterialRef material(String name) {
        return new MaterialRef(new ResourceLocation("config_test", name), "");
    }
}
