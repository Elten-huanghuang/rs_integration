package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.tree.RecipePreviewRenderer;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.huanghuang.rsintegration.util.UIRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import com.huanghuang.rsintegration.crafting.MachineSelectionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import java.util.Optional;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 经过真实绘制与点击命中路径，验证入口不会互相抢占，且查看不会偷偷改路线。 */
class PlanDetailsPanelTest extends BootstrapTest {
    @TempDir Path directory;
    private final Font font = mock(Font.class);
    private final RecipeCandidatePanel recipes = mock(RecipeCandidatePanel.class);
    private final GuiGraphics graphics = mock(GuiGraphics.class);
    private final PlanDetailsPanel.MachineControl machines = mock(PlanDetailsPanel.MachineControl.class);
    private final AtomicReference<ItemStack> selectedMaterial = new AtomicReference<>();
    private final AtomicReference<PlanTreeNode> located = new AtomicReference<>();
    private final AtomicReference<ItemStack> bookmarked = new AtomicReference<>();
    private final AtomicInteger rechecks = new AtomicInteger();
    private final AtomicBoolean waiting = new AtomicBoolean();
    private PlanTreeNode root;
    private PlanTreeNode material;
    private PlanResponse plan;
    private PlanDetailsPanel panel;
    private PlanDetailsPanel.Bounds bounds;

    @BeforeEach
    void setup() {
        when(font.width(anyString())).thenAnswer(call -> call.<String>getArgument(0).length() * 6);
        when(font.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(call -> {
            String value = call.getArgument(0);
            return value.substring(0, Math.min(value.length(), Math.max(0, call.<Integer>getArgument(1) / 6)));
        });
        when(font.plainSubstrByWidth(anyString(), anyInt(), anyBoolean()))
                .thenAnswer(call -> call.getArgument(0));
        when(graphics.pose()).thenReturn(new PoseStack());
        when(recipes.searchBox()).thenReturn(mock(EditBox.class));
        root = node(Items.CRAFTING_TABLE);
        root.depth = 0;
        material = node(Items.OAK_PLANKS);
        material.materialLockKey = "table#planks";
        material.materialOptions = List.of(new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.BIRCH_PLANKS));
        root.children.add(material);
        plan = new PlanResponse(false, "table", root.displayStack, List.of(),
                Map.of(material.key, new PlanResponse.Availability(4, 1)), List.of(), "minecraft:crafting_table");
        panel = new PlanDetailsPanel(font, recipes, value -> value,
                (owner, stack) -> selectedMaterial.set(stack), located::set,
                (stack, count) -> bookmarked.set(stack), () -> {}, waiting::get,
                rechecks::incrementAndGet, recipe -> {}, machines);
        panel.update(plan, root, List.of());
        panel.open(material);
        panel.layout(720, 500, 40, 400);
        bounds = PlanDetailsPanel.layoutBounds(720, 500, 40, 400, 270);
        render();
    }

