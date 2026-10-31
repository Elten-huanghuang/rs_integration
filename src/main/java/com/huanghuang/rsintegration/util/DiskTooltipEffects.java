package com.huanghuang.rsintegration.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/** 磁盘提示的配色和透明度曲线，不依赖客户端渲染器。 */
public final class DiskTooltipEffects {
    public enum Theme { GUIXU, RESONANCE, EXPERIENCE, FOOD, MANA, SOURCE, DIMENSION, VOID }
    public enum Tone { MAIN, FLUID, MUTED, HINT, WARNING }

    // 归墟盘使用鎏金色系，只通过明暗区分信息层级。
    private static final int[] GOLD = {0xDCAD48, 0xFFF1B0, 0xF4C95D, 0xDCAD48};
    private static final int[] GUIXU_FLUID = {0xB98224, 0xFFE9A8, 0xD6A23D, 0xB98224};
    private static final int[] GUIXU_MUTED = {0x8C702D, 0xD8BD77, 0xB3934A, 0x8C702D};
    private static final int[] GUIXU_HINT = {0xA8751C, 0xFFE5A1, 0xD9A83A, 0xA8751C};
    private static final int[] GUIXU_WARNING = {0xA96E17, 0xFFD77A, 0xC98C22, 0xA96E17};

    // 共振盘保留原有青蓝紫配色。
    private static final int[] RESONANCE = {0x70DBF0, 0xA2B5FF, 0xD4ACFF, 0x70DBF0};
    private static final int[] RESONANCE_FLUID = {0x65CCD6, 0xB8FFF1, 0x88DAF0, 0x65CCD6};
    private static final int[] RESONANCE_MUTED = {0x91AFB8, 0xD0DFDE, 0xA5BDCC, 0x91AFB8};
    private static final int[] RESONANCE_HINT = {0xB5A0E8, 0xEED4FF, 0xA9D9ED, 0xB5A0E8};
    private static final int[] RESONANCE_WARNING = {0xF19B79, 0xFFDBA0, 0xF19B79};

    private static final int[] EXPERIENCE = {0xD7FF69, 0xF5FFB2, 0xA9F75B, 0xD7FF69};
    private static final int[] EXPERIENCE_FLUID = {0xB9F47D, 0xE9FFB4, 0xB9F47D};
    private static final int[] EXPERIENCE_MUTED = {0xD3E6B8, 0xEBF4D5, 0xD3E6B8};
    private static final int[] EXPERIENCE_HINT = {0xFAECA1, 0xFFFFD1, 0xFAECA1};
    private static final int[] FOOD = {0xFFF1CB, 0xFFF9E5, 0xEBD9AD, 0xFFF1CB};
    private static final int[] FOOD_FLUID = {0xD7ECB0, 0xEDF7CE, 0xD7ECB0};
    private static final int[] FOOD_MUTED = {0xE7DCC1, 0xF5EAD1, 0xE7DCC1};
    private static final int[] FOOD_HINT = {0xFFD99C, 0xFFF1C1, 0xFFD99C};
    private static final int[] MANA = {0x9AEDE9, 0xD2FAFF, 0x83CFFF, 0x9AEDE9};
    private static final int[] MANA_MUTED = {0xB6DBDE, 0xDCF1EB, 0xB6DBDE};
    private static final int[] SOURCE = {0xE3B6FF, 0xFFE0F7, 0xB8BEFF, 0xE3B6FF};
    private static final int[] SOURCE_MUTED = {0xD1C9E6, 0xEFE1EF, 0xD1C9E6};
    private static final int[] DIMENSION = {0xFFB15E, 0xFFE1A3, 0xFFC17A, 0xFFB15E};
    private static final int[] DIMENSION_MUTED = {0xEBD4B8, 0xFFF0D7, 0xEBD4B8};
    private static final int[] VOID = {0xD0B6F7, 0xF3DAFF, 0xAEBBEE, 0xD0B6F7};
    private static final int[] VOID_MUTED = {0xCFC6DF, 0xE8DEEF, 0xCFC6DF};
    private static final int[] EQUIPMENT_HINT = {0xE9DFB3, 0xFFF4D6, 0xE9DFB3};

    private DiskTooltipEffects() {}

    public static int color(Theme theme, Tone tone, int character, long millis) {
        int[] palette = pulsePalette(theme, tone, millis);
        double position = ((Math.floorMod(millis, 4200L) / 4200.0 + character * 0.045) % 1.0)
                * (palette.length - 1);
        int index = (int) position;
        double fraction = position - index;
        fraction = fraction * fraction * (3.0 - 2.0 * fraction);
        int first = palette[index], next = palette[index + 1];
        int red = blend(first >> 16 & 255, next >> 16 & 255, fraction);
        int green = blend(first >> 8 & 255, next >> 8 & 255, fraction);
        int blue = blend(first & 255, next & 255, fraction);
        return red << 16 | green << 8 | blue;
    }

