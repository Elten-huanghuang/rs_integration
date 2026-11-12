package com.huanghuang.rsintegration.client.storage;

import com.huanghuang.rsintegration.config.RSIntegrationConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 为 RSI 的特殊物品提示绘制动态底板。
 *
 * <p>原版 tooltip 背景只支持上下两种颜色。这里先绘制星云底图和流动星芒，
 * 再把原版底板设为透明，使文字和布局仍然由原版负责。</p>
 */
@OnlyIn(Dist.CLIENT)
public final class DiskTooltipBackground {
    private static final String MOD_ID = "rs_integration";
    private static final int FRAME_WIDTH = 512;
    private static final int FRAME_HEIGHT = 256;
    private static final int FRAME_COLUMNS = 4;
    private static final int FRAME_COUNT = 24;
    private static final double LOOP_SECONDS = 24.0;
    private static final int TEXTURE_WIDTH = FRAME_WIDTH * FRAME_COLUMNS;
    private static final int TEXTURE_HEIGHT = FRAME_HEIGHT * (FRAME_COUNT / FRAME_COLUMNS);
    private static final int STAR_TEXTURE_SIZE = 128;
    private static final ResourceLocation STAR = texture("star");

    private static final Theme RESONANCE = new Theme(
            texture("resonance_nebula"),
            0xFFB9F4FF, 0xFFE3C9FF,
            0xFF8BDFFF, 0xFFC39AFF, Artwork.NEBULA);
    private static final Theme RESONANCE_DISK = new Theme(
            RESONANCE.background(), RESONANCE.glowA(), RESONANCE.glowB(),
            RESONANCE.borderA(), RESONANCE.borderB(), Artwork.RESONANCE);
    private static final Theme GUIXU = new Theme(
            texture("guixu_nebula"),
            0xFFFFF1BC, 0xFFFFDB87,
            0xFFFFE59A, 0xFFDCAD48, Artwork.GUIXU);
    private static final Theme EXPERIENCE = new Theme(
            texture("experience_enamel"),
            0xFFB8EF57, 0xFFEEF7B6,
            0xFFD1F66D, 0xFF8BC447, Artwork.EXPERIENCE);
    private static final Theme FOOD = new Theme(
            texture("food_enamel"),
            0xFFFFD399, 0xFFF1E6CA,
            0xFFB9C38F, 0xFFE2C89C, Artwork.FOOD);
    private static final Theme MANA = new Theme(
            texture("mana_water"), 0xFF88E9F1, 0xFFD4FFF0,
            0xFF77D8DE, 0xFF8ABDE8, Artwork.MANA);
    private static final Theme SOURCE = new Theme(
            texture("source_runes"), 0xFFD5A6FF, 0xFFFFC8EC,
            0xFFC4A2EF, 0xFFDCADD5, Artwork.SOURCE);
    private static final Theme PICKUP = dimension(0xFFFFB15E, 0xFFFFDCA0);
    private static final Theme MAGNET = dimension(0xFFFFA85C, 0xFFFFCA8A);
    private static final Theme REFILL = dimension(0xFFFFC06B, 0xFFFFE1AA);
    private static final Theme FEEDING = dimension(0xFFFFB875, 0xFFFFD29B);
    private static final Theme VOID = new Theme(
            texture("resonance_nebula"), 0xFFBB9DE2, 0xFFEAC7F6,
            0xFFA997CA, 0xFFC4ABD9, Artwork.VOID);

    private DiskTooltipBackground() {}

    private static ResourceLocation texture(String name) {
        return new ResourceLocation(MOD_ID, "textures/gui/disk_tooltip/" + name + ".png");
    }

