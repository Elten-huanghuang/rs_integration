package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.plan.PlanWorkspaceLayout.Rect;
import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeLayout;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import com.mojang.blaze3d.vertex.PoseStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlanWorkspaceInteractionTest extends BootstrapTest {
    private final Font font = mock(Font.class);
    private final GuiGraphics graphics = mock(GuiGraphics.class);

    @BeforeEach
    void setupGraphics() { when(graphics.pose()).thenReturn(new PoseStack()); }

    @Test
    void materialTableFiltersLocatesAndBookmarksOnlyTheNetShortage() {
        when(font.width(anyString())).thenAnswer(call -> call.<String>getArgument(0).length() * 6);
        when(font.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(call -> call.getArgument(0));
        ItemStack iron = new ItemStack(Items.IRON_INGOT), wood = new ItemStack(Items.OAK_PLANKS);
        IngredientKey ironKey = IngredientKey.of(iron), woodKey = IngredientKey.of(wood);
        PlanResponse plan = new PlanResponse(false, "test", iron, List.of(), Map.of(
                ironKey, new PlanResponse.Availability(10, 4, 6),
                woodKey, new PlanResponse.Availability(4, 0, 0)), List.of(), "test:recipe");
        AtomicReference<IngredientKey> located = new AtomicReference<>();
        AtomicLong bookmarked = new AtomicLong();
        PlanMaterialTable table = new PlanMaterialTable(font, located::set,
                (stack, amount) -> bookmarked.addAndGet(amount), () -> {});
        table.update(plan);
        Rect bounds = new Rect(20, 140, 920, 330);
        table.render(graphics, bounds, 0, 0);
        table.click(60, 215, 0);
        assertEquals(ironKey, located.get());
        table.click(60, 215, 2);
        assertEquals(6, bookmarked.get());
        table.click(60, 245, 2);
        assertEquals(6, bookmarked.get(), "已安排补做且净缺口为0的材料不能误收藏");
        table.search("minecraft:oak_planks");
        table.render(graphics, bounds, 0, 0);
        table.click(60, 215, 0);
        assertEquals(woodKey, located.get());
        located.set(null);
        table.click(110, 155, 0); // 仅看缺料，与搜索共同过滤后为空。
        table.render(graphics, bounds, 0, 0);
        table.click(60, 215, 0);
        assertNull(located.get(), "过滤前的旧命中区必须失效");
    }

    @Test
    void minimapClickAndDragUseTheRealTreeProjection() {
        ItemStack item = new ItemStack(Items.CRAFTING_TABLE);
        PlanTreeNode root = new PlanTreeNode(IngredientKey.of(item), item, 1, 0, null);
        root.children.add(new PlanTreeNode(IngredientKey.of(new ItemStack(Items.OAK_PLANKS)), new ItemStack(Items.OAK_PLANKS), 4, 1, null));
        PlanTreeLayout layout = new PlanTreeLayout();
        layout.ensureLayout(root);
        PlanTreeMiniMap map = new PlanTreeMiniMap();
        Rect viewport = new Rect(20, 140, 600, 330);
        map.render(graphics, font, layout, viewport, 50, 150, 1, root);
        AtomicReference<double[]> target = new AtomicReference<>();
        assertTrue(map.click(570, 435, 0, (x, y) -> target.set(new double[]{x, y})));
        double[] first = target.get();
        assertNotNull(first);
        assertTrue(map.drag(580, 445, (x, y) -> target.set(new double[]{x, y})));
        assertTrue(target.get()[0] > first[0]);
        assertTrue(target.get()[1] > first[1]);
        map.release();
        assertFalse(map.drag(580, 445, (x, y) -> fail("释放后不能继续拖动")));
        map.render(graphics, font, layout, new Rect(0, 0, 100, 60), 0, 0, 1, root);
        assertFalse(map.contains(570, 435), "隐藏后的旧缩略图不能吞掉点击");
    }

    @Test
    void largeMinimapReusesProjectionUntilLayoutOrSizeChangesAndMergesOverlappingPixels() {
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(graphics).drawManaged(any());
        ItemStack item = new ItemStack(Items.IRON_INGOT);
        PlanTreeNode root = new PlanTreeNode(IngredientKey.of(item), item, 1, 0, null);
        for (int i = 0; i < 1057; i++) root.children.add(new PlanTreeNode(IngredientKey.of(item), item, 1, 1, null));
        PlanTreeLayout layout = spy(new PlanTreeLayout());
        layout.ensureLayout(root);
        PlanTreeMiniMap map = new PlanTreeMiniMap();
        Rect viewport = new Rect(20, 140, 600, 330);
        map.render(graphics, font, layout, viewport, 50, 150, 1, root);
        verify(graphics, atMost(180)).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        clearInvocations(layout, graphics);
        map.render(graphics, font, layout, viewport, -300, 70, 1.5, root.children.get(0));
        verify(layout, never()).boxes();
        verify(layout, atMost(1)).boxFor(any());
        verify(graphics, atMost(180)).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        root.expanded = false;
        layout.markDirty();
        layout.ensureLayout(root);
        clearInvocations(layout);
        map.render(graphics, font, layout, viewport, 0, 0, 1, root);
        verify(layout).boxes();
        clearInvocations(layout);
        map.render(graphics, font, layout, new Rect(20, 140, 800, 420), 0, 0, 1, root);
        verify(layout).boxes();
    }

    @Test
    void allSupportedGuiSizesKeepPrimaryActionsAndCanvasInsideTheFrame() {
        for (int[] size : new int[][]{{320, 240}, {427, 240}, {480, 270}, {720, 360}, {960, 540}, {1920, 1080}}) {
            for (boolean open : new boolean[]{false, true}) {
                PlanWorkspaceLayout layout = PlanWorkspaceLayout.create(size[0], size[1], open, 290);
                assertTrue(layout.content().height() > 0);
                assertTrue(layout.tree().width() > 0);
                assertTrue(layout.content().bottom() <= layout.footer().y());
                assertTrue(layout.confirm().right() < layout.cancel().x());
                assertTrue(layout.cancel().right() <= layout.frame().right());
                assertTrue(layout.confirm().y() >= layout.footer().y());
                assertTrue(layout.confirm().bottom() <= layout.frame().bottom());
                if (open) assertTrue(layout.tree().right() < layout.inspector().x());
            }
        }
    }
}
