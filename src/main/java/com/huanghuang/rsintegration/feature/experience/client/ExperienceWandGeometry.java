package com.huanghuang.rsintegration.feature.experience.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 不依赖游戏状态的几何；光核像素帧、杖身和轨道分别缓存。 */
public final class ExperienceWandGeometry {
    public static final double CORE_Y = 24.3;
    public static final double CORE_RADIUS = 1.55;
    public static final double CORE_PIXEL_SIZE = 0.4;
    public static final double MODEL_CENTER_Y = 14.6;
    public static final int LOOP_TICKS = 160;
    public static final int CORE_LOOP_TICKS = 80;
    public static final int CORE_FRAME_COUNT = 32;
    public static final double RING_PIXEL_SIZE = 0.5;

    private static final int METAL = 0x1D302C;
    private static final int METAL_LIGHT = 0x567767;
    private static final int GOLD = 0xC49A52;
    private static final int GOLD_LIGHT = 0xF0D486;
    private static final int EXPERIENCE = 0xA7EB29;
    private static final int RING_METAL = 0x3A5148;
    private static final int RING_TRIM = 0xD0A94F;

    public static final List<Quad> BODY = buildBody();
    public static final List<List<CorePixel>> LIGHT_CORES = buildLightFrames();
    public static final List<Orbit> ORBITS = List.of(
            new Orbit(12, 58, -16, 20, 1,
                    ring(3.3, -116, 86, 1)),
            new Orbit(-54, -25, 45, 150, -1,
                    ring(3.75, 20, 70, -1)),
            new Orbit(54, -25, -40, 280, 1,
                    ring(4.2, -54, 60, 1)));

    private ExperienceWandGeometry() {}

    public static double coreFramePosition(double ticks) {
        double wrapped = ((ticks % CORE_LOOP_TICKS) + CORE_LOOP_TICKS) % CORE_LOOP_TICKS;
        return wrapped * CORE_FRAME_COUNT / CORE_LOOP_TICKS;
    }

    public record Point(double x, double y, double z) {
        Point subtract(Point other) {
            return new Point(x - other.x, y - other.y, z - other.z);
        }

        Point cross(Point other) {
            return new Point(y * other.z - z * other.y,
                    z * other.x - x * other.z, x * other.y - y * other.x);
        }

        Point normalized() {
            double length = Math.sqrt(x * x + y * y + z * z);
            return new Point(x / length, y / length, z / length);
        }
    }

    public record Quad(Point a, Point b, Point c, Point d,
                       Point normal, int color, boolean emissive, int alpha) {}

    /** 像素共面拼接成完整圆盘，避免透视让各自独立的小方块露出缝隙。 */
    public record CorePixel(double x, double y, double depth, int color) {
        public Point corner(double dx, double dy, Point right, Point up, Point forward) {
            return new Point(right.x * (x + dx) + up.x * (y + dy)
                    + forward.x * depth,
                    CORE_Y + right.y * (x + dx) + up.y * (y + dy)
                            + forward.y * depth,
                    right.z * (x + dx) + up.z * (y + dy) + forward.z * depth);
        }
    }

    public record Orbit(float tiltX, float tiltY, float tiltZ, float phase,
                        int revolutions, List<Quad> mesh) {
        public float angle(double ticks) {
            return (float) (phase + (ticks % LOOP_TICKS) * 360 * revolutions / LOOP_TICKS);
        }

        /** 与渲染矩阵同序，用于检查真实空间轨迹及离线预览。 */
        public Point transform(Point point, double ticks) {
            double z = Math.toRadians(angle(ticks) + tiltZ);
            double x1 = point.x * Math.cos(z) - point.y * Math.sin(z);
            double y1 = point.x * Math.sin(z) + point.y * Math.cos(z);
            double y = Math.toRadians(tiltY);
            double x2 = x1 * Math.cos(y) + point.z * Math.sin(y);
            double z2 = -x1 * Math.sin(y) + point.z * Math.cos(y);
            double x = Math.toRadians(tiltX);
            return new Point(x2, y1 * Math.cos(x) - z2 * Math.sin(x),
                    y1 * Math.sin(x) + z2 * Math.cos(x));
        }
    }

