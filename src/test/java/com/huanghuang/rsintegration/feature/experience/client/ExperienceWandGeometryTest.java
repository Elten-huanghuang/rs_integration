package com.huanghuang.rsintegration.feature.experience.client;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceWandGeometry.CorePixel;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceWandGeometry.Orbit;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceWandGeometry.Point;
import com.huanghuang.rsintegration.feature.experience.client.ExperienceWandGeometry.Quad;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExperienceWandGeometryTest {
    @Test
    void orbitSurfacesUseWholePixelsAndSquareEndsInsteadOfSmoothCurves() {
        double pixel = ExperienceWandGeometry.RING_PIXEL_SIZE;
        assertTrue(pixel >= 0.5, "环带颗粒不能比杖身细太多");
        for (Orbit orbit : ExperienceWandGeometry.ORBITS) {
            List<Point> vertices = orbit.mesh().stream()
                    .flatMap(ExperienceWandGeometryTest::points).toList();
            assertEquals(pixel, extent(vertices, 2), 1e-9, "侧面厚度也必须是一整像素");
            for (Quad face : orbit.mesh()) {
                assertEquals(1, Math.abs(face.normal().x()) + Math.abs(face.normal().y())
                        + Math.abs(face.normal().z()), 1e-9, "轮廓必须是方格阶梯，不能是光滑斜边");
                Point[] corners = {face.a(), face.b(), face.c(), face.d()};
                for (int i = 0; i < corners.length; i++) {
                    Point point = corners[i];
                    assertEquals(Math.rint(point.x() / pixel), point.x() / pixel, 1e-9);
                    assertEquals(Math.rint(point.y() / pixel), point.y() / pixel, 1e-9);
                    assertEquals(pixel / 2, Math.abs(point.z()), 1e-9);
                    assertEquals(pixel, length(point.subtract(corners[(i + 1) % 4])), 1e-9,
                            "每个面都是整块像素，不允许细长亮线和针尖");
                }
            }
            assertTrue(orbit.mesh().stream().map(Quad::color).distinct().count() <= 7);
        }
    }

    @Test
    void eachPixelatedOrbitIsOneShortConnectedFragment() {
        assertEquals(3, ExperienceWandGeometry.ORBITS.size(), "三条轨道，不再额外增加碎片");
        double pixel = ExperienceWandGeometry.RING_PIXEL_SIZE;
        for (Orbit orbit : ExperienceWandGeometry.ORBITS) {
            Set<RingCell> remaining = new HashSet<>();
            for (Quad face : orbit.mesh()) {
                if (face.normal().z() > 0.9) {
                    remaining.add(new RingCell((int) Math.round(face.a().x() / pixel),
                            (int) Math.round(face.a().y() / pixel)));
                }
            }
            int count = 0;
            while (!remaining.isEmpty()) {
                count++;
                RingCell start = remaining.iterator().next();
                ArrayDeque<RingCell> queue = new ArrayDeque<>();
                remaining.remove(start);
                queue.add(start);
                int cells = 0;
                while (!queue.isEmpty()) {
                    RingCell cell = queue.remove();
                    cells++;
                    for (RingCell neighbor : List.of(new RingCell(cell.x - 1, cell.y),
                            new RingCell(cell.x + 1, cell.y), new RingCell(cell.x, cell.y - 1),
                            new RingCell(cell.x, cell.y + 1))) {
                        if (remaining.remove(neighbor)) queue.add(neighbor);
                    }
                }
                assertTrue(cells >= 8 && cells <= 40, "每条轨道只是一截短弧，内部仍需完整连通");
            }
            assertEquals(1, count, "每条轨道只保留一段完整尾带");
            Set<Integer> angles = new HashSet<>();
            for (Quad face : orbit.mesh()) {
                if (face.normal().z() <= 0.9) continue;
                double angle = Math.atan2(face.a().y() + pixel / 2, face.a().x() + pixel / 2);
                angles.add(Math.floorMod((int) Math.floor(Math.toDegrees(angle) / 15), 24));
            }
            assertTrue(angles.size() >= 3, "短弧仍应有能辨认的弯曲轮廓");
            assertTrue(angles.size() <= 7, "短弧占用不到四分之一圈，不能重新拉成长框架");
        }
    }

    @Test
    void orbitHeadsLeadTheRotationAndTaperIntoOnePixelTails() {
        double pixel = ExperienceWandGeometry.RING_PIXEL_SIZE;
        for (Orbit orbit : ExperienceWandGeometry.ORBITS) {
            List<Quad> surface = orbit.mesh().stream().filter(face -> face.normal().z() > 0.9).toList();
            double minAngle = surface.stream().mapToDouble(ExperienceWandGeometryTest::cellAngle)
                    .min().orElseThrow();
            double maxAngle = surface.stream().mapToDouble(ExperienceWandGeometryTest::cellAngle)
                    .max().orElseThrow();
            double headWidth = 0;
            double tailWidth = 0;
            for (int sample = 0; sample < 20; sample++) {
                double headProgress = 0.08 + sample * 0.01;
                double tailProgress = 0.60 + sample * 0.01;
                double headAngle = minAngle + (maxAngle - minAngle)
                        * (orbit.revolutions() > 0 ? 1 - headProgress : headProgress);
                double tailAngle = minAngle + (maxAngle - minAngle)
                        * (orbit.revolutions() > 0 ? 1 - tailProgress : tailProgress);
                headWidth += radialWidth(surface, headAngle);
                tailWidth += radialWidth(surface, tailAngle);
            }
            assertTrue(headWidth > tailWidth * 2.0,
                    "带头必须有三像素宽，之后逐级收至一像素长尾");
            assertTrue(tailWidth / 20 <= pixel * 1.6,
                    "尾部只保留一像素阶梯，斜向方格允许投影宽于一像素");
            Set<RingCell> tailCells = new HashSet<>();
            for (Quad cell : surface) {
                double progress = (cellAngle(cell) - minAngle) / (maxAngle - minAngle);
                if (orbit.revolutions() > 0) progress = 1 - progress;
                if (progress > 0.5) {
                    tailCells.add(new RingCell((int) Math.round(cell.a().x() / pixel),
                            (int) Math.round(cell.a().y() / pixel)));
                }
            }
            assertTrue(tailCells.size() >= 4, "细尾应延续数格，不能只把最后一个端点削细");
            for (RingCell cell : tailCells) {
                assertFalse(tailCells.containsAll(List.of(new RingCell(cell.x + 1, cell.y),
                                new RingCell(cell.x, cell.y + 1), new RingCell(cell.x + 1, cell.y + 1))),
                        "细尾不能出现完整的两像素宽方块");
            }
            for (Quad inlay : surface) {
                if (!inlay.emissive()) continue;
                double progress = (cellAngle(inlay) - minAngle) / (maxAngle - minAngle);
                assertTrue(orbit.revolutions() > 0 ? progress > 0.6 : progress < 0.4,
                        "经验晶片应留在宽头，不能让细尾重新出现装饰凸块");
            }
        }
    }

    @Test
    void orbitInlaysStayFlushAndMatchOnBothSides() {
        Set<Integer> palette = new HashSet<>();
        for (Orbit orbit : ExperienceWandGeometry.ORBITS) {
            Map<Integer, Integer> surfaceColors = new HashMap<>();
            Map<RingCell, Quad> front = new HashMap<>();
            double pixel = ExperienceWandGeometry.RING_PIXEL_SIZE;
            for (Quad face : orbit.mesh()) {
                palette.add(face.color());
                if (face.normal().z() > 0.9) {
                    surfaceColors.merge(face.color(), 1, Integer::sum);
                    front.put(new RingCell((int) Math.round(face.a().x() / pixel),
                            (int) Math.round(face.a().y() / pixel)), face);
                }
                if (face.emissive()) {
                    assertTrue(Math.abs(face.normal().z()) > 0.9, "晶片只嵌在正反表面");
                }
            }
            assertTrue(surfaceColors.size() >= 3 && surfaceColors.size() <= 4,
                    "只用金属内侧、描金外沿、端头亮色和经验绿晶片");
            assertEquals(1, front.values().stream().filter(Quad::emissive).count(),
                    "短弧只保留一处经验晶片，避免装饰挤在一起");
            for (Map.Entry<RingCell, Quad> entry : front.entrySet()) {
                if (!entry.getValue().emissive()) continue;
                RingCell cell = entry.getKey();
                int metalNeighbors = 0;
                for (RingCell neighbor : List.of(new RingCell(cell.x - 1, cell.y),
                        new RingCell(cell.x + 1, cell.y), new RingCell(cell.x, cell.y - 1),
                        new RingCell(cell.x, cell.y + 1))) {
                    Quad bezel = front.get(neighbor);
                    if (bezel != null && !bezel.emissive()) metalNeighbors++;
                }
                assertTrue(metalNeighbors >= 2, "晶片要嵌在短弧的宽端，不能挂在孤立凸块上");
            }
            for (Quad back : orbit.mesh()) {
                if (back.normal().z() >= -0.9) continue;
                RingCell cell = new RingCell((int) Math.round(back.b().x() / pixel),
                        (int) Math.round(back.b().y() / pixel));
                assertEquals(front.get(cell).color(), back.color(), "正反两面的装饰一致");
                assertEquals(front.get(cell).emissive(), back.emissive());
            }
        }
        assertTrue(palette.size() <= 7, "共用少量像素色阶，避免细碎杂色");
    }

    @Test
    void pixelatedOrbitMeshesHaveClosedSidesAndNoDuplicateFaces() {
        for (Orbit orbit : ExperienceWandGeometry.ORBITS) {
            Set<String> surfaces = new HashSet<>();
            Map<String, Integer> edges = new HashMap<>();
            for (Quad face : orbit.mesh()) {
                String surface = String.join("/", points(face).map(ExperienceWandGeometryTest::key)
                        .sorted().toList());
                assertTrue(surfaces.add(surface), "不能有重复的共面表面");
                Point[] vertices = {face.a(), face.b(), face.c(), face.d()};
                for (int i = 0; i < vertices.length; i++) {
                    String a = key(vertices[i]);
                    String b = key(vertices[(i + 1) % 4]);
                    edges.merge(a.compareTo(b) < 0 ? a + "/" + b : b + "/" + a, 1, Integer::sum);
                }
            }
            assertTrue(edges.values().stream().allMatch(value -> value == 2), "像素碎环必须封闭");
        }
    }

    private record RingCell(int x, int y) {}

    private static double cellAngle(Quad face) {
        return Math.atan2((face.a().y() + face.c().y()) / 2,
                (face.a().x() + face.c().x()) / 2);
    }

    private static double radialWidth(List<Quad> surface, double angle) {
        double width = 0;
        for (Quad cell : surface) {
            double near = 0;
            double far = Double.POSITIVE_INFINITY;
            for (int axis = 0; axis < 2; axis++) {
                double direction = axis == 0 ? Math.cos(angle) : Math.sin(angle);
                double low = Math.min(coordinate(cell.a(), axis), coordinate(cell.c(), axis));
                double high = Math.max(coordinate(cell.a(), axis), coordinate(cell.c(), axis));
                if (Math.abs(direction) < 1e-9) {
                    if (low > 0 || high < 0) far = -1;
                } else {
                    double a = low / direction;
                    double b = high / direction;
                    near = Math.max(near, Math.min(a, b));
                    far = Math.min(far, Math.max(a, b));
                }
            }
            width += Math.max(0, far - near);
        }
        return width;
    }

    @Test
    void lightCoreUsesCoarseOpaquePixelsWithoutDetachedHalo() {
        for (List<CorePixel> frame : ExperienceWandGeometry.LIGHT_CORES) {
            assertTrue(frame.size() >= 35 && frame.size() <= 60,
                    "光核只用少量粗像素，不添加漂浮光点");
            assertTrue(frame.stream().map(CorePixel::color).distinct().count() <= 7);
            for (CorePixel pixel : frame) {
                double radius = Math.hypot(pixel.x(), pixel.y());
                double size = ExperienceWandGeometry.CORE_PIXEL_SIZE;
                assertEquals(Math.rint(pixel.x() / size - 0.5), pixel.x() / size - 0.5, 1e-9);
                assertEquals(Math.rint(pixel.y() / size - 0.5), pixel.y() / size - 0.5, 1e-9);
                assertTrue(radius <= ExperienceWandGeometry.CORE_RADIUS);
                assertEquals(0, pixel.depth(), "所有光核像素必须处于同一平面");
            }
            Set<RingCell> remaining = new HashSet<>();
            for (CorePixel pixel : frame) {
                remaining.add(new RingCell(
                        (int) Math.round(pixel.x() / ExperienceWandGeometry.CORE_PIXEL_SIZE - 0.5),
                        (int) Math.round(pixel.y() / ExperienceWandGeometry.CORE_PIXEL_SIZE - 0.5)));
            }
            RingCell start = remaining.iterator().next();
            ArrayDeque<RingCell> queue = new ArrayDeque<>();
            remaining.remove(start);
            queue.add(start);
            while (!queue.isEmpty()) {
                RingCell cell = queue.remove();
                for (RingCell neighbor : List.of(new RingCell(cell.x - 1, cell.y),
                        new RingCell(cell.x + 1, cell.y), new RingCell(cell.x, cell.y - 1),
                        new RingCell(cell.x, cell.y + 1))) {
                    if (remaining.remove(neighbor)) queue.add(neighbor);
                }
            }
            assertTrue(remaining.isEmpty(), "光核轮廓必须是无断点的单个像素圆盘");
        }
    }

    @Test
    void handleDecorationsKeepTheShaftSlender() {
        List<Quad> handle = ExperienceWandGeometry.BODY.stream()
                .filter(quad -> overlapsHeight(quad, 3.3, 17.4)).toList();
        assertFalse(handle.isEmpty());
        for (Point point : handle.stream().flatMap(ExperienceWandGeometryTest::points).toList()) {
            assertTrue(Math.hypot(point.x(), point.z()) <= 0.6,
                    "握柄和刻纹不能把细杆扩成粗棍");
        }
    }

    @Test
    void handleUsesSeparatedShortRunesInsteadOfAContinuousLightBar() {
        List<Quad> runes = ExperienceWandGeometry.BODY.stream()
                .filter(Quad::emissive).filter(quad -> overlapsHeight(quad, 10.5, 17.4))
                .toList();
        assertFalse(runes.isEmpty());
        for (Quad rune : runes) {
            assertTrue(extent(points(rune).toList(), 1) <= 1.0,
                    "经验刻纹应为短笔画，不能重新出现贯通灯条");
        }
        assertFalse(runes.stream().anyMatch(quad -> overlapsHeight(quad, 12.5, 13.5)));
        assertFalse(runes.stream().anyMatch(quad -> overlapsHeight(quad, 15.1, 16.1)));
    }

    @Test
    void gripHasRaisedWrappingAndDistinctMaterials() {
        List<Quad> grip = ExperienceWandGeometry.BODY.stream()
                .filter(quad -> !quad.emissive() && overlapsHeight(quad, 5.6, 9.5)).toList();
        assertTrue(grip.stream().map(Quad::color).distinct().count() >= 4);
        List<Point> vertices = grip.stream().flatMap(ExperienceWandGeometryTest::points).toList();
        assertTrue(vertices.stream().anyMatch(point -> Math.hypot(point.x(), point.z()) > 0.52));
        assertTrue(vertices.stream().map(point -> Math.round(point.y() * 1000))
                .distinct().count() >= 20, "皮革斜缠带需要真实起伏，不能只是换色长方条");
    }

    @Test
    void lightCoreHasACompleteCenteredSilhouetteWithoutBobbing() {
        List<CorePixel> first = ExperienceWandGeometry.LIGHT_CORES.get(0);
        for (List<CorePixel> frame : ExperienceWandGeometry.LIGHT_CORES) {
            assertEquals(first.size(), frame.size());
            Set<String> centers = new HashSet<>();
            for (int i = 0; i < frame.size(); i++) {
                CorePixel pixel = frame.get(i);
                assertEquals(first.get(i).x(), pixel.x());
                assertEquals(first.get(i).y(), pixel.y());
                assertEquals(first.get(i).depth(), pixel.depth());
                centers.add(Math.round(pixel.x() * 1000) + "/" + Math.round(pixel.y() * 1000));
            }
            for (CorePixel pixel : frame) {
                assertTrue(centers.contains(-Math.round(pixel.x() * 1000)
                        + "/" + -Math.round(pixel.y() * 1000)), "圆形轮廓不能缺角或漂移");
            }
        }
    }

    @Test
    void neighboringCorePixelsShareExactEdgesAtDifferentItemAngles() {
        double half = ExperienceWandGeometry.CORE_PIXEL_SIZE / 2;
        List<CorePixel> pixels = ExperienceWandGeometry.LIGHT_CORES.get(0);
        for (Orbit orientation : ExperienceWandGeometry.ORBITS) {
            Point right = orientation.transform(new Point(1, 0, 0), 0);
            Point up = orientation.transform(new Point(0, 1, 0), 0);
            Point forward = orientation.transform(new Point(0, 0, 1), 0);
            Map<String, CorePixel> grid = new HashMap<>();
            for (CorePixel pixel : pixels) {
                grid.put(Math.round(pixel.x() * 1000) + "/" + Math.round(pixel.y() * 1000), pixel);
            }
            for (CorePixel pixel : pixels) {
                CorePixel neighbor = grid.get(Math.round((pixel.x() + 2 * half) * 1000)
                        + "/" + Math.round(pixel.y() * 1000));
                if (neighbor == null) continue;
                Point leftEdge = pixel.corner(half, -half, right, up, forward);
                Point rightEdge = neighbor.corner(-half, -half, right, up, forward);
                assertEquals(0, length(leftEdge.subtract(rightEdge)), 1e-9,
                        "同平面像素的公共边必须重合，透视下不能漏出缝");
            }
        }
    }

    @Test
    void lightCoreFacesTheObserverAtDifferentItemAngles() {
        Point center = new Point(0, ExperienceWandGeometry.CORE_Y, 0);
        double half = ExperienceWandGeometry.CORE_PIXEL_SIZE / 2;
        for (Orbit orientation : ExperienceWandGeometry.ORBITS) {
            Point right = orientation.transform(new Point(1, 0, 0), 0);
            Point up = orientation.transform(new Point(0, 1, 0), 0);
            Point forward = orientation.transform(new Point(0, 0, 1), 0);
            for (CorePixel pixel : ExperienceWandGeometry.LIGHT_CORES.get(0)) {
                Point a = pixel.corner(-half, -half, right, up, forward);
                Point b = pixel.corner(half, -half, right, up, forward);
                Point d = pixel.corner(-half, half, right, up, forward);
                Point normal = b.subtract(a).cross(d.subtract(a)).normalized();
                assertEquals(1, dot(normal, forward), 1e-9);
                assertEquals(pixel.x() - half, dot(a.subtract(center), right), 1e-9);
                assertEquals(pixel.y() - half, dot(a.subtract(center), up), 1e-9);
                assertEquals(pixel.depth(), dot(a.subtract(center), forward), 1e-9);
            }
        }
    }

    @Test
    void lightCoreFlowsInsideWithoutChangingItsFootprint() {
        List<CorePixel> first = ExperienceWandGeometry.LIGHT_CORES.get(0);
        List<CorePixel> later = ExperienceWandGeometry.LIGHT_CORES.get(11);
        int colorChanges = 0;
        for (int i = 0; i < first.size(); i++) {
            if (first.get(i).color() != later.get(i).color()) colorChanges++;
            assertEquals(first.get(i).x(), later.get(i).x());
            assertEquals(first.get(i).y(), later.get(i).y());
            assertEquals(first.get(i).depth(), later.get(i).depth());
        }
        assertTrue(colorChanges >= 4, "少量明暗像素需要缓慢流动");
        assertTrue(colorChanges < first.size() / 2, "不能让整颗光核闪烁变色");
    }

    @Test
    void lightCoreInterpolationLoopsContinuouslyAndHandlesWrappedTime() {
        int count = ExperienceWandGeometry.CORE_FRAME_COUNT;
        for (double ticks : new double[]{-0.001, 0, 2.5, 79.999, 80, 160.5}) {
            double frame = ExperienceWandGeometry.coreFramePosition(ticks);
            assertTrue(frame >= 0 && frame < count);
        }
        assertEquals(ExperienceWandGeometry.coreFramePosition(0.5),
                ExperienceWandGeometry.coreFramePosition(80_000_000.5), 1e-9);
        assertEquals(0, ExperienceWandGeometry.coreFramePosition(
                ExperienceWandGeometry.CORE_LOOP_TICKS), 1e-9);
        for (int i = 0; i < ExperienceWandGeometry.LIGHT_CORES.get(0).size(); i++) {
            for (int channel : new int[]{0, 8, 16}) {
                assertEquals(interpolatedCoreChannel(i, channel,
                                ExperienceWandGeometry.CORE_LOOP_TICKS - 0.001),
                        interpolatedCoreChannel(i, channel, 0.001), 0.1,
                        "流动色块在循环边界不能闪跳");
            }
        }
    }

    @Test
    void everyFaceHasFiniteVerticesAndAUnitNormal() {
        Stream<List<Quad>> meshes = Stream.concat(Stream.of(ExperienceWandGeometry.BODY),
                ExperienceWandGeometry.ORBITS.stream().map(Orbit::mesh));
        for (Quad face : meshes.flatMap(List::stream).toList()) {
            assertEquals(1, length(face.normal()), 1e-9);
            points(face).forEach(point -> {
                assertTrue(Double.isFinite(point.x()));
                assertTrue(Double.isFinite(point.y()));
                assertTrue(Double.isFinite(point.z()));
            });
        }
    }

    @Test
    void orbitBandsHaveRealThicknessAndNeverCutThroughTheCore() {
        for (Orbit orbit : ExperienceWandGeometry.ORBITS) {
            List<Point> points = orbit.mesh().stream()
                    .flatMap(ExperienceWandGeometryTest::points).toList();
            assertTrue(extent(points, 2) > 0.2, "环带必须有厚度，不能是单层贴片");
            assertTrue(points.stream().allMatch(point ->
                    length(point) > ExperienceWandGeometry.CORE_RADIUS
                            + ExperienceWandGeometry.RING_PIXEL_SIZE),
                    "收紧轨道后，金属环带与光核仍需保留至少一像素间距");
        }
    }

    @Test
    void innerGreenFlowsStayInsideTheConnectedOpaqueCore() {
        Set<Integer> greens = new HashSet<>();
        for (CorePixel pixel : ExperienceWandGeometry.LIGHT_CORES.get(0)) {
            greens.add(pixel.color());
            assertTrue(((pixel.color() >> 8) & 255) > (pixel.color() & 255));
        }
        assertTrue(greens.contains(0xD8FF4D) && greens.contains(0x26D948),
                "交错流光使用高亮黄绿与饱和绿色，和底色有明显区分");
        assertTrue(ExperienceWandGeometry.LIGHT_CORES.stream()
                .anyMatch(frame -> !frame.equals(ExperienceWandGeometry.LIGHT_CORES.get(0))),
                "流光色块应随时间改变");
    }

    @Test
    void orbitPlanesDifferAndIncludeBothRotationDirections() {
        List<Orbit> orbits = ExperienceWandGeometry.ORBITS;
        assertTrue(orbits.stream().anyMatch(orbit -> orbit.revolutions() > 0));
        assertTrue(orbits.stream().anyMatch(orbit -> orbit.revolutions() < 0));
        for (int i = 0; i < orbits.size(); i++) {
            Point normal = orbits.get(i).transform(new Point(0, 0, 1), 0);
            assertTrue(Math.abs(normal.z()) < 0.75, "轨道不能全躺在贴图平面里");
            for (int j = i + 1; j < orbits.size(); j++) {
                Point other = orbits.get(j).transform(new Point(0, 0, 1), 0);
                assertTrue(Math.abs(dot(normal, other)) < 0.2,
                        "轨道平面要明显错开，不能从常用视角看起来叠在同一层");
            }
        }
    }

    @Test
    void orbitAnimationMovesGeometryInDepthAndLoopsSmoothly() {
        for (Orbit orbit : ExperienceWandGeometry.ORBITS) {
            Point sample = new Point(4, 0, 0);
            Point start = orbit.transform(sample, 0);
            Point moved = orbit.transform(sample, 7.5);
            assertTrue(length(start.subtract(moved)) > 1);
            List<Point> trajectory = Stream.iterate(0.0,
                            ticks -> ticks < ExperienceWandGeometry.LOOP_TICKS,
                            ticks -> ticks + ExperienceWandGeometry.LOOP_TICKS / 8.0)
                    .map(ticks -> orbit.transform(sample, ticks)).toList();
            assertTrue(extent(trajectory, 2) > 1, "整圈旋转都需有纵深，起始相位可以停在前后极值处");
            Point loop = orbit.transform(sample, ExperienceWandGeometry.LOOP_TICKS);
            assertEquals(start.x(), loop.x(), 1e-9);
            assertEquals(start.y(), loop.y(), 1e-9);
            assertEquals(start.z(), loop.z(), 1e-9);
            Point before = orbit.transform(sample, ExperienceWandGeometry.LOOP_TICKS - 0.001);
            Point after = orbit.transform(sample, 0.001);
            assertTrue(length(before.subtract(after)) < 0.002, "循环边界不能跳帧");
        }
    }

    @Test
    void customModelAndTextureResourcesMatchTheRenderer() throws IOException {
        Path assets = Path.of("src/main/resources/assets/rs_integration");
        JsonObject model = new Gson().fromJson(
                Files.readString(assets.resolve("models/item/resonant_experience_wand.json")),
                JsonObject.class);
        assertEquals("minecraft:builtin/entity", model.get("parent").getAsString());
        assertFalse(model.getAsJsonObject("textures").has("layer0"));
        for (String context : List.of("gui", "ground", "fixed", "head",
                "firstperson_righthand", "firstperson_lefthand",
                "thirdperson_righthand", "thirdperson_lefthand")) {
            assertTrue(model.getAsJsonObject("display").has(context), context);
        }
        JsonObject display = model.getAsJsonObject("display");
        assertEquals(0.68F, display.getAsJsonObject("firstperson_righthand")
                .getAsJsonArray("scale").get(0).getAsFloat(), 1e-6F);
        assertEquals(0.68F, display.getAsJsonObject("firstperson_lefthand")
                .getAsJsonArray("scale").get(0).getAsFloat(), 1e-6F);
        assertEquals(3.0F, display.getAsJsonObject("firstperson_righthand")
                .getAsJsonArray("translation").get(1).getAsFloat(), 1e-6F);
        Path surface = assets.resolve("textures/item/resonant_experience_wand_surface.png");
        var texture = ImageIO.read(surface.toFile());
        assertEquals(16, texture.getWidth());
        assertEquals(16, texture.getHeight());
        assertFalse(Files.exists(surface.resolveSibling(surface.getFileName() + ".mcmeta")));
    }

    @Test
    void exportsTheActualProductionMeshesForVisualVerification() throws IOException {
        Path output = Path.of("build/experience-wand/geometry.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new Gson().toJson(Map.of(
                "body", ExperienceWandGeometry.BODY,
                "lightCores", ExperienceWandGeometry.LIGHT_CORES,
                "corePixelSize", ExperienceWandGeometry.CORE_PIXEL_SIZE,
                "orbits", ExperienceWandGeometry.ORBITS,
                "coreY", ExperienceWandGeometry.CORE_Y,
                "loopTicks", ExperienceWandGeometry.LOOP_TICKS,
                "coreLoopTicks", ExperienceWandGeometry.CORE_LOOP_TICKS)));
        assertTrue(Files.size(output) > 1000);
    }

    private static Stream<Point> points(Quad quad) {
        return Stream.of(quad.a(), quad.b(), quad.c(), quad.d());
    }

    private static boolean overlapsHeight(Quad quad, double low, double high) {
        double min = points(quad).mapToDouble(Point::y).min().orElseThrow();
        double max = points(quad).mapToDouble(Point::y).max().orElseThrow();
        return max >= low && min <= high;
    }

    private static double interpolatedCoreChannel(int pixel, int shift, double ticks) {
        double frame = ExperienceWandGeometry.coreFramePosition(ticks);
        int index = (int) frame;
        double weight = frame - index;
        CorePixel from = ExperienceWandGeometry.LIGHT_CORES.get(index).get(pixel);
        CorePixel to = ExperienceWandGeometry.LIGHT_CORES.get(
                (index + 1) % ExperienceWandGeometry.CORE_FRAME_COUNT).get(pixel);
        int a = (from.color() >> shift) & 255;
        int b = (to.color() >> shift) & 255;
        return a + (b - a) * weight;
    }

    private static double extent(List<Point> points, int axis) {
        return points.stream().mapToDouble(point -> coordinate(point, axis)).max().orElseThrow()
                - points.stream().mapToDouble(point -> coordinate(point, axis)).min().orElseThrow();
    }

    private static double coordinate(Point point, int axis) {
        return axis == 0 ? point.x() : axis == 1 ? point.y() : point.z();
    }

    private static double dot(Point a, Point b) {
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }

    private static double length(Point point) {
        return Math.sqrt(dot(point, point));
    }

    private static String key(Point point) {
        return Math.round(point.x() * 1e8) + "," + Math.round(point.y() * 1e8)
                + "," + Math.round(point.z() * 1e8);
    }
}
