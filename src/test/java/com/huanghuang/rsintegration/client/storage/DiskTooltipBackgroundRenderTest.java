package com.huanghuang.rsintegration.client.storage;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class DiskTooltipBackgroundRenderTest {
    private static final Path RESOURCES = Path.of("src/main/resources/assets/rs_integration");
    // 每种绘制路径选一个物品，避免同一套电路背景重复跑四遍。
    private static final List<String> ITEMS = List.of("resonance_storage_disk", "unified_storage_disk",
            "resonant_experience_wand", "order_terminal", "resonant_mana_pool",
            "resonant_source_tank", "rs_pickup_upgrade", "rs_void_upgrade");

    @Test
    void opaqueBackgroundsCoverInventoryAndArtworkStaysInsideTheTooltip() throws IOException {
        Map<ResourceLocation, BufferedImage> textures = new HashMap<>();
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            for (String item : ITEMS) {
                var theme = theme(item);
                for (int[] size : List.of(new int[]{300, 140}, new int[]{120, 200},
                        new int[]{320, 24}, new int[]{65, 67}, new int[]{12, 12})) {
                    var probe = new RenderProbe();
                    DiskTooltipBackground.renderBackground(probe.graphics, 0, 0,
                            size[0], size[1], theme, 23.99);
                    Blit background = probe.blits.get(0);
                    assertEquals(0, background.x());
                    assertEquals(0, background.y());
                    assertEquals(size[0], background.width());
                    assertEquals(size[1], background.height());
                    assertEquals(1, background.alpha());
                    if (!textures.containsKey(background.texture())) {
                        BufferedImage image = ImageIO.read(
                                RESOURCES.resolve(background.texture().getPath()).toFile());
                        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0,
                                image.getWidth());
                        for (int pixel : pixels) assertEquals(255, pixel >>> 24);
                        textures.put(background.texture(), image);
                    }
                    for (Blit blit : probe.blits) {
                        assertTrue(blit.x() >= 0 && blit.y() >= 0, blit.toString());
                        assertTrue(blit.x() + blit.width() <= size[0], blit.toString());
                        assertTrue(blit.y() + blit.height() <= size[1], blit.toString());
                        assertTrue(blit.sourceWidth() > 0 && blit.sourceHeight() > 0);
                        assertTrue(blit.u() >= 0 && blit.v() >= 0);
                        assertTrue(blit.u() + blit.sourceWidth() <= blit.textureWidth());
                        assertTrue(blit.v() + blit.sourceHeight() <= blit.textureHeight());
                    }
                    assertEquals(0, probe.pose.last().pose().m32());
                    for (float color : probe.tint) assertEquals(1, color);
                }
            }
        }
    }

    @Test
    void artworkChangesOverTimeAndLoops() {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            for (String item : ITEMS) {
                var first = render(item, 0);
                var moved = render(item, 3);
                var loop = render(item, 24);
                assertFalse(first.blits.isEmpty());
                assertNotEquals(first.blits, moved.blits);
                assertEquals(first.blits, loop.blits);
            }
        }
    }

    @Test
    void opaqueBordersSitAboveTheBackgroundAndRegularTooltipText() {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            for (String item : ITEMS) {
                var probe = render(item, 0);
                var borders = probe.fills.stream().filter(fill -> fill.depth() > 400).toList();
                assertEquals(4, borders.size());
                for (Fill border : borders) {
                    assertEquals(255, border.first() >>> 24);
                    assertEquals(255, border.last() >>> 24);
                }
            }
        }
    }

    @Test
    void poseAndColorAreRestoredIfArtworkOrBorderRenderingFails() {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            for (boolean borderFailure : new boolean[]{false, true}) {
                var probe = new RenderProbe();
                probe.failOnBorder = borderFailure;
                probe.failOnBlit = borderFailure ? 0 : 3;
                assertThrows(IllegalStateException.class, () -> DiskTooltipBackground.renderBackground(
                        probe.graphics, 0, 0, 300, 140, theme("resonance_storage_disk"), 0));
                assertEquals(0, probe.pose.last().pose().m32());
                for (float color : probe.tint) assertEquals(1, color);
            }
        }
    }

    private static DiskTooltipBackground.Theme theme(String item) {
        return DiskTooltipBackground.themeFor(new ResourceLocation("rs_integration", item));
    }

    private static RenderProbe render(String item, double time) {
        var probe = new RenderProbe();
        DiskTooltipBackground.renderBackground(probe.graphics, 0, 0, 300, 140, theme(item), time);
        return probe;
    }

    private record Blit(ResourceLocation texture, int x, int y, int width, int height,
                        float u, float v, int sourceWidth, int sourceHeight,
                        int textureWidth, int textureHeight, float alpha) {}

    private record Fill(float depth, int first, int last) {}

    /** 只记录真实绘制调用，不在单元测试里重新实现渲染器或导出预览。 */
    private static final class RenderProbe {
        private final GuiGraphics graphics = mock(GuiGraphics.class);
        private final PoseStack pose = new PoseStack();
        private final List<Blit> blits = new ArrayList<>();
        private final List<Fill> fills = new ArrayList<>();
        private final float[] tint = {1, 1, 1, 1};
        private int failOnBlit;
        private boolean failOnBorder;

        private RenderProbe() {
            when(graphics.pose()).thenReturn(pose);
            doAnswer(call -> {
                for (int i = 0; i < tint.length; i++) tint[i] = call.getArgument(i);
                return null;
            }).when(graphics).setColor(anyFloat(), anyFloat(), anyFloat(), anyFloat());
            doAnswer(call -> {
                assertEquals(400, pose.last().pose().m32());
                if (blits.size() + 1 == failOnBlit) throw new IllegalStateException("绘制失败");
                blits.add(new Blit(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4), call.getArgument(5),
                        call.getArgument(6), call.getArgument(7), call.getArgument(8),
                        call.getArgument(9), call.getArgument(10), tint[3]));
                return null;
            }).when(graphics).blit(any(ResourceLocation.class), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyFloat(), anyFloat(), anyInt(), anyInt(), anyInt(), anyInt());
            doAnswer(call -> {
                fill(call.getArgument(4), call.getArgument(4));
                return null;
            }).when(graphics).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            doAnswer(call -> {
                fill(call.getArgument(5), call.getArgument(6));
                return null;
            }).when(graphics).fillGradient(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        }

        private void fill(int first, int last) {
            if (failOnBorder) throw new IllegalStateException("边框绘制失败");
            fills.add(new Fill(pose.last().pose().m32(), first, last));
        }
    }
}