    private static List<Quad> buildBody() {
        List<Quad> mesh = new ArrayList<>();
        // 八棱细杆和收口握柄分层；装饰只略高于表面，不能把中段重新做粗。
        shaftSection(mesh, 3.1, 19.3, 0.35, 0.41, 0x405248);
        shaftSection(mesh, 3.15, 3.65, 0.55, 0.36, METAL_LIGHT);
        shaftSection(mesh, 4.85, 5.17, 0.57, 0.57, GOLD);
        shaftSection(mesh, 5.17, 5.65, 0.4, 0.49, 0x463E2F);
        shaftSection(mesh, 5.65, 9.4, 0.49, 0.49, 0x463E2F);
        shaftSection(mesh, 9.4, 9.95, 0.49, 0.4, 0x463E2F);
        gripWrap(mesh);
        shaftSection(mesh, 9.9, 10.22, 0.58, 0.58, GOLD);
        shaftSection(mesh, 10.22, 10.34, 0.48, 0.48, GOLD_LIGHT);

        // 连续灯条改成有间隔的短刻纹，保留暗面，避免像荧光管。
        shaftRune(mesh, 11.65, 0.58);
        shaftRune(mesh, 14.0, 0.82);
        shaftRune(mesh, 16.4, 0.52);
        shaftSection(mesh, 17.8, 18.03, 0.47, 0.47, GOLD);
        shaftSection(mesh, 18.03, 19.1, 0.43, 0.56, METAL);
        shaftSection(mesh, 19.1, 19.35, 0.56, 0.82, RING_TRIM);
        shaftSection(mesh, 19.35, 19.65, 0.82, 0.82, RING_TRIM);
        shaftSection(mesh, 19.65, 20.1, 0.72, 0.52, METAL_LIGHT);
        shaftSection(mesh, 20.1, 20.65, 0.52, 0.52, METAL);
        // 杖颈由八棱套筒收束成托架中轴，避免杖头像浮在细杆上。
        shaftSection(mesh, 20.65, 21.25, 0.52, 0.42, METAL_LIGHT);
        shaftSection(mesh, 21.25, 21.55, 0.58, 0.58, GOLD);
        shaftSection(mesh, 21.55, 22.1, 0.42, 0.34, METAL);
        shaftRune(mesh, 19.76, 0.5);

        crossedCradle(mesh);
        box(mesh, -0.65, 2.7, -0.65, 0.65, 3.15, 0.65, GOLD, false);
        box(mesh, -0.95, 2.25, -0.7, 0.95, 2.7, 0.7, METAL_LIGHT, false);
        mesh.addAll(sphere(new Point(0, 1.6, 0), 0.88, 4));
        box(mesh, -0.27, 0.2, -0.27, 0.27, 0.8, 0.27, GOLD_LIGHT, false);
        // 尾坠四条金属护条，不遮掉中间的小经验珠。
        for (int sign : new int[]{-1, 1}) {
            box(mesh, sign * 0.92 - 0.12, 0.9, -0.18,
                    sign * 0.92 + 0.12, 2.35, 0.18, GOLD, false);
            box(mesh, -0.18, 0.9, sign * 0.92 - 0.12,
                    0.18, 2.35, sign * 0.92 + 0.12, GOLD, false);
        }
        return List.copyOf(mesh);
    }

    private record CradlePixel(int x, int y, int z) {}

    private static void crossedCradle(List<Quad> mesh) {
        Set<CradlePixel> pixels = new LinkedHashSet<>();
        pixels.add(new CradlePixel(0, 0, 0));
        int previousHeight = 0;
        for (int step = 1; step <= 5; step++) {
            int height = Math.min(2, (step + 1) / 2);
            // 四臂抬成浅托座；最高点贴近球的下缘，台阶仍保持像素连通。
            for (int y = previousHeight; y <= height; y++) {
                for (int sign : new int[]{-1, 1}) {
                    pixels.add(new CradlePixel(sign * step, y, 0));
                    pixels.add(new CradlePixel(0, y, sign * step));
                }
            }
            previousHeight = height;
        }
        double half = RING_PIXEL_SIZE / 2;
        for (CradlePixel pixel : pixels) {
            double x = pixel.x * RING_PIXEL_SIZE;
            double y = CORE_Y - 3.0 + pixel.y * RING_PIXEL_SIZE;
            double z = pixel.z * RING_PIXEL_SIZE;
            int reach = Math.max(Math.abs(pixel.x), Math.abs(pixel.z));
            int color = reach == 5 ? GOLD : METAL_LIGHT;
            List<Quad> cube = new ArrayList<>(6);
            box(cube, x - half, y - half, z - half, x + half, y + half, z + half, color, false);
            for (Quad face : cube) {
                CradlePixel neighbor = new CradlePixel(pixel.x + (int) face.normal.x,
                        pixel.y + (int) face.normal.y, pixel.z + (int) face.normal.z);
                if (!pixels.contains(neighbor)) {
                    // 金色只落在上沿和端头，暗色承托本体让液球与轨道更突出。
                    int faceColor = face.normal.y > 0.9 && reach < 5 ? GOLD : color;
                    quad(mesh, face.a, face.b, face.c, face.d, faceColor, false);
                }
            }
        }
    }