    @Test
    void materialAndRecipeEntrancesCoexistOnTheSameNode() {
        verify(recipes, never()).layoutEmbedded(anyInt(), anyInt(), anyInt(), anyInt());
        // 材料种类行，然后选择第二种变体；不经过配方卡片的点击入口。
        panel.mouseClicked(bounds.x() + 60, bounds.y() + 130, 0);
        render();
        panel.mouseClicked(bounds.x() + 60, bounds.y() + 186, 0);
        assertNotNull(selectedMaterial.get());
        assertEquals(Items.BIRCH_PLANKS, selectedMaterial.get().getItem());
        verify(recipes, never()).mouseClicked(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void switchingToTheMaterialBillOnlyNavigates() {
        clickTab(1);
        panel.mouseClicked(bounds.x() + 40, bounds.y() + 99, 0);
        assertSame(material, located.get());
        assertNull(selectedMaterial.get());
        verify(recipes, never()).selectNoRecipe();
        verify(recipes, never()).mouseClicked(anyDouble(), anyDouble(), anyInt());
    }

    @Test
    void locatingAConditionPreservesItsListAndInventoryUpdatesKeepTheSameRows() {
        material.warnings = List.of(Component.literal("Need research"));
        panel.update(plan, root, List.of());
        panel.showConditions();
        panel.searchBox().setValue("research");
        render();
        clearInvocations(recipes);

        panel.mouseClicked(bounds.x() + 40, bounds.y() + 99, 0);
        assertSame(material, located.get());
        panel.locate(material);
        panel.updateInventory(plan.withMaterialAvailability(
                Map.of(material.key, new PlanResponse.Availability(4, 3))), List.of());
        assertEquals("research", panel.searchBox().getValue());
        clearInvocations(graphics);
        render();
        verify(graphics).drawString(eq(font), eq("Need research"), anyInt(), anyInt(), anyInt(), eq(false));
        panel.mouseClicked(bounds.x() + 40, bounds.y() + 99, 0);
        assertSame(material, located.get());
        assertEquals(0, rechecks.get());
        verify(recipes, never()).open(any(), any());
        verify(recipes, never()).bind(any(), any());
    }

    @Test
    void unchangedConditionsReuseWrappedTextWhileResizingRecalculatesIt() {
        String warning = "Need research: a long prerequisite description that should wrap across several lines";
        material.warnings = List.of(Component.literal(warning));
        panel.update(plan, root, List.of());
        panel.showConditions();
        render();
        clearInvocations(font);
        render();
        verify(font, never()).plainSubstrByWidth(startsWith("Need research:"), anyInt());
        panel.layout(new PlanWorkspaceLayout.Rect(bounds.x(), bounds.y(), bounds.width() - 30, bounds.height()));
        render();
        verify(font, atLeastOnce()).plainSubstrByWidth(startsWith("Need research:"), anyInt());
    }

    @Test
    void replenishingStockRemovesTheBookmarkActionWithoutRebuildingTheTree() {
        clickTab(1);
        panel.mouseClicked(bounds.x() + bounds.width() - 20, bounds.y() + 99, 0);
        assertNotNull(bookmarked.get());
        bookmarked.set(null);
        panel.updateInventory(plan.withMaterialAvailability(
                Map.of(material.key, new PlanResponse.Availability(4, 4))), List.of());
        render();
        panel.mouseClicked(bounds.x() + bounds.width() - 20, bounds.y() + 99, 0);
        assertNull(bookmarked.get());
        // 已补齐的材料从缺料列表消失，旧位置不会再触发定位或收藏。
        assertNull(located.get());
        assertSame(material, panel.selectedNode());
    }

    @Test
    void conditionsCanBeRecheckedWithoutSubmittingACraft() {
        panel.showIssues();
        render();
        panel.mouseClicked(bounds.x() + 180, bounds.y() + 55, 0);
        render();
        panel.mouseClicked(bounds.x() + 60, bounds.y() + bounds.height() - 12, 0);
        assertEquals(1, rechecks.get());
        verify(recipes, never()).selectNoRecipe();
    }

    @Test
    void pendingPreviewBlocksVariantEditsButStillAllowsViewingLists() {
        panel.mouseClicked(bounds.x() + 60, bounds.y() + 130, 0);
        waiting.set(true);
        render();
        panel.mouseClicked(bounds.x() + 60, bounds.y() + 186, 0);
        assertNull(selectedMaterial.get());
        clickTab(1);
        panel.mouseClicked(bounds.x() + 40, bounds.y() + 99, 0);
        assertSame(material, located.get());
    }

    @Test
    void updateKeepsTheSelectedRequirementAfterChangingItsVariant() {
        panel.expectMaterial(new ItemStack(Items.BIRCH_PLANKS));
        PlanTreeNode newRoot = node(Items.CRAFTING_TABLE);
        PlanTreeNode newMaterial = node(Items.BIRCH_PLANKS);
        newMaterial.materialLockKey = material.materialLockKey;
        newRoot.children.add(newMaterial);
        panel.update(plan, newRoot, List.of());
        assertSame(newMaterial, panel.selectedNode());
    }

    @Test
    void narrowWindowsKeepTheSidebarInsideTheViewportAndLeaveRoomForTheTree() {
        for (int screenWidth : new int[]{240, 320, 426, 640, 1920}) {
            PlanDetailsPanel.Bounds value = PlanDetailsPanel.layoutBounds(screenWidth, 240, 40, 180, 480);
            assertTrue(value.x() >= screenWidth / 2);
            assertTrue(value.x() + value.width() <= screenWidth);
            assertTrue(value.y() >= 0);
            assertTrue(value.y() + value.height() <= 240);
        }
    }

    @Test
    void shortWindowsCanOpenRecipeCandidatesAsASeparatePage() {
        panel.mouseClicked(bounds.x() + 180, bounds.y() + 130, 0);
        panel.layout(480, 270, 40, 155);
        render();
        clearInvocations(recipes);
        for (int index = 0; index < 5; index++) {
            panel.mouseScrolled(300, 120, -1);
            render();
        }
        verify(recipes, atLeastOnce()).layoutEmbedded(anyInt(), anyInt(), anyInt(), intThat(height -> height > 0));
    }

    @Test
    void shortWindowsCanScrollToTheMaterialVariants() {
        panel.mouseClicked(bounds.x() + 60, bounds.y() + 130, 0);
        panel.layout(480, 270, 40, 155);
        render();
        for (int index = 0; index < 4; index++) {
            panel.mouseScrolled(300, 120, -1);
            render();
        }
        // 滚动后的第三行仍在实际可见列表内，不能只剩被裁剪的标题。
        panel.mouseClicked(300, 145, 0);
        assertNotNull(selectedMaterial.get());
        assertEquals(Items.BIRCH_PLANKS, selectedMaterial.get().getItem());
    }

    @Test
    void shortWindowsScrollToCurrentRecipeWithoutOpeningTheCandidatePage() {
        ResourceLocation current = ResourceLocation.tryParse("test:current");
        material.step = new PlanStep(current, material.displayStack, 1, List.of(), List.of(), null, 0, true);
        panel.open(material);
        panel.layout(480, 270, 40, 155);
        render();
        clearInvocations(recipes);
        for (int index = 0; index < 10; index++) {
            panel.mouseScrolled(300, 120, -1);
            render();
        }
        verify(recipes, atLeastOnce()).renderCurrentRecipe(eq(graphics), eq(material),
                anyInt(), anyInt(), intThat(width -> width > 0), intThat(height -> height > 0), anyInt(), anyInt());
        verify(recipes, never()).open(any(), any());
        verify(recipes, never()).layoutEmbedded(anyInt(), anyInt(), anyInt(), anyInt());
        clearInvocations(recipes);
        render();
        ArgumentCaptor<Integer> previewY = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> previewHeight = ArgumentCaptor.forClass(Integer.class);
        verify(recipes).renderCurrentRecipe(eq(graphics), eq(material), anyInt(), previewY.capture(),
                anyInt(), previewHeight.capture(), anyInt(), anyInt());
        PlanDetailsPanel.Bounds small = PlanDetailsPanel.layoutBounds(480, 270, 40, 155, 270);
        assertTrue(previewY.getValue() >= small.y() + 59, "滚到底部后配方必须位于固定页签下方");
        assertTrue(previewY.getValue() + previewHeight.getValue() < small.y() + small.height(),
                "小窗口也必须完整容纳当前配方预览区");
    }

    @Test
    void failedPreviewExplainsTheReasonAndKeepsThePreviousNodeAvailable() {
        panel.showPreviewFailure(List.of(Component.literal("Dependency cycle\nChoose another recipe")));
        assertSame(material, panel.selectedNode());
        clearInvocations(recipes);
        render();
        verify(recipes, never()).layoutEmbedded(anyInt(), anyInt(), anyInt(), anyInt());
        panel.mouseClicked(bounds.x() + 60, bounds.y() + 130, 0);
        clearInvocations(graphics);
        render();
        verify(graphics).drawString(eq(font), eq("Dependency cycle"), anyInt(), anyInt(), anyInt(), eq(false));
        verify(graphics).drawString(eq(font), eq("Choose another recipe"), anyInt(), anyInt(), anyInt(), eq(false));
    }

    @Test
    void realEmbeddedCandidatesRenderJeiPreviewsAndCardClicksReachTheRecipeCallback() {
        ResourceLocation current = ResourceLocation.tryParse("test:current");
        ResourceLocation alternative = ResourceLocation.tryParse("test:alternative");
        material.step = new PlanStep(current, material.displayStack, 1, List.of(),
                List.of(alternative), null, 0, true);
        RecipePreviewRenderer preview = mock(RecipePreviewRenderer.class);
        when(preview.candidateDetails(any(), any())).thenReturn(
                new RecipePreviewRenderer.CandidateDetails("recipe", ""));
        when(preview.categoryTitle(any())).thenReturn(Optional.of(Component.literal("machine")));
        when(preview.previewBounds(any())).thenReturn(new RecipePreviewRenderer.PreviewBounds(80, 40));
        when(preview.renderRecipeInArea(any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), any())).thenReturn(ItemStack.EMPTY);
        AtomicReference<ResourceLocation> chosen = new AtomicReference<>();
        RecipeCandidatePanel actual = new RecipeCandidatePanel(font, preview,
                (owner, recipe) -> chosen.set(recipe), (owner, recipe) -> chosen.set(recipe),
                owner -> {}, owner -> {}, directory.resolve("preferences.json"), () -> {}, () -> true);
        panel = new PlanDetailsPanel(font, actual, value -> value,
                (owner, stack) -> selectedMaterial.set(stack), located::set,
                (stack, count) -> {}, () -> {}, waiting::get, rechecks::incrementAndGet, recipe -> {}, machines);
        panel.update(plan, root, List.of());
        panel.open(material);
        panel.layout(720, 500, 40, 440);
        render();
        verify(preview, atLeastOnce()).renderRecipeInArea(eq(graphics), eq(font), eq(current),
                anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), eq(material.displayStack));
        verify(preview, never()).candidateDetails(any(), any());
        verify(preview, never()).previewWidthHint(any());
        panel.mouseClicked(bounds.x() + 180, bounds.y() + 130, 0);
        // 只替换需要 OpenGL 上下文的圆角绘制，仍执行真实的卡片布局、JEI 调用和命中逻辑。
        try (var shapes = mockStatic(UIRenderer.class)) {
            render();
            verify(preview, atLeastOnce()).renderRecipeInArea(eq(graphics), eq(font), eq(current),
                    anyInt(), anyInt(), intThat(width -> width > 0 && width <= bounds.width()),
                    intThat(height -> height > 0), anyInt(), anyInt(), eq(material.displayStack));
            // 首张候选在详情头部、配方搜索框之后；点击 JEI 预览区也必须选中这张卡片。
            panel.mouseClicked(bounds.x() + 60, bounds.y() + 140, 0);
            assertEquals(current, chosen.get());
            assertNull(selectedMaterial.get());
            assertTrue(actual.isOpen());
            assertTrue(CraftingPlanPreferences.load(directory.resolve("preferences.json")).recipeChoices().isEmpty());
            panel.mouseClicked(bounds.x() + 60, bounds.y() + 140, 1);
            assertTrue(actual.draftPreferences().excludedRecipes().contains(current.toString()));
            assertTrue(CraftingPlanPreferences.load(directory.resolve("preferences.json")).excludedRecipes().isEmpty());
            // 模拟试算失败后重新绑定旧节点；禁用草稿必须撤回。
            actual.open(material, Set.of());
            assertTrue(actual.draftPreferences().excludedRecipes().isEmpty());
        }
    }

