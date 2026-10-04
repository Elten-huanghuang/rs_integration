package com.huanghuang.rsintegration.util;

import net.minecraft.network.chat.Component;

/** 磁盘提示的配色和透明度曲线，不依赖客户端渲染器。 */
public final class DiskTooltipEffects {
    public enum Theme { GUIXU, RESONANCE }
    public enum Tone { MAIN, FLUID, MUTED, HINT, WARNING }

    // 所有磁盘提示共用鎏金色系，只通过明暗区分信息层级。
    private static final int[] GOLD = {0xDCAD48, 0xFFF1B0, 0xF4C95D, 0xDCAD48};
    private static final int[] RESONANCE = {0xC58A28, 0xFFE29A, 0xE6B84F, 0xC58A28};
    private static final int[] FLUID = {0xB98224, 0xFFE9A8, 0xD6A23D, 0xB98224};
    private static final int[] MUTED = {0x8C702D, 0xD8BD77, 0xB3934A, 0x8C702D};
    private static final int[] HINT = {0xA8751C, 0xFFE5A1, 0xD9A83A, 0xA8751C};
    private static final int[] WARNING = {0xA96E17, 0xFFD77A, 0xC98C22, 0xA96E17};

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
        return TextBuilder.of(component)
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
        int[] source = switch (tone) {
            case MAIN -> theme == Theme.GUIXU ? GOLD : RESONANCE;
            case FLUID -> FLUID;
            case MUTED -> MUTED;
            case HINT -> HINT;
            case WARNING -> WARNING;
        };
        // 用同一条淡入/呼吸曲线调节颜色亮度；Minecraft 字体没有逐字 alpha，
        // 因此通过亮度模拟淡入淡出，既有动画又不会让文字完全消失。
        double brightness = alpha(240L, millis, false) / 255.0;
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