    private static void shaftSection(List<Quad> mesh, double y0, double y1,
                                      double radius0, double radius1, int color) {
        for (int side = 0; side < 8; side++) {
            double a = Math.PI / 8 + side * Math.PI / 4;
            double b = a + Math.PI / 4;
            int facet = mix(color, side % 2 == 0 ? GOLD_LIGHT : METAL, 0.10);
            quad(mesh, shaftPoint(radius0, a, y0), shaftPoint(radius1, a, y1),
                    shaftPoint(radius1, b, y1), shaftPoint(radius0, b, y0), facet, false);
        }
        for (int side = 0; side < 8; side += 2) {
            double a = Math.PI / 8 + side * Math.PI / 4;
            double b = a + Math.PI / 4;
            double c = b + Math.PI / 4;
            quad(mesh, new Point(0, y0, 0), shaftPoint(radius0, a, y0),
                    shaftPoint(radius0, b, y0), shaftPoint(radius0, c, y0), color, false);
            quad(mesh, new Point(0, y1, 0), shaftPoint(radius1, c, y1),
                    shaftPoint(radius1, b, y1), shaftPoint(radius1, a, y1), color, false);
        }
    }

    private static Point shaftPoint(double radius, double angle, double y) {
        return new Point(radius * Math.cos(angle), y, radius * Math.sin(angle));
    }

    private static void gripWrap(List<Quad> mesh) {
        // 与八棱握柄对齐的实体斜缠带；棕灰色皮革区别于上段金属。
        int segments = 12;
        for (int i = 0; i < segments; i++) {
            double a = Math.PI / 8 + i * Math.PI / 4;
            double b = a + Math.PI / 4;
            double y0 = 5.75 + 3.4 * i / segments;
            double y1 = 5.75 + 3.4 * (i + 1) / segments;
            Point p0 = shaftPoint(0.535, a, y0 - 0.075);
            Point p1 = shaftPoint(0.535, a, y0 + 0.075);
            Point p2 = shaftPoint(0.535, b, y1 + 0.075);
            Point p3 = shaftPoint(0.535, b, y1 - 0.075);
            Point q0 = shaftPoint(0.49, a, y0 - 0.075);
            Point q1 = shaftPoint(0.49, a, y0 + 0.075);
            Point q2 = shaftPoint(0.49, b, y1 + 0.075);
            Point q3 = shaftPoint(0.49, b, y1 - 0.075);
            quad(mesh, p0, p1, p2, p3, 0x73533D, false);
            quad(mesh, q0, p0, p3, q3, 0x49392F, false);
            quad(mesh, p1, q1, q2, p2, 0xA1794A, false);
            if (i == 0) quad(mesh, q0, q1, p1, p0, 0x73533D, false);
            if (i == segments - 1) quad(mesh, p3, p2, q2, q3, 0x73533D, false);
        }
    }

    private static void shaftRune(List<Quad> mesh, double y, double height) {
        shaftMark(mesh, -0.25, y - 0.12, 0.25, y + height + 0.12,
                0.378, 0.402, METAL_LIGHT, false);
        shaftMark(mesh, -0.09, y, 0.09, y + height,
                0.405, 0.43, EXPERIENCE, true);
        shaftMark(mesh, -0.09, y + height - 0.1, 0.24, y + height,
                0.405, 0.43, EXPERIENCE, true);
        shaftMark(mesh, -0.24, y, 0.09, y + 0.1,
                0.405, 0.43, EXPERIENCE, true);
    }

