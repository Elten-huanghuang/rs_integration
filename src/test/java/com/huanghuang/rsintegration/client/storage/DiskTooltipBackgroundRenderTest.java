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
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private static final List<String> ITEMS = List.of("resonance_storage_disk", "resonance_network_generator", "unified_storage_disk",
            "resonant_experience_wand", "order_terminal", "resonant_mana_pool",
            "resonant_source_tank", "rs_pickup_upgrade", "rs_void_upgrade");

    @Test
    void opaqueBackgroundsCoverInventoryAndArtworkStaysInsideTheTooltip() throws IOException {
        Map<ResourceLocation, BufferedImage> textures = new HashMap<>();
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            for (String item : ITEMS) {
                var theme = theme(item);
                for (int[] size : List.of(new int[]{300, 140}, new int[]{180, 60}, new int[]{94, 54}, new int[]{120, 200},
                        new int[]{320, 24}, new int[]{65, 67}, new int[]{12, 12})) {
                    var probe = new RenderProbe();
                    DiskTooltipBackground.renderBackground(probe.graphics, 0, 0,
                            size[0], size[1], theme, 23.99);
                    Blit background = probe.blits.get(0);
                    assertEquals(0, background.x());
                    assertEquals(0, background.y());
                    boolean guixuFrame = item.equals("unified_storage_disk")
                            && GuixuTooltipArt.supportsFrame(size[0], size[1]);
                    boolean foodFrame = item.equals("order_terminal")
                            && OrderTerminalTooltipArt.supportsFrame(size[0], size[1]);
                    assertEquals(size[0] + (guixuFrame ? 5 : 0), background.width());
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
                        boolean ornament = blit.depth() > 400;
                        int leftOutset = ornament ? (foodFrame ? 25 : guixuFrame ? 20 : 17) : 0;
                        int topOutset = ornament ? (foodFrame ? 5 : guixuFrame ? 11 : 6) : 0;
                        int rightOutset = guixuFrame ? (ornament ? 7 : 5) : (ornament ? 17 : 0);
                        assertTrue(blit.x() >= -leftOutset && blit.y() >= -topOutset, blit.toString());
                        assertTrue(blit.x() + blit.width() <= size[0] + rightOutset, blit.toString());
                        assertTrue(blit.y() + blit.height() <= size[1] + (guixuFrame && ornament ? 10 : 0), blit.toString());
                        assertTrue(blit.sourceWidth() > 0 && blit.sourceHeight() > 0);
                        assertTrue(blit.u() >= 0 && blit.v() >= 0);
                        assertTrue(blit.u() + blit.sourceWidth() <= blit.textureWidth());
                        assertTrue(blit.v() + blit.sourceHeight() <= blit.textureHeight());
                    }
                    assertEquals(0, probe.pose.last().pose().m32());
                    assertTrue(probe.scissors.isEmpty());
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
                assertEquals(item.equals("resonance_storage_disk") ? 9
                        : item.equals("unified_storage_disk") || item.equals("order_terminal") ? 0 : 4, borders.size());
                for (Fill border : borders) {
                    assertEquals(255, border.first() >>> 24);
                    assertEquals(255, border.last() >>> 24);
                }
            }
        }
    }

    @Test
    void resonanceDiskUsesItsOwnArtistFrameAndTheFrameDoesNotFlicker() throws IOException {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            var normal = render("resonance_storage_disk", 0);
            var glitch = render("resonance_storage_disk", 1.64);
            var frame = normal.blits.stream().filter(blit -> blit.depth() > 400).toList();
            assertEquals(3, frame.size());
            assertEquals(frame, glitch.blits.stream().filter(blit -> blit.depth() > 400).toList());
            assertEquals(List.of("left", "top", "right"), frame.stream().map(blit ->
                    blit.texture().getPath().replace("textures/gui/disk_tooltip/resonance_frame_", "")
                            .replace(".png", "")).toList());
            for (Blit part : frame) {
                BufferedImage image = ImageIO.read(RESOURCES.resolve(part.texture().getPath()).toFile());
                assertEquals(image.getWidth(), part.sourceWidth());
                assertEquals(image.getHeight(), part.sourceHeight());
                assertEquals(Math.round(image.getWidth() * 1.25F), part.width());
                assertEquals(part.texture().getPath().endsWith("_top.png")
                        ? Math.round(image.getHeight() * 1.25F) : Math.round(140 * 2.0F / 3.0F), part.height());
                assertEquals(1, part.red());
                assertEquals(1, part.green());
                assertEquals(1, part.blue());
                assertEquals(1, part.alpha());
            }
            for (String item : ITEMS) {
                if (!item.equals("resonance_storage_disk") && !item.equals("unified_storage_disk")
                        && !item.equals("order_terminal")) {
                    assertTrue(render(item, 1.64).blits.stream().noneMatch(blit -> blit.depth() > 400));
                }
            }
        }
    }

    @Test
    void terminalPreservesArtistDetailsAndOnlyExtendsStraightSections() throws IOException {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            var probe = render("order_terminal", 0);
            var frame = probe.blits.stream().filter(blit -> blit.depth() > 400).toList();
            assertEquals(7, frame.size());
            assertEquals(frame, render("order_terminal", 1.64).blits.stream()
                    .filter(blit -> blit.depth() > 400).toList());
            assertTrue(probe.fills.stream().noneMatch(fill -> fill.depth() > 400));
            assertEquals(6, probe.blits.stream().filter(blit -> blit.texture().getPath()
                    .endsWith("food_motifs.png")).count());
            assertEquals(1, probe.fills.size()); // 只保留就绪灯，没有为厨房背景添加 CRT 闪烁。
            for (Blit part : frame) {
                assertTrue(part.texture().getPath().endsWith("order_terminal_frame.png"));
                assertEquals(119, part.textureWidth());
                assertEquals(67, part.textureHeight());
                assertEquals(1, part.red());
                assertEquals(1, part.green());
                assertEquals(1, part.blue());
                assertEquals(1, part.alpha());
                if (part.width() != part.sourceWidth()) {
                    assertEquals(1, part.sourceWidth());
                    assertEquals(part.sourceHeight(), part.height());
                } else if (part.height() != part.sourceHeight()) {
                    assertEquals(1, part.sourceHeight());
                }
            }

            // 紧凑的四行普通提示也要使用外框，不能只在 Shift 展开时才显示。
            var compact = new RenderProbe();
            int compactHeight = 4 * 10 + 6 + OrderTerminalTooltipArt.HEADER_SPACE
                    + OrderTerminalTooltipArt.BOTTOM_SPACE;
            DiskTooltipBackground.renderBackground(compact.graphics, 0, 0, 180, compactHeight,
                    theme("order_terminal"), 0);
            assertTrue(compact.blits.stream().anyMatch(blit -> blit.depth() > 400));
            assertTrue(compact.fills.stream().noneMatch(fill -> fill.depth() > 400));

            // 原图尺寸应逐像素还原画师整张素材，确保切片没有遗漏、重复或错位。
            var natural = new RenderProbe();
            DiskTooltipBackground.renderBackground(natural.graphics, 0, 0, 94, 62,
                    theme("order_terminal"), 0);
            var source = ImageIO.read(RESOURCES.resolve(
                    "textures/gui/disk_tooltip/order_terminal_frame.png").toFile());
            var reconstructed = new BufferedImage(119, 67, BufferedImage.TYPE_INT_ARGB);
            var canvas = reconstructed.createGraphics();
            try {
                for (Blit part : natural.blits.stream().filter(blit -> blit.depth() > 400).toList()) {
                    int u = (int) part.u();
                    int v = (int) part.v();
                    int x = part.x() + 25;
                    int y = part.y() + 5;
                    canvas.drawImage(source, x, y, x + part.width(), y + part.height(),
                            u, v, u + part.sourceWidth(), v + part.sourceHeight(), null);
                }
            } finally {
                canvas.dispose();
            }
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    // 透明像素的隐藏 RGB 不影响最终外观。
                    int expected = source.getRGB(x, y);
                    assertEquals(expected >>> 24 == 0 ? 0 : expected, reconstructed.getRGB(x, y));
                }
            }
        }
    }

    @Test
    void terminalRestoresPoseAndColorIfItsBackgroundOrFrameFails() {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            int frameStart = (int) render("order_terminal", 0).blits.stream()
                    .filter(blit -> blit.depth() == 400).count() + 1;
            for (int failure : new int[]{1, frameStart}) {
                var probe = new RenderProbe();
                probe.failOnBlit = failure;
                assertThrows(IllegalStateException.class, () -> DiskTooltipBackground.renderBackground(
                        probe.graphics, 0, 0, 300, 140, theme("order_terminal"), 0));
                assertEquals(0, probe.pose.last().pose().m32());
                assertTrue(probe.scissors.isEmpty());
                for (float color : probe.tint) assertEquals(1, color);
            }
        }
    }

    @Test
    void resonanceSideOrnamentsCoverTwoThirdsOfTheVerticalEdgesAndTheMiddleIsRaised() {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            for (int height : new int[]{67, 100, 140, 240}) {
                var probe = new RenderProbe();
                DiskTooltipBackground.renderBackground(probe.graphics, 0, 0, 300, height,
                        theme("resonance_storage_disk"), 0);
                var frame = probe.blits.stream().filter(blit -> blit.depth() > 400).toList();
                assertEquals(3, frame.size());
                assertEquals(Math.round(height * 2.0F / 3.0F), frame.get(0).height());
                assertEquals(frame.get(0).height(), frame.get(2).height());
                assertEquals(33, frame.get(0).width());
                assertEquals(65, frame.get(1).width());
                assertEquals(frame.get(0).y() - 3, frame.get(1).y());
            }
        }
    }

    @Test
    void guixuUsesAllSixArtistPiecesAndKeepsItsTopAndBottomOpen() throws IOException {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            var probe = render("unified_storage_disk", 0);
            var frame = probe.blits.stream().filter(blit -> blit.depth() > 400).toList();
            assertEquals(Set.of("top", "left_tile", "left_bottom", "right_top", "right_tile", "right_bottom"),
                    frame.stream().map(blit -> blit.texture().getPath()
                            .replace("textures/gui/disk_tooltip/guixu_frame_", "").replace(".png", ""))
                            .collect(Collectors.toSet()));
            assertTrue(probe.fills.stream().noneMatch(fill -> fill.depth() > 400));
            for (Blit part : frame) {
                var image = ImageIO.read(RESOURCES.resolve(part.texture().getPath()).toFile());
                assertEquals(image.getWidth(), part.textureWidth());
                assertEquals(image.getHeight(), part.textureHeight());
                assertEquals(part.sourceWidth(), part.width());
                assertEquals(part.sourceHeight(), part.height());
                assertEquals(1, part.alpha());
            }
            var arrow = frame.stream().filter(blit -> blit.texture().getPath().endsWith("_left_bottom.png"))
                    .findFirst().orElseThrow();
            assertEquals(-9, arrow.x());
            assertEquals(118, arrow.y());
            assertEquals(50, arrow.width());
            assertEquals(32, arrow.height());
            assertTrue(probe.clips.size() < 16);
            int end = 0;
            for (Clip clip : probe.clips) {
                assertEquals(end, clip.y());
                assertTrue(clip.x() >= 0 && clip.x() <= 3);
                assertTrue(clip.right() >= 300 && clip.right() <= 305);
                end = clip.bottom();
            }
            assertEquals(140, end);
            assertTrue(probe.scissors.isEmpty());
        }
    }

    @Test
    void guixuRestoresClippingAndPoseIfItsNebulaOrFrameFails() {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            var reference = render("unified_storage_disk", 0);
            int frameStart = (int) reference.blits.stream().filter(blit -> blit.depth() == 400).count() + 1;
            for (int failure : new int[]{1, frameStart}) {
                var probe = new RenderProbe();
                probe.failOnBlit = failure;
                assertThrows(IllegalStateException.class, () -> DiskTooltipBackground.renderBackground(
                        probe.graphics, 0, 0, 300, 140, theme("unified_storage_disk"), 0));
                assertEquals(0, probe.pose.last().pose().m32());
                assertTrue(probe.scissors.isEmpty());
                for (float color : probe.tint) assertEquals(1, color);
            }
        }
    }

    @Test
    void crtEffectsRemainFaintAndInsideTheBackgroundEvenDuringSignalGlitches() {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            for (int step = 0; step < 240; step++) {
                var probe = render("resonance_storage_disk", step / 10.0);
                var effects = probe.fills.stream().filter(fill -> fill.depth() == 400).toList();
                assertFalse(effects.isEmpty());
                for (Fill effect : effects) {
                    assertTrue(effect.first() >>> 24 <= 22);
                    assertTrue(effect.x() >= 1 && effect.y() >= 1);
                    assertTrue(effect.right() <= 299 && effect.bottom() <= 139);
                }
                for (Blit blit : probe.blits) {
                    assertTrue(blit.u() >= 0 && blit.v() >= 0);
                    assertTrue(blit.u() + blit.sourceWidth() <= blit.textureWidth());
                    assertTrue(blit.v() + blit.sourceHeight() <= blit.textureHeight());
                }
            }
            assertTrue(render("unified_storage_disk", 1.64).fills.stream().noneMatch(fill -> fill.depth() == 400));
            assertTrue(render("resonance_network_generator", 1.64).fills.stream().noneMatch(fill -> fill.depth() == 400));
        }
    }

    @Test
    void smallTooltipsKeepTheirThinBorderWithoutOverlappingOrnaments() {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            for (String item : List.of("resonance_storage_disk", "order_terminal")) {
                var probe = new RenderProbe();
                DiskTooltipBackground.renderBackground(probe.graphics, 0, 0, 65, 24, theme(item), 1.64);
                assertTrue(probe.blits.stream().noneMatch(blit -> blit.depth() > 400));
                assertEquals(4, probe.fills.stream().filter(fill -> fill.depth() > 400).count());
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

    @Test
    void poseAndColorAreRestoredIfAnArtistFrameTextureFails() {
        try (var renderSystem = mockStatic(RenderSystem.class)) {
            var probe = new RenderProbe();
            probe.failOnBlit = 6;
            assertThrows(IllegalStateException.class, () -> DiskTooltipBackground.renderBackground(
                    probe.graphics, 0, 0, 300, 140, theme("resonance_storage_disk"), 0));
            assertEquals(0, probe.pose.last().pose().m32());
            for (float color : probe.tint) assertEquals(1, color);
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
                        int textureWidth, int textureHeight, float red, float green, float blue,
                        float alpha, float depth) {}

    private record Fill(float depth, int x, int y, int right, int bottom, int first, int last) {}

    private record Clip(int x, int y, int right, int bottom) {}

    /** 只记录真实绘制调用，不在单元测试里重新实现渲染器或导出预览。 */
    private static final class RenderProbe {
        private final GuiGraphics graphics = mock(GuiGraphics.class);
        private final PoseStack pose = new PoseStack();
        private final List<Blit> blits = new ArrayList<>();
        private final List<Fill> fills = new ArrayList<>();
        private final List<Clip> clips = new ArrayList<>();
        private final ArrayDeque<Clip> scissors = new ArrayDeque<>();
        private final float[] tint = {1, 1, 1, 1};
        private int failOnBlit;
        private boolean failOnBorder;

        private RenderProbe() {
            when(graphics.pose()).thenReturn(pose);
            doAnswer(call -> {
                var clip = new Clip(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3));
                scissors.push(clip);
                clips.add(clip);
                return null;
            }).when(graphics).enableScissor(anyInt(), anyInt(), anyInt(), anyInt());
            doAnswer(call -> {
                scissors.pop();
                return null;
            }).when(graphics).disableScissor();
            doAnswer(call -> {
                for (int i = 0; i < tint.length; i++) tint[i] = call.getArgument(i);
                return null;
            }).when(graphics).setColor(anyFloat(), anyFloat(), anyFloat(), anyFloat());
            doAnswer(call -> {
                assertTrue(pose.last().pose().m32() == 400 || pose.last().pose().m32() == 408);
                if (blits.size() + 1 == failOnBlit) throw new IllegalStateException("绘制失败");
                blits.add(new Blit(call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        call.getArgument(3), call.getArgument(4), call.getArgument(5),
                        call.getArgument(6), call.getArgument(7), call.getArgument(8),
                        call.getArgument(9), call.getArgument(10), tint[0], tint[1], tint[2], tint[3],
                        pose.last().pose().m32()));
                return null;
            }).when(graphics).blit(any(ResourceLocation.class), anyInt(), anyInt(),
                    anyInt(), anyInt(), anyFloat(), anyFloat(), anyInt(), anyInt(), anyInt(), anyInt());
            doAnswer(call -> {
                fill(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
                        call.getArgument(4), call.getArgument(4));
                return null;
            }).when(graphics).fill(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
            doAnswer(call -> {
                fill(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3),
                        call.getArgument(5), call.getArgument(6));
                return null;
            }).when(graphics).fillGradient(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        }

        private void fill(int x, int y, int right, int bottom, int first, int last) {
            if (failOnBorder && pose.last().pose().m32() > 400) throw new IllegalStateException("边框绘制失败");
            fills.add(new Fill(pose.last().pose().m32(), x, y, right, bottom, first, last));
        }
    }
}
