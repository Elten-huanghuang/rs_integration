package com.huanghuang.rsintegration.crafting.tree;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.huanghuang.rsintegration.config.PlanUiConfig;
import com.huanghuang.rsintegration.crafting.plan.PlanStep;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

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
        verify(first, never()).containsCycle();
        verify(first, never()).hasAlternatives();
        layout.markDirty();
        layout.ensureLayout(root);
        clearInvocations(first);
        renderer.render(graphics, layout, path, null, box.x() - 3, 54, box.x() + box.w() + 3, 110);
        verify(first, never()).containsCycle();
        verify(first, never()).hasAlternatives();
    }

    @Test
    void indexedNodesConnectorsAndHitTestsMatchFullGeometryAcrossViewports() {
        Random random = new Random(71681);
        PlanTreeNode root = node();
        List<PlanTreeNode> nodes = new ArrayList<>(List.of(root));
        for (int i = 0; i < 600; i++) {
            PlanTreeNode child = node();
            if (i % 3 == 0) child.step = step(true);
            child.expanded = i % 7 != 0;
            nodes.get(random.nextInt(nodes.size())).children.add(child);
            nodes.add(child);
        }
        PlanTreeLayout layout = new PlanTreeLayout();
        for (boolean named : List.of(true, false)) {
            CommentedConfig config = CommentedConfig.inMemory();
            config.set("layout.showNodeNames", named);
            config.set("layout.showLeafNames", false);
            PlanUiConfig.SPEC.setConfig(config);
            layout.ensureLayout(root);
            List<PlanTreeLayout.Connector> allLines = referenceConnectors(layout);
            for (int pass = 0; pass < 180; pass++) {
                PlanTreeLayout.Box anchor = layout.boxes().get(random.nextInt(layout.boxes().size()));
                int left = anchor.x() + random.nextInt(300) - 150;
                int top = anchor.y() + random.nextInt(200) - 100;
                int right = left + 1 + random.nextInt(500), bottom = top + 1 + random.nextInt(180);
                List<PlanTreeLayout.Box> visible = new ArrayList<>();
                layout.forEachVisibleBox(left, top, right, bottom, visible::add);
                var expected = layout.boxes().stream().filter(box -> intersects(left, top, right, bottom,
                        box.x() - 8, box.y() - 4, box.x() + box.w() + 4, box.bottom() + 8)).toList();
                assertEquals(new HashSet<>(expected), new HashSet<>(visible));
                assertEquals(expected.size(), visible.size());
                for (int width = 1; width <= 4; width++) {
                    int lineWidth = width;
                    List<PlanTreeLayout.Connector> lines = new ArrayList<>();
                    layout.forEachVisibleConnector(left, top, right, bottom, width, lines::add);
                    var expectedLines = allLines.stream().filter(line -> intersects(left, top, right, bottom,
                            line.left(lineWidth), line.top(lineWidth), line.right(lineWidth), line.bottom(lineWidth))).toList();
                    assertEquals(new HashSet<>(expectedLines), new HashSet<>(lines));
                    assertEquals(expectedLines.size(), lines.size());
                }
                var expectedHit = layout.boxes().stream().filter(box -> box.contains(left, top)).findFirst().orElse(null);
                assertSame(expectedHit, layout.hitTest(left, top));
            }
            for (PlanTreeLayout.Box box : layout.boxes()) {
                assertSame(box, layout.hitTest(box.x(), box.y()));
                assertSame(box, layout.hitTest(box.x() + box.w() - 1, box.bottom() - 1));
                assertNull(layout.hitTest(box.x() + box.w(), box.y()));
                assertNull(layout.hitTest(box.x(), box.bottom()));
            }
        }
    }

    @Test
    void offscreenLargeTreeDoesNotAskRendererToEnumerateNodesOrChildren() {
        PlanTreeNode root = node();
        for (int i = 0; i < 20000; i++) root.children.add(node());
        PlanTreeLayout layout = spy(new PlanTreeLayout());
        layout.ensureLayout(root);
        long revision = layout.revision();
        GuiGraphics graphics = mock(GuiGraphics.class);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(graphics).drawManaged(any());
        clearInvocations(layout);
        new PlanTreeRenderer(mock(Font.class)).render(graphics, layout, new SelectedPath(), null,
                -10000, -10000, -9000, -9000);
        verify(layout, never()).boxes();
        verify(layout, never()).boxFor(any());
        verify(graphics, never()).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        assertNull(layout.hitTest(-10000, -10000));
        assertEquals(revision, layout.revision());
        root.expanded = false;
        layout.markDirty();
        layout.ensureLayout(root);
        assertEquals(1, layout.boxes().size());
        List<PlanTreeLayout.Connector> lines = new ArrayList<>();
        layout.forEachVisibleConnector(0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE, 4, lines::add);
        assertTrue(lines.isEmpty(), "折叠后不能复用上一代连线");
    }

    @Test
    void cycleSnapshotIncludesCollapsedChildrenAndRefreshesWithLayout() {
        PlanTreeNode root = spy(node());
        root.step = step(true);
        List<PlanTreeNode> nodes = new ArrayList<>(List.of(root));
        for (int i = 1; i < 128; i++) {
            PlanTreeNode child = spy(node());
            child.step = step(true);
            nodes.get(i - 1).children.add(child);
            nodes.add(child);
        }
        PlanTreeLayout layout = new PlanTreeLayout();
        layout.ensureLayout(root);
        for (PlanTreeNode node : nodes) {
            assertTrue(layout.hasAlternatives(node));
            verify(node, never()).containsCycle();
        }
        nodes.get(50).expanded = false;
        nodes.get(127).cycle = true;
        layout.markDirty();
        layout.ensureLayout(root);
        assertFalse(layout.hasAlternatives(root), "折叠不能隐藏循环分支的切换限制");
        nodes.get(127).cycle = false;
        layout.markDirty();
        layout.ensureLayout(root);
        assertTrue(layout.hasAlternatives(root));
        root.step = step(false);
        clearInvocations(root);
        assertFalse(root.hasAlternatives());
        verify(root, never()).containsCycle();
    }

    private static PlanStep step(boolean alternatives) {
        return new PlanStep(new ResourceLocation("test:recipe"), new ItemStack(Items.IRON_INGOT), 1,
                List.of(), List.of(), null, 0, alternatives);
    }

    private static List<PlanTreeLayout.Connector> referenceConnectors(PlanTreeLayout layout) {
        List<PlanTreeLayout.Connector> lines = new ArrayList<>();
        for (PlanTreeLayout.Box parent : layout.boxes()) {
            if (!parent.node().expanded || parent.node().children.isEmpty()) continue;
            int busY = parent.bottom() + (PlanTreeLayout.LEVEL_GAP - parent.h()) / 2;
            int minX = parent.connectorX(), maxX = minX;
            for (PlanTreeNode child : parent.node().children) {
                PlanTreeLayout.Box box = layout.boxFor(child);
                if (box == null) continue;
                minX = Math.min(minX, box.connectorX());
                maxX = Math.max(maxX, box.connectorX());
                lines.add(new PlanTreeLayout.Connector(box.connectorX(), busY, box.connectorX(), box.y()));
            }
            lines.add(new PlanTreeLayout.Connector(parent.connectorX(), parent.bottom(), parent.connectorX(), busY));
            if (maxX > minX) lines.add(new PlanTreeLayout.Connector(minX, busY, maxX, busY));
        }
        return lines;
    }

    private static boolean intersects(int left, int top, int right, int bottom,
                                       int x1, int y1, int x2, int y2) {
        return x2 > left && x1 < right && y2 > top && y1 < bottom;
    }

    private static PlanTreeNode node() {
        ItemStack stack = new ItemStack(Items.IRON_INGOT);
        return new PlanTreeNode(IngredientKey.of(stack), stack, 1, 0, null);
    }
}