    private static void shaftMark(List<Quad> mesh, double x0, double y0, double x1, double y1,
                                   double near, double far, int color, boolean emissive) {
        box(mesh, x0, y0, near, x1, y1, far, color, emissive);
        box(mesh, -x1, y0, -far, -x0, y1, -near, color, emissive);
        box(mesh, near, y0, -x1, far, y1, -x0, color, emissive);
        box(mesh, -far, y0, x0, -near, y1, x1, color, emissive);
    }

    private static List<Quad> sphere(Point center, double radius, int divisions) {
        List<Quad> mesh = new ArrayList<>();
        // 将六个细分立方体表面投射到球面：无极点孔洞，所有方向都有完整轮廓。
        for (int face = 0; face < 6; face++) {
            for (int u = 0; u < divisions; u++) {
                for (int v = 0; v < divisions; v++) {
                    Point a = spherePoint(face, u, v, divisions);
                    Point b = spherePoint(face, u + 1, v, divisions);
                    Point c = spherePoint(face, u + 1, v + 1, divisions);
                    Point d = spherePoint(face, u, v + 1, divisions);
                    Point normal = new Point(a.x + b.x + c.x + d.x,
                            a.y + b.y + c.y + d.y, a.z + b.z + c.z + d.z).normalized();
                    double shade = Math.max(0, -0.38 * normal.x + 0.72 * normal.y
                            + 0.57 * normal.z);
                    shade = Math.round(shade * 5) / 5.0;
                    int color = mix(0x277D2D, 0xC5EE3C, 0.24 + 0.76 * shade);
                    if (shade >= 0.99) {
                        color = 0xF5FFB5;
                    }
                    quad(mesh, scaled(a, center, radius), scaled(b, center, radius),
                            scaled(c, center, radius), scaled(d, center, radius), color, true);
                }
            }
        }
        return List.copyOf(mesh);
    }

    private static List<List<CorePixel>> buildLightFrames() {
        List<List<CorePixel>> frames = new ArrayList<>();
        int bound = (int) Math.ceil(CORE_RADIUS / CORE_PIXEL_SIZE);
        for (int frame = 0; frame < CORE_FRAME_COUNT; frame++) {
            double phase = frame * Math.PI * 2 / CORE_FRAME_COUNT;
            List<CorePixel> pixels = new ArrayList<>();
            for (int u = -bound; u < bound; u++) {
                for (int v = -bound; v < bound; v++) {
                    double x = (u + 0.5) * CORE_PIXEL_SIZE;
                    double y = (v + 0.5) * CORE_PIXEL_SIZE;
                    double distance = Math.hypot(x, y);
                    if (distance > CORE_RADIUS) continue;
                    double flow = Math.sin(2.8 * x - 2.2 * y + phase
                            + 0.55 * Math.sin(3 * y - phase));
                    double brightness = 1 - Math.hypot(x + 0.18, y - 0.20) / CORE_RADIUS
                            + 0.10 * flow;
                    int color = brightness > 0.60 ? 0xF1FFC2
                            : brightness > 0.30 ? 0xD0F66B
                            : brightness > 0.06 ? 0x98E84A : 0x51C947;
                    // 两道波形色带在同一张实心圆盘上流动，不再叠放易被排序切碎的透明片。
                    double streamA = Math.abs(y - 0.28 * Math.sin(2.2 * x - phase) - 0.10 * x);
                    double streamB = Math.abs(y + 0.24 * Math.sin(1.9 * x + phase) + 0.22 * x);
                    boolean firstFlow = distance > 0.28 && Math.abs(x) < 1.22 && streamA < 0.23;
                    boolean secondFlow = distance > 0.28 && Math.abs(x) < 1.22 && streamB < 0.23;
                    if (firstFlow && secondFlow) color = 0xF4FF9C;
                    else if (firstFlow) color = 0xD8FF4D;
                    else if (secondFlow) color = 0x26D948;
                    pixels.add(new CorePixel(x, y, 0, color));
                }
            }
            frames.add(List.copyOf(pixels));
        }
        return List.copyOf(frames);
    }