    /**
     * 将静态提示组件转换成带流光和呼吸亮度的主题文字。
     * Tooltip 每帧重建时会重新取时间，因此不需要客户端专用渲染钩子。
     */
    public static Component flow(Component component, Theme theme, Tone tone, float phaseShift) {
        // 逐字拆分前清掉旧式格式码，避免模组名称的 §9§o 被拆成可见的 9o。
        String plain = ChatFormatting.stripFormatting(component.getString());
        Component source = Component.literal(plain).withStyle(component.getStyle());
        return TextBuilder.of(source)
                .colorFlow(2400L, phaseShift, pulsePalette(theme, tone, System.currentTimeMillis()))
                .build();
    }

    public static Component flow(String key, Theme theme, Tone tone, float phaseShift, Object... args) {
        return flow(Component.translatable(key, args), theme, tone, phaseShift);
    }

    /**
     * 只给组件着色，不展开翻译内容。适用于容量、ID 等需要保留参数结构的行。
     */
    public static Component tint(Component component, Theme theme, Tone tone) {
        int rgb = color(theme, tone, 0, System.currentTimeMillis());
        return component.copy().withStyle(style -> style.withColor(rgb));
    }

    public static Component tint(String key, Theme theme, Tone tone, Object... args) {
        return tint(Component.translatable(key, args), theme, tone);
    }

    private static int[] pulsePalette(Theme theme, Tone tone, long millis) {
        int[] source = switch (theme) {
            case GUIXU -> switch (tone) {
                case MAIN -> GOLD;
                case FLUID -> GUIXU_FLUID;
                case MUTED -> GUIXU_MUTED;
                case HINT -> GUIXU_HINT;
                case WARNING -> GUIXU_WARNING;
            };
            case RESONANCE -> switch (tone) {
                case MAIN -> RESONANCE;
                case FLUID -> RESONANCE_FLUID;
                case MUTED -> RESONANCE_MUTED;
                case HINT -> RESONANCE_HINT;
                case WARNING -> RESONANCE_WARNING;
            };
            case EXPERIENCE -> switch (tone) {
                case MAIN -> EXPERIENCE;
                case FLUID -> EXPERIENCE_FLUID;
                case MUTED -> EXPERIENCE_MUTED;
                case HINT, WARNING -> EXPERIENCE_HINT;
            };
            case FOOD -> switch (tone) {
                case MAIN -> FOOD;
                case FLUID -> FOOD_FLUID;
                case MUTED -> FOOD_MUTED;
                case HINT, WARNING -> FOOD_HINT;
            };
            case MANA, SOURCE, DIMENSION, VOID -> switch (tone) {
                case MAIN, FLUID -> switch (theme) {
                    case MANA -> MANA;
                    case SOURCE -> SOURCE;
                    case DIMENSION -> DIMENSION;
                    default -> VOID;
                };
                case MUTED -> switch (theme) {
                    case MANA -> MANA_MUTED;
                    case SOURCE -> SOURCE_MUTED;
                    case DIMENSION -> DIMENSION_MUTED;
                    default -> VOID_MUTED;
                };
                case HINT -> EQUIPMENT_HINT;
                case WARNING -> RESONANCE_WARNING;
            };
        };
        // 用同一条淡入/呼吸曲线调节颜色亮度；Minecraft 字体没有逐字 alpha，
        // 因此通过亮度模拟淡入淡出，既有动画又不会让文字完全消失。
        double brightness = alpha(240L, millis, false) / 255.0;
        if (theme != Theme.GUIXU && theme != Theme.RESONANCE) {
            // 液流和暖木底板更亮，文字只做轻微呼吸，避免暗相位看不清说明。
            brightness = 0.9 + brightness * 0.1;
        }
        int[] result = new int[source.length];
        for (int i = 0; i < source.length; i++) {
            int rgb = source[i];
            int red = (int) Math.round(((rgb >> 16) & 255) * brightness);
            int green = (int) Math.round(((rgb >> 8) & 255) * brightness);
            int blue = (int) Math.round((rgb & 255) * brightness);
            result[i] = red << 16 | green << 8 | blue;
        }
        return result;
    }

    public static int alpha(long hoverMillis, long animationMillis, boolean important) {
        double fade = Math.max(0.0, Math.min(1.0, hoverMillis / 240.0));
        fade = fade * fade * (3.0 - 2.0 * fade);
        double wave = (1.0 + Math.cos(Math.floorMod(animationMillis, 3600L) * Math.PI * 2 / 3600.0)) / 2.0;
        double minimum = important ? 0.82 : 0.62;
        // Font 会把极低 alpha 当作默认不透明；下限同时避免提示开始时完全不可读。
        return Math.max(8, (int) Math.round(255 * fade * (minimum + (1.0 - minimum) * wave)));
    }

    private static int blend(int first, int next, double fraction) {
        return (int) Math.round(first + (next - first) * fraction);
    }
}
