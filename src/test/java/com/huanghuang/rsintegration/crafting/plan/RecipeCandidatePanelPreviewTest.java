package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.tree.RecipePreviewRenderer;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.CraftingResolver;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecipeCandidatePanelPreviewTest extends BootstrapTest {
    private static final ResourceLocation CURRENT = id("current");
    private static final ResourceLocation FIRST_ALTERNATIVE = id("first_alternative");
    private static final ResourceLocation SECOND_ALTERNATIVE = id("second_alternative");

    @TempDir
    Path tempDir;

    @Test
    void everyCandidateCardRendersItsJeiPreview() {
        Font font = mock(Font.class);
        RecipePreviewRenderer preview = mock(RecipePreviewRenderer.class);
        when(font.width(anyString())).thenReturn(0);
        when(font.plainSubstrByWidth(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(font.plainSubstrByWidth(anyString(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(preview.candidateDetails(any(), any()))
                .thenAnswer(invocation -> new RecipePreviewRenderer.CandidateDetails(
                        invocation.<ResourceLocation>getArgument(0).getPath(), ""));
        when(preview.categoryTitle(any())).thenReturn(
                Optional.of(Component.literal("machine")));
        when(preview.previewWidthHint(any())).thenReturn(80);
        when(preview.previewBounds(any()))
                .thenReturn(new RecipePreviewRenderer.PreviewBounds(80, 40));

        RecipeCandidatePanel panel = new RecipeCandidatePanel(font, preview,
                (node, recipe) -> {}, (node, recipe) -> {}, node -> {}, node -> {},
                tempDir.resolve("crafting_plan.json"),
                () -> {}, () -> true);
        PlanStep step = new PlanStep(CURRENT, new ItemStack(Items.DIAMOND), 1, List.of(),
                List.of(FIRST_ALTERNATIVE, SECOND_ALTERNATIVE), null, 0, true);
        PlanTreeNode node = new PlanTreeNode(
                IngredientKey.of(
                        new ItemStack(Items.DIAMOND)),
                new ItemStack(Items.DIAMOND), 1, 0, step);

        panel.open(node, Set.of());
        for (RecipeCandidatePickerModel.Candidate candidate : panel.visibleCandidates()) {
            assertTrue(panel.cardHeight(candidate) > 30,
                    () -> "preview area was not allocated for " + candidate.recipeId());
        }
        verify(preview, atLeastOnce()).previewBounds(CURRENT);
        verify(preview, atLeastOnce()).previewBounds(FIRST_ALTERNATIVE);
        verify(preview, atLeastOnce()).previewBounds(SECOND_ALTERNATIVE);
    }

    @Test
    void oversizedNineByNinePreviewIsConstrainedToTheThreeByThreeCardBudget() {
        RecipePreviewRenderer.PreviewBounds capped =
                RecipeCandidatePanel.constrainPreviewBounds(
                        new RecipePreviewRenderer.PreviewBounds(240, 240));
        RecipePreviewRenderer.PreviewBounds ordinary =
                RecipeCandidatePanel.constrainPreviewBounds(
                        new RecipePreviewRenderer.PreviewBounds(80, 40));

        assertEquals(new RecipePreviewRenderer.PreviewBounds(
                        RecipeCandidatePanel.MAX_PREVIEW_WIDTH,
                        RecipeCandidatePanel.MAX_PREVIEW_HEIGHT),
                capped);
        assertEquals(new RecipePreviewRenderer.PreviewBounds(80, 40), ordinary);
    }

    @Test
    void selectingThePlanCurrentRecipeStillNotifiesTheScreenAndDoesNotCommitEarly() {
        Font font = mock(Font.class);
        RecipePreviewRenderer preview = mock(RecipePreviewRenderer.class);
        when(font.width(anyString())).thenReturn(0);
        when(font.plainSubstrByWidth(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(font.plainSubstrByWidth(anyString(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(preview.candidateDetails(any(), any()))
                .thenAnswer(invocation -> new RecipePreviewRenderer.CandidateDetails(
                        invocation.<ResourceLocation>getArgument(0).getPath(), ""));
        when(preview.categoryTitle(any())).thenReturn(
                Optional.of(Component.literal("machine")));
        when(preview.previewWidthHint(any())).thenReturn(80);
        when(preview.previewBounds(any()))
                .thenReturn(new RecipePreviewRenderer.PreviewBounds(80, 40));
        Path preferences = tempDir.resolve("node_preferences.json");
        AtomicReference<ResourceLocation> selected = new AtomicReference<>();
        RecipeCandidatePanel panel = new RecipeCandidatePanel(font, preview,
                (node, recipe) -> selected.set(recipe), (node, recipe) -> {},
                node -> {}, node -> {}, preferences, () -> {}, () -> true);
        PlanStep step = new PlanStep(CURRENT, new ItemStack(Items.DIAMOND), 1, List.of(),
                List.of(FIRST_ALTERNATIVE), null, 0, true);
        PlanTreeNode node = new PlanTreeNode(
                IngredientKey.of(
                        new ItemStack(Items.DIAMOND)),
                new ItemStack(Items.DIAMOND), 1, 0, step);

        panel.open(node, Set.of());
        panel.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0);

        assertEquals(CURRENT, selected.get());
        assertNull(CraftingPlanPreferences.load(preferences).recipeChoices()
                .get(CraftingResolver.preferenceKey(node.key.stack(1)).toString()));
    }

    @Test
    void stockOnlyCanBeCancelledByClickingItAgain() {
        Font font = mock(Font.class);
        RecipePreviewRenderer preview = mock(RecipePreviewRenderer.class);
        when(font.width(anyString())).thenReturn(0);
        when(font.plainSubstrByWidth(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(font.plainSubstrByWidth(anyString(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(preview.candidateDetails(any(), any()))
                .thenAnswer(invocation -> new RecipePreviewRenderer.CandidateDetails(
                        invocation.<ResourceLocation>getArgument(0).getPath(), ""));
        when(preview.categoryTitle(any())).thenReturn(
                Optional.of(Component.literal("machine")));
        when(preview.previewWidthHint(any())).thenReturn(80);
        when(preview.previewBounds(any()))
                .thenReturn(new RecipePreviewRenderer.PreviewBounds(80, 40));
        Path preferences = tempDir.resolve("stock_only_toggle.json");
        AtomicReference<ResourceLocation> cancelledTo = new AtomicReference<>();
        RecipeCandidatePanel panel = new RecipeCandidatePanel(font, preview,
                (node, recipe) -> {}, (node, recipe) -> cancelledTo.set(recipe),
                node -> {}, node -> {},
                preferences, () -> {}, () -> true);
        PlanStep step = new PlanStep(CURRENT, new ItemStack(Items.DIAMOND), 1, List.of(),
                List.of(FIRST_ALTERNATIVE), null, 1, true);
        PlanTreeNode node = new PlanTreeNode(
                IngredientKey.of(
                        new ItemStack(Items.DIAMOND)),
                new ItemStack(Items.DIAMOND), 1, 1, step);
        String preferenceKey = CraftingResolver.preferenceKey(node.key.stack(1)).toString();

        panel.open(node, Set.of());
        panel.selectNoRecipe();

        assertFalse(panel.isOpen());
        assertEquals(RecipeChoice.NoRecipe.INSTANCE,
                CraftingPlanPreferences.load(preferences).recipeChoices().get(preferenceKey));

        panel.open(node, Set.of());
        assertTrue(panel.stockOnlySelected());
        panel.selectNoRecipe();

        assertFalse(panel.isOpen());
        assertNull(CraftingPlanPreferences.load(preferences).recipeChoices().get(preferenceKey));
        assertEquals(CURRENT, cancelledTo.get());
        panel.open(node, Set.of());
        assertFalse(panel.stockOnlySelected());
    }

    @Test
    void stockOnlyCanBeSelectedForARecursiveLeafWithoutCandidates() {
        Font font = mock(Font.class);
        RecipePreviewRenderer preview = mock(RecipePreviewRenderer.class);
        when(font.width(anyString())).thenReturn(0);
        when(font.plainSubstrByWidth(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(font.plainSubstrByWidth(anyString(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        Path preferences = tempDir.resolve("no_candidate_leaf.json");
        AtomicReference<ResourceLocation> selected = new AtomicReference<>(CURRENT);
        RecipeCandidatePanel panel = new RecipeCandidatePanel(font, preview,
                (node, recipe) -> selected.set(recipe), (node, recipe) -> {},
                node -> {}, node -> {}, preferences, () -> {}, () -> true);
        PlanTreeNode leaf = new PlanTreeNode(
                IngredientKey.of(new ItemStack(Items.DIAMOND)),
                new ItemStack(Items.DIAMOND), 1, 1, null);
        String preferenceKey = CraftingResolver.preferenceKey(leaf.key.stack(1)).toString();

        panel.open(leaf, Set.of());

        assertTrue(panel.isOpen());
        assertTrue(panel.visibleCandidates().isEmpty());
        panel.selectNoRecipe();

        assertFalse(panel.isOpen());
        assertNull(selected.get());
        assertEquals(RecipeChoice.NoRecipe.INSTANCE,
                CraftingPlanPreferences.load(preferences).recipeChoices().get(preferenceKey));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("test", path);
    }
}