    private static Point spherePoint(int face, int uIndex, int vIndex, int divisions) {
        double u = -1 + 2.0 * uIndex / divisions;
        double v = -1 + 2.0 * vIndex / divisions;
        return switch (face) {
            case 0 -> new Point(1, u, v).normalized();
            case 1 -> new Point(-1, u, -v).normalized();
            case 2 -> new Point(u, 1, -v).normalized();
            case 3 -> new Point(u, -1, v).normalized();
            case 4 -> new Point(u, v, 1).normalized();
            default -> new Point(-u, v, -1).normalized();
        };
    }

    private static Point scaled(Point point, Point center, double radius) {
        return new Point(center.x + point.x * radius, center.y + point.y * radius,
                center.z + point.z * radius);
    }

    private record RingPixel(int x, int y) {}

    private static Map<RingPixel, Integer> ringOrnaments(Map<RingPixel, Double> pixels,
                                                         double radius, double start, double sweep,
                                                         int direction) {
        Map<RingPixel, Integer> ornaments = new LinkedHashMap<>();
        pixels.forEach((pixel, progress) -> {
            double distance = Math.hypot(pixel.x + 0.5, pixel.y + 0.5) * RING_PIXEL_SIZE;
            // 亮色集中在迎向旋转方向的宽头，尾部只保留连续的细金属轮廓。
            if (progress < 0.32 || distance >= radius) {
                ornaments.put(pixel, RING_TRIM);
            }
            if (progress < 0.08) ornaments.put(pixel, GOLD_LIGHT);
        });
        double progress = 0.18;
        double angle = start + sweep * (direction > 0 ? 1 - progress : progress);
        double ornamentRadius = radius - RING_PIXEL_SIZE / 2;
        double targetX = ornamentRadius * Math.cos(angle) / RING_PIXEL_SIZE - 0.5;
        double targetY = ornamentRadius * Math.sin(angle) / RING_PIXEL_SIZE - 0.5;
        RingPixel anchor = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (RingPixel pixel : pixels.keySet()) {
            double distance = Math.pow(pixel.x - targetX, 2) + Math.pow(pixel.y - targetY, 2);
            if (distance < nearest) {
                nearest = distance;
                anchor = pixel;
            }
        }
        if (anchor == null) return ornaments;
        // 单颗经验晶片嵌在宽端，与外沿描金错开，避免整条弧被涂成一色。
        ornaments.put(anchor, EXPERIENCE);
        return ornaments;
    }

    private static List<Quad> ring(double radius, double start, double length, int direction) {
        List<Quad> mesh = new ArrayList<>();
        pixelArc(mesh, radius, start, length, direction);
        return List.copyOf(mesh);
    }

