package com.huanghuang.rsintegration.crafting.tree;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.PlanUiConfig;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanTreeThemeLayoutTest extends BootstrapTest {
    @AfterEach
    void unloadTheme() { PlanUiConfig.SPEC.setConfig(null); }

    @Test
    void switchingToCompactNodesRebuildsGeometryAndKeepsHitTestingAligned() {
        PlanTreeNode root = node();
        PlanTreeLayout layout = new PlanTreeLayout();
        layout.ensureLayout(root);
        int namedWidth = layout.boxFor(root).w();
        assertEquals(layout.boxFor(root).centerX(), layout.boxFor(root).connectorX());
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("layout.showNodeNames", false);
        PlanUiConfig.SPEC.setConfig(config);
        layout.ensureLayout(root);
        var compact = layout.boxFor(root);
        assertTrue(compact.w() < namedWidth);
        assertEquals(compact.itemCenterX(), compact.connectorX());
        assertSame(root, layout.hitTest(compact.centerX(), compact.y() + 10).node());
    }

    @Test
    void wideLabelsDoNotOverlapSiblingNodesAndCollapsedChildrenStayHidden() {
        CommentedConfig config = CommentedConfig.inMemory();
        config.set("layout.nodeLabelWidth", 180);
        PlanUiConfig.SPEC.setConfig(config);
        PlanTreeNode root = node();
        for (int index = 0; index < 24; index++) {
            PlanTreeNode child = node();
            child.children.add(node());
            child.expanded = false;
            root.children.add(child);
        }
        PlanTreeLayout layout = new PlanTreeLayout();
        layout.ensureLayout(root);
        assertEquals(25, layout.boxes().size());
        int previousRight = -1;
        for (PlanTreeNode child : root.children) {
            var box = layout.boxFor(child);
            assertTrue(box.x() > previousRight);
            assertTrue(box.connectorX() >= box.x() && box.connectorX() < box.x() + box.w());
            previousRight = box.x() + box.w();
        }
    }

    @Test
    void panningAcrossAWideTreeDrawsOnlyVisibleBranchesAndReusesCycleChecks() {
        PlanTreeNode root = node();
        PlanTreeNode first = spy(node());
        root.children.add(first);
        for (int i = 1; i < 1057; i++) root.children.add(node());
        PlanTreeLayout layout = new PlanTreeLayout();
        layout.ensureLayout(root);
        PlanTreeLayout.Box box = layout.boxFor(first);
        Font font = mock(Font.class);
        GuiGraphics graphics = mock(GuiGraphics.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(graphics).drawManaged(any());
        PlanTreeRenderer renderer = new PlanTreeRenderer(font);
        SelectedPath path = new SelectedPath();
        clearInvocations(first);
        for (int frame = 0; frame < 2; frame++) {
            clearInvocations(graphics);
            renderer.render(graphics, layout, path, null, box.x() - 3, 54, box.x() + box.w() + 3, 110);
            verify(graphics, atMost(100)).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        }
        verify(first).hasAlternatives();
        layout.markDirty();
        layout.ensureLayout(root);
        clearInvocations(first);
        renderer.render(graphics, layout, path, null, box.x() - 3, 54, box.x() + box.w() + 3, 110);
        verify(first).hasAlternatives();
    }

    private static PlanTreeNode node() {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        return new PlanTreeNode(IngredientKey.of(stack), stack, 1, 0, null);
    }
}