    @Test
    void machineSelectionAndPolicyControlsReachTheOriginalExecutionState() {
        MachineCandidateView machine = new MachineCandidateView("minecraft:overworld", 4, 70, 9,
                new ItemStack(Items.FURNACE), MachineCandidateView.State.READY, Component.literal("ready"));
        PlanResponse withMachine = new PlanResponse(false, "table", root.displayStack, List.of(),
                plan.materials(), List.of(), plan.recipeId(), null, null, 0, 0, 0,
                List.of(), 1, null, null, null, 0, false, false, false, null,
                Set.of(), Map.of(), null, null, false, List.of(machine), Map.of(), null, List.of());
        panel.update(withMachine, root, List.of());
        clickTab(4);
        panel.mouseClicked(bounds.x() + 120, bounds.y() + 80, 0);
        verify(machines).selectMode(MachineSelectionMode.PREFERRED);
        panel.mouseClicked(bounds.x() + 40, bounds.y() + 122, 0);
        verify(machines).selectCandidate(0);
    }

    @Test
    void conditionsOnHiddenStepsRemainVisibleAndNeverBecomeMissingMaterials() {
        ResourceLocation hiddenRecipe = ResourceLocation.tryParse("test:hidden");
        PlanResponse withHiddenIssue = new PlanResponse(false, "table", root.displayStack, List.of(),
                Map.of(), List.of(), plan.recipeId(), null, null, 0, 0, 0,
                List.of(), 1, null, null, null, 0, false, false, false, null,
                Set.of(), Map.of(), null, null, true, List.of(),
                Map.of(hiddenRecipe, new PlanResponse.StepIssue(List.of(Component.literal("Research missing")), true)),
                null, List.of());
        panel.update(withHiddenIssue, root, List.of());
        panel.showIssues();
        render();
        panel.mouseClicked(bounds.x() + 180, bounds.y() + 55, 0);
        clearInvocations(graphics);
        render();
        verify(graphics).drawString(eq(font), eq("Research missing"), anyInt(), anyInt(), anyInt(), eq(false));
        clickTab(1);
        panel.mouseClicked(bounds.x() + bounds.width() - 20, bounds.y() + 99, 0);
        assertNull(bookmarked.get());
    }

    private void clickTab(int index) {
        if (index == 4) panel.showMachines();
        else if (index == 3) panel.showSteps();
        else panel.mouseClicked(bounds.x() + 5 + (bounds.width() - 12) / 3 * index + 10, bounds.y() + 55, 0);
        render();
    }

    private void render() { panel.render(graphics, 0, 0); }

    private static PlanTreeNode node(Item item) {
        ItemStack stack = new ItemStack(item);
        return new PlanTreeNode(IngredientKey.of(stack), stack, 4, 1, null);
    }
}