    private static void pixelArc(List<Quad> mesh, double radius, double start,
                                  double length, int direction) {
        Map<RingPixel, Double> pixels = new LinkedHashMap<>();
        double pixelSize = RING_PIXEL_SIZE;
        int bound = (int) Math.ceil(radius / pixelSize) + 2;
        double startAngle = Math.toRadians(start);
        double sweep = Math.toRadians(length);
        for (int x = -bound; x < bound; x++) {
            for (int y = -bound; y < bound; y++) {
                double px = (x + 0.5) * pixelSize;
                double py = (y + 0.5) * pixelSize;
                double angle = (Math.atan2(py, px) - startAngle) % (2 * Math.PI);
                if (angle < 0) angle += 2 * Math.PI;
                if (angle >= sweep) continue;
                // 进度从宽头指向拖尾；反向轨道同步翻转，避免细尾朝前。
                double progress = direction > 0 ? 1 - angle / sweep : angle / sweep;
                int widthPixels = progress < 0.16 ? 4
                        : progress < 0.34 ? 3
                        : progress < 0.58 ? 2 : 1;
                if (Math.abs(Math.hypot(px, py) - radius)
                        <= widthPixels * pixelSize / 2) {
                    pixels.put(new RingPixel(x, y), progress);
                }
            }
        }

        // 对角连接补成四邻域阶梯，避免一像素尾端变成只在角点接触的散珠。
        for (RingPixel pixel : List.copyOf(pixels.keySet())) {
            for (int step : new int[]{-1, 1}) {
                RingPixel diagonal = new RingPixel(pixel.x + 1, pixel.y + step);
                RingPixel horizontal = new RingPixel(pixel.x + 1, pixel.y);
                RingPixel vertical = new RingPixel(pixel.x, pixel.y + step);
                if (!pixels.containsKey(diagonal)
                        || pixels.containsKey(horizontal) || pixels.containsKey(vertical)) continue;
                double hRadius = Math.hypot(horizontal.x + 0.5, horizontal.y + 0.5);
                double vRadius = Math.hypot(vertical.x + 0.5, vertical.y + 0.5);
                RingPixel bridge = hRadius >= vRadius ? horizontal : vertical;
                pixels.put(bridge, (pixels.get(pixel) + pixels.get(diagonal)) / 2);
            }
        }
        Map<RingPixel, Integer> ornaments =
                ringOrnaments(pixels, radius, startAngle, sweep, direction);
        // 主体和包边的侧面仅轻微压暗，转到侧面也保留连续的金属轮廓。
        for (RingPixel pixel : pixels.keySet()) {
            int faceColor = ornaments.getOrDefault(pixel, RING_METAL);
            boolean emissive = faceColor == EXPERIENCE;
            int sideColor = mix(emissive ? RING_METAL : faceColor, 0x202D28, 0.3);
            double x0 = pixel.x * pixelSize;
            double y0 = pixel.y * pixelSize;
            double x1 = x0 + pixelSize;
            double y1 = y0 + pixelSize;
            double z0 = -pixelSize / 2;
            double z1 = pixelSize / 2;
            Point a = new Point(x0, y0, z0);
            Point b = new Point(x1, y0, z0);
            Point c = new Point(x1, y1, z0);
            Point d = new Point(x0, y1, z0);
            Point e = new Point(x0, y0, z1);
            Point f = new Point(x1, y0, z1);
            Point g = new Point(x1, y1, z1);
            Point h = new Point(x0, y1, z1);
            quad(mesh, b, a, d, c, faceColor, emissive);
            quad(mesh, e, f, g, h, faceColor, emissive);
            // 只输出外露的方块侧壁，无细线贴层或共面的重复面。
            if (!pixels.containsKey(new RingPixel(pixel.x - 1, pixel.y))) {
                quad(mesh, a, e, h, d, sideColor, false);
            }
            if (!pixels.containsKey(new RingPixel(pixel.x + 1, pixel.y))) {
                quad(mesh, f, b, c, g, sideColor, false);
            }
            if (!pixels.containsKey(new RingPixel(pixel.x, pixel.y - 1))) {
                quad(mesh, a, b, f, e, sideColor, false);
            }
            if (!pixels.containsKey(new RingPixel(pixel.x, pixel.y + 1))) {
                quad(mesh, d, h, g, c, sideColor, false);
            }
        }
    }

    private static void box(List<Quad> mesh, double x0, double y0, double z0,
                            double x1, double y1, double z1, int color, boolean emissive) {
        Point a = new Point(x0, y0, z0);
        Point b = new Point(x1, y0, z0);
        Point c = new Point(x1, y1, z0);
        Point d = new Point(x0, y1, z0);
        Point e = new Point(x0, y0, z1);
        Point f = new Point(x1, y0, z1);
        Point g = new Point(x1, y1, z1);
        Point h = new Point(x0, y1, z1);
        quad(mesh, b, a, d, c, color, emissive);
        quad(mesh, e, f, g, h, color, emissive);
        quad(mesh, a, e, h, d, color, emissive);
        quad(mesh, f, b, c, g, color, emissive);
        quad(mesh, d, h, g, c, color, emissive);
        quad(mesh, a, b, f, e, color, emissive);
    }

    private static void quad(List<Quad> mesh, Point a, Point b, Point c, Point d,
                             int color, boolean emissive) {
        quad(mesh, a, b, c, d, color, emissive, 255);
    }

    private static void quad(List<Quad> mesh, Point a, Point b, Point c, Point d,
                             int color, boolean emissive, int alpha) {
        mesh.add(new Quad(a, b, c, d, b.subtract(a).cross(d.subtract(a)).normalized(),
                color, emissive, alpha));
    }

    private static int mix(int a, int b, double amount) {
        int r = (int) (((a >> 16) & 255) * (1 - amount) + ((b >> 16) & 255) * amount);
        int g = (int) (((a >> 8) & 255) * (1 - amount) + ((b >> 8) & 255) * amount);
        int blue = (int) ((a & 255) * (1 - amount) + (b & 255) * amount);
        return (r << 16) | (g << 8) | blue;
    }
}