    private static Theme dimension(int first, int second) {
        return new Theme(texture("dimension_circuit"), first, second, first, second, Artwork.DIMENSION);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTooltipGatherComponents(RenderTooltipEvent.GatherComponents event) {
        Theme theme = themeFor(event.getItemStack());
        if (theme == null) return;
        int frameSpace = switch (theme.artwork()) {
            case RESONANCE -> ResonanceTooltipArt.HORIZONTAL_SPACE;
            case GUIXU -> GuixuTooltipArt.HORIZONTAL_SPACE;
            case FOOD -> OrderTerminalTooltipArt.HORIZONTAL_SPACE;
            default -> 0;
        };
        if (frameSpace == 0) return;
        int maxWidth = Math.max(1, event.getScreenWidth() - frameSpace);
        if (event.getMaxWidth() > 0) maxWidth = Math.min(maxWidth, event.getMaxWidth());
        event.setMaxWidth(maxWidth);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTooltipPre(RenderTooltipEvent.Pre event) {
        Theme theme = themeFor(event.getItemStack());
        if (theme != null && theme.artwork() == Artwork.RESONANCE) {
            ResonanceTooltipArt.reserveFrameSpace(event);
        } else if (theme != null && theme.artwork() == Artwork.GUIXU) {
            GuixuTooltipArt.reserveFrameSpace(event);
        } else if (theme != null && theme.artwork() == Artwork.FOOD) {
            OrderTerminalTooltipArt.reserveFrameSpace(event);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTooltipColor(RenderTooltipEvent.Color event) {
        Theme theme = themeFor(event.getItemStack());
        if (theme == null || event.getComponents().isEmpty()) return;

        int width = tooltipWidth(event.getFont(), event.getComponents());
        int height = tooltipHeight(event.getComponents());
        if (width <= 0 || height <= 0) return;

        int left = event.getX() - 3;
        int top = event.getY() - 3;
        int right = event.getX() + width + 3;
        int bottom = event.getY() + height + 3;
        if (theme.artwork() == Artwork.RESONANCE) {
            // 为角饰与横饰补出留白，文字仍由原版按原来的组件坐标绘制。
            left -= ResonanceTooltipArt.SIDE_SPACE;
            right += ResonanceTooltipArt.SIDE_SPACE;
            top -= ResonanceTooltipArt.HEADER_SPACE;
            bottom += ResonanceTooltipArt.BOTTOM_SPACE;
        } else if (theme.artwork() == Artwork.GUIXU) {
            left -= GuixuTooltipArt.SIDE_SPACE;
            right += GuixuTooltipArt.SIDE_SPACE;
            top -= GuixuTooltipArt.HEADER_SPACE;
            bottom += GuixuTooltipArt.BOTTOM_SPACE;
        } else if (theme.artwork() == Artwork.FOOD) {
            left -= OrderTerminalTooltipArt.LEFT_SPACE;
            right += OrderTerminalTooltipArt.RIGHT_SPACE;
            top -= OrderTerminalTooltipArt.HEADER_SPACE;
            bottom += OrderTerminalTooltipArt.BOTTOM_SPACE;
        }

        renderBackground(event.getGraphics(), left, top, right, bottom, theme,
                System.currentTimeMillis() / 1000.0);

        // 原版填充会覆盖自定义底板；透明颜色保留我们刚刚绘制的内容。
        event.setBackground(0x00000000);
        event.setBorderStart(0x00000000);
        event.setBorderEnd(0x00000000);
    }

    static Theme themeFor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null ? null : themeFor(id);
    }

    /** 让提示框美化模组避开已经由 RSI 绘制底板的物品。 */
    public static boolean hasCustomBackground(Item item) {
        return item != null && themeFor(ForgeRegistries.ITEMS.getKey(item)) != null;
    }

    static Theme themeFor(ResourceLocation id) {
        if (!RSIntegrationConfig.enabled(RSIntegrationConfig.CLIENT_SPEC,
                RSIntegrationConfig.TOOLTIP_CUSTOM_BACKGROUNDS)) return null;
        if (id == null || !MOD_ID.equals(id.getNamespace())) return null;
        return switch (id.getPath()) {
            case "resonance_storage_disk" -> RESONANCE_DISK;
            case "resonance_network_generator" -> RESONANCE;
            case "unified_storage_disk" -> GUIXU;
            case "resonant_experience_wand" -> EXPERIENCE;
            case "order_terminal" -> FOOD;
            case "resonant_mana_pool" -> MANA;
            case "resonant_source_tank" -> SOURCE;
            case "rs_pickup_upgrade" -> PICKUP;
            case "rs_magnet_upgrade" -> MAGNET;
            case "rs_refill_upgrade" -> REFILL;
            case "rs_feeding_upgrade" -> FEEDING;
            case "rs_void_upgrade" -> VOID;
            default -> null;
        };
    }

    static int tooltipWidth(Font font, List<ClientTooltipComponent> components) {
        int width = 0;
        for (ClientTooltipComponent component : components) {
            width = Math.max(width, component.getWidth(font));
        }
        return width;
    }

    static void reserveFrameSpace(RenderTooltipEvent.Pre event, int leftMargin, int rightMargin,
                                  int topMargin, int bottomMargin) {
        // 只修正原版鼠标定位，自定义位置的配方预览仍遵循其自己的布局约束。
        if (event.getTooltipPositioner() != DefaultTooltipPositioner.INSTANCE
                || event.getComponents().isEmpty()) return;
        int width = tooltipWidth(event.getFont(), event.getComponents());
        int height = tooltipHeight(event.getComponents());
        var position = event.getTooltipPositioner().positionTooltip(event.getScreenWidth(),
                event.getScreenHeight(), event.getX(), event.getY(), width, height);
        int maxX = event.getScreenWidth() - width - rightMargin;
        int maxY = event.getScreenHeight() - height - bottomMargin;
        // 原版定位器从输入原点的右侧 12、上方 12 像素开始。
        if (maxX >= leftMargin) event.setX(Math.max(leftMargin, Math.min(maxX, position.x())) - 12);
        if (maxY >= topMargin) event.setY(Math.max(topMargin, Math.min(maxY, position.y())) + 12);
    }

    static int tooltipHeight(List<ClientTooltipComponent> components) {
        int height = 0;
        for (ClientTooltipComponent component : components) {
            height += component.getHeight();
        }
        return components.size() == 1 ? height - 2 : height;
    }

    static void renderBackground(GuiGraphics graphics,
                                 int left, int top, int right, int bottom,
                                 Theme theme, double time) {
        int width = Math.max(1, right - left);
        int height = Math.max(1, bottom - top);
        double cycle = (time % LOOP_SECONDS + LOOP_SECONDS) % LOOP_SECONDS;
        double phase = cycle * Math.PI * 2.0 / LOOP_SECONDS;

        // 底板与原版保持同一深度，遮住物品栏；边框在后面单独提高层级。
        var pose = graphics.pose();
        pose.pushPose();
        try {
            pose.translate(0.0F, 0.0F, 400.0F);
            // Color 事件处于批量绘制中；先提交旧图元，避免它们随后覆盖即时绘制的纹理。
            graphics.flush();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (theme.artwork() == Artwork.NEBULA || theme.artwork() == Artwork.VOID
                    || theme.artwork() == Artwork.RESONANCE || theme.artwork() == Artwork.GUIXU) {
                drawNebula(graphics, left, top, width, height, theme, cycle);
                drawStar(graphics, left, top, width, height,
                        phase, 0.23, 0.24, 0.23, theme.glowA());
                drawStar(graphics, left, top, width, height,
                        -phase + 1.8, 0.82, 0.66, 0.18, theme.glowB());
                drawStar(graphics, left, top, width, height,
                        phase + 3.1, 0.53, 0.30, 0.14, theme.glowA());
            } else if (theme.artwork() == Artwork.EXPERIENCE || theme.artwork() == Artwork.FOOD) {
                ItemTooltipArt.draw(graphics, left, top, width, height, theme, phase);
            } else {
                EquipmentTooltipArt.draw(graphics, left, top, width, height, theme, phase);
            }

            graphics.flush();
            if (theme.artwork() == Artwork.RESONANCE) {
                ResonanceTooltipArt.drawCrt(graphics, left, top, width, height, cycle);
                graphics.flush();
            }
            // 边框在底板与普通 tooltip 字体上方写入深度，贴边文字也不能盖住轮廓。
            pose.translate(0.0F, 0.0F, 8.0F);
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (theme.artwork() == Artwork.GUIXU && GuixuTooltipArt.supportsFrame(width, height)) {
                GuixuTooltipArt.drawFrame(graphics, left, top, width, height);
                graphics.flush();
                return;
            }
            if (theme.artwork() == Artwork.FOOD && OrderTerminalTooltipArt.supportsFrame(width, height)) {
                OrderTerminalTooltipArt.drawFrame(graphics, left, top, width, height);
                graphics.flush();
                return;
            }
            int border = blend(theme.borderA(), theme.borderB(),
                    (float) ((Math.sin(phase) + 1.0) * 0.5));
            if (theme.artwork() == Artwork.RESONANCE && ResonanceTooltipArt.supportsFrame(width, height)) {
                int middle = left + (width - ResonanceTooltipArt.middleWidth()) / 2;
                graphics.fill(left, top, middle, top + 1, withAlpha(border, 255));
                graphics.fill(middle + ResonanceTooltipArt.middleWidth(), top, right, top + 1, withAlpha(border, 255));
            } else {
                graphics.fill(left, top, right, top + 1, withAlpha(border, 255));
            }
            graphics.fill(left, bottom - 1, right, bottom, withAlpha(border, 255));
            graphics.fillGradient(left, top + 1, left + 1, bottom - 1, 0,
                    withAlpha(theme.borderA(), 255), withAlpha(theme.borderB(), 255));
            graphics.fillGradient(right - 1, top + 1, right, bottom - 1, 0,
                    withAlpha(theme.borderA(), 255), withAlpha(theme.borderB(), 255));
            graphics.flush();
            if (theme.artwork() == Artwork.RESONANCE) {
                ResonanceTooltipArt.drawFrame(graphics, left, top, width, height);
                graphics.flush();
            }
        } finally {
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
            pose.popPose();
        }
    }

    private static void drawNebula(GuiGraphics graphics, int left, int top,
                                   int width, int height, Theme theme, double time) {
        // 星云内部由循环帧驱动；相邻帧交叉淡化，让云气连续流动。
        double framePosition = time / LOOP_SECONDS * FRAME_COUNT;
        int frame = (int) framePosition;
        float blend = (float) (framePosition - frame);
        boolean guixuFrame = theme.artwork() == Artwork.GUIXU && GuixuTooltipArt.supportsFrame(width, height);
        int renderWidth = width + (guixuFrame ? 5 : 0);
        double scale = Math.max(renderWidth / (double) FRAME_WIDTH,
                height / (double) FRAME_HEIGHT) * 1.08;
        int sampleWidth = Math.min(FRAME_WIDTH, Math.max(1, (int) Math.ceil(renderWidth / scale)));
        int sampleHeight = Math.min(FRAME_HEIGHT, Math.max(1, (int) Math.ceil(height / scale)));
        float u = (float) ((FRAME_WIDTH - sampleWidth)
                * (0.5 + Math.sin(time * Math.PI * 2.0 / LOOP_SECONDS) * 0.32));
        float v = (float) ((FRAME_HEIGHT - sampleHeight)
                * (0.5 + Math.cos(time * Math.PI * 2.0 / LOOP_SECONDS) * 0.32));
        if (guixuFrame) {
            // 按金框下缘的转折裁剪完整星云，所有片段共用同一采样，避免缩放接缝。
            for (int y = 0; y < height;) {
                int inset = GuixuTooltipArt.leftInset(y, height);
                int outset = GuixuTooltipArt.rightOutset(y, height);
                int end = y + 1;
                while (end < height && GuixuTooltipArt.leftInset(end, height) == inset
                        && GuixuTooltipArt.rightOutset(end, height) == outset) end++;
                graphics.enableScissor(left + inset, top + y, left + width + outset, top + end);
                try {
                    RenderSystem.enableBlend();
                    RenderSystem.defaultBlendFunc();
                    drawNebulaLayers(graphics, left, top, renderWidth, height, theme, frame, blend,
                            u, v, sampleWidth, sampleHeight);
                } finally {
                    graphics.disableScissor();
                }
                y = end;
            }
        } else {
            drawNebulaLayers(graphics, left, top, width, height, theme, frame, blend,
                    u, v, sampleWidth, sampleHeight);
        }
        int offset = theme.artwork() == Artwork.RESONANCE ? ResonanceTooltipArt.glitchOffset(time) : 0;
        if (offset != 0 && width > 2 && height > 2) {
            // 只重采样一条星云，模拟短暂横向失步；不会移动文字、外框或采到邻帧。
            int bandHeight = Math.min(4, height - 2);
            int bandY = Math.max(1, Math.min(height - bandHeight - 1,
                    (int) (height * ResonanceTooltipArt.glitchFraction(time))));
            int sourceHeight = Math.min(sampleHeight,
                    Math.max(1, (int) Math.ceil(sampleHeight * bandHeight / (double) height)));
            float bandU = Math.max(0, Math.min(FRAME_WIDTH - sampleWidth,
                    u - offset * sampleWidth / (float) width));
            float bandV = v + Math.min(sampleHeight - sourceHeight,
                    sampleHeight * bandY / (float) height);
            drawNebulaLayers(graphics, left, top + bandY, width, bandHeight, theme, frame, blend,
                    bandU, bandV, sampleWidth, sourceHeight);
        }
    }

    private static void drawNebulaLayers(GuiGraphics graphics, int left, int top, int width, int height,
                                         Theme theme, int frame, float blend, float u, float v,
                                         int sampleWidth, int sampleHeight) {
        for (int layer = 0; layer < 2; layer++) {
            int index = (frame + layer) % FRAME_COUNT;
            boolean cosmic = theme.artwork() == Artwork.VOID;
            graphics.setColor(cosmic ? 0.88F : 1, cosmic ? 0.76F : 1,
                    cosmic ? 0.94F : 1, layer == 0 ? 1.0F : blend);
            graphics.blit(theme.background(), left, top, width, height,
                    index % FRAME_COLUMNS * FRAME_WIDTH + u,
                    index / FRAME_COLUMNS * FRAME_HEIGHT + v,
                    sampleWidth, sampleHeight, TEXTURE_WIDTH, TEXTURE_HEIGHT);
        }
    }

    private static void drawStar(GuiGraphics graphics, int left, int top,
                                 int width, int height, double phase,
                                 double xFraction, double yFraction,
                                 double sizeFraction, int color) {
        int size = Math.min(Math.min(width, height) - 4,
                Math.max(12, Math.min(40, (int) (height * sizeFraction))));
        if (size < 2) return;
        int x = (int) ((xFraction + Math.sin(phase) * 0.035) * width) - size / 2;
        int y = (int) ((yFraction + Math.cos(phase) * 0.045) * height) - size / 2;
        x = left + Math.max(2, Math.min(width - size - 2, x));
        y = top + Math.max(2, Math.min(height - size - 2, y));
        float alpha = (float) (0.68 + Math.sin(phase * 2.0) * 0.18);
        graphics.setColor((color >> 16 & 255) / 255.0F,
                (color >> 8 & 255) / 255.0F, (color & 255) / 255.0F, alpha);
        graphics.blit(STAR, x, y, size, size, 0.0F, 0.0F,
                STAR_TEXTURE_SIZE, STAR_TEXTURE_SIZE, STAR_TEXTURE_SIZE, STAR_TEXTURE_SIZE);
    }

    private static int withAlpha(int rgb, int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
    }

    private static int blend(int first, int second, float progress) {
        float value = Math.max(0.0F, Math.min(1.0F, progress));
        int red = (int) (((first >> 16 & 255) * (1.0F - value))
                + ((second >> 16 & 255) * value));
        int green = (int) (((first >> 8 & 255) * (1.0F - value))
                + ((second >> 8 & 255) * value));
        int blue = (int) (((first & 255) * (1.0F - value))
                + ((second & 255) * value));
        return red << 16 | green << 8 | blue;
    }

    enum Artwork { NEBULA, RESONANCE, GUIXU, EXPERIENCE, FOOD, MANA, SOURCE, DIMENSION, VOID }

    record Theme(ResourceLocation background, int glowA, int glowB,
                 int borderA, int borderB, Artwork artwork) {}
}
