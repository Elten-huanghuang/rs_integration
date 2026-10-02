package com.huanghuang.rsintegration.crafting.plan;

import com.huanghuang.rsintegration.crafting.tree.IngredientKey;
import com.huanghuang.rsintegration.crafting.tree.PlanTreeNode;
import com.huanghuang.rsintegration.crafting.tree.RecipePreviewRenderer;
import com.huanghuang.rsintegration.testutil.BootstrapTest;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecipeCandidatePanelRenderTest extends BootstrapTest {
    @Test
    void panelUsesOpaqueGuiFrameAndFinishesEachLayerBeforeDrawingRecipeContents() {
        Font font = mock(Font.class);
        when(font.plainSubstrByWidth(anyString(), anyInt())).thenAnswer(call -> call.getArgument(0));
        RecipePreviewRenderer preview = mock(RecipePreviewRenderer.class);
        ResourceLocation id = new ResourceLocation("test", "scroll");
        ItemStack result = new ItemStack(Items.INK_SAC);
        when(preview.candidateDetails(id, result)).thenReturn(new RecipePreviewRenderer.CandidateDetails("卷轴", "卷轴"));
        when(preview.categoryTitle(id)).thenReturn(Optional.of(Component.literal("炼金锅")));
        PoseStack pose = new PoseStack();
        GuiGraphics graphics = mock(GuiGraphics.class);
        when(graphics.pose()).thenReturn(pose);
        // 将 GUI 矩形栅格化，检查完整外框和实心底板，而不是依赖一组颜色常量。
        BufferedImage pixels = new BufferedImage(656, 350, BufferedImage.TYPE_INT_ARGB);
        boolean[] pendingSurface = {false};
        doAnswer(call -> { pendingSurface[0] = false; return null; }).when(graphics).flush();
        when(preview.renderRecipeInArea(any(), any(), any(), anyInt(), anyInt(), anyInt(), anyInt(),
                anyInt(), anyInt(), any())).thenAnswer(call -> {
            assertFalse(pendingSurface[0], "背景批次必须先提交，不能覆盖之后绘制的 JEI 配方");
            return ItemStack.EMPTY;
        });
        doAnswer(call -> {
            pendingSurface[0] = true;
            int left = call.getArgument(0), top = call.getArgument(1);
            int right = call.getArgument(2), bottom = call.getArgument(3), color = call.getArgument(4);
            for (int y = Math.max(0, top); y < Math.min(pixels.getHeight(), bottom); y++) {
                for (int x = Math.max(0, left); x < Math.min(pixels.getWidth(), right); x++) pixels.setRGB(x, y, color);
            }
            return null;
        }).when(graphics).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        try (var edits = mockConstruction(EditBox.class)) {
            var panel = new RecipeCandidatePanel(font, preview, (node, recipe) -> fail("绘制不得选择配方"));
            var step = new PlanStep(id, result, 1, List.of(new ItemStack(Items.PAPER)), List.of(id));
            panel.open(new PlanTreeNode(IngredientKey.of(result), result, 1, 0, step), Set.of());
            panel.layout(656, 350, 40);
            panel.render(graphics, 0, 0);
            var bounds = RecipeCandidatePickerModel.layout(656, 350, 40);
            int border = pixels.getRGB(bounds.x(), bounds.y());
            assertEquals(255, border >>> 24);
            for (int y = bounds.y(); y < bounds.y() + bounds.height(); y++) {
                assertEquals(border, pixels.getRGB(bounds.x(), y));
                assertEquals(border, pixels.getRGB(bounds.x() + bounds.width() - 1, y));
            }
            for (int x = bounds.x(); x < bounds.x() + bounds.width(); x++) {
                assertEquals(border, pixels.getRGB(x, bounds.y()));
                assertEquals(border, pixels.getRGB(x, bounds.y() + bounds.height() - 1));
            }
            assertEquals(255, pixels.getRGB(bounds.x() + 3, bounds.y() + 42) >>> 24);
            assertNotEquals(border, pixels.getRGB(bounds.x() + 3, bounds.y() + 42));
            assertTrue(panel.contentRight(636) < bounds.x());
            assertTrue(panel.contains(bounds.x() + 1, bounds.y() + 1));
            assertFalse(panel.contains(panel.contentRight(636), bounds.y()));
            assertEquals(0f, pose.last().pose().m32(), "绘制后必须恢复主视图层级");
            verify(preview).renderRecipeInArea(any(), any(), eq(id), anyInt(), anyInt(), anyInt(), anyInt(),
                    anyInt(), anyInt(), eq(result));
            verify(graphics).disableScissor();
            assertFalse(pendingSurface[0], "离开面板前必须提交自身背景，不污染下一层绘制");
        }
    }
}
