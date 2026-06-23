package com.huanghuang.rsintegration.util;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Objects;

public class TextBuilder {

    private MutableComponent component;
    public static final TextBuilder EMPTY = of("");

    private TextBuilder(MutableComponent component) {
        this.component = Objects.requireNonNull(component, "component must not be null");
    }

    // ═══════════════════════════════════════════════════════════════
    // 工厂方法
    // ═══════════════════════════════════════════════════════════════

    public static TextBuilder of(Object o) {
        if (o == null) return new TextBuilder(Component.empty());
        if (o instanceof MutableComponent mc) return new TextBuilder(mc);
        if (o instanceof TextBuilder tb) return new TextBuilder(tb.build());
        if (o instanceof Component c) return new TextBuilder(c.copy());
        return new TextBuilder(Component.literal(String.valueOf(o)));
    }

    public static TextBuilder translate(String key, Object... args) {
        Objects.requireNonNull(key, "translation key must not be null");
        Object[] processed = processTranslationArgs(args);
        return new TextBuilder(Component.translatable(key, processed));
    }

    public static TextBuilder translateStyled(String key, ChatFormatting color, Object... args) {
        Objects.requireNonNull(key, "translation key must not be null");
        Objects.requireNonNull(color, "color must not be null");
        Object[] processed = processTranslationArgs(args);
        return new TextBuilder(Component.translatable(key, processed).withStyle(color));
    }

    public static TextBuilder translateStyled(String key, int rgbColor, Object... args) {
        Objects.requireNonNull(key, "translation key must not be null");
        Object[] processed = processTranslationArgs(args);
        return new TextBuilder(Component.translatable(key, processed).withStyle(s -> s.withColor(rgbColor)));
    }

    private static Object[] processTranslationArgs(Object... args) {
        if (args == null || args.length == 0) return new Object[0];
        return Arrays.stream(args).map(TextBuilder::convertToComponent).toArray();
    }

    private static Component convertToComponent(Object obj) {
        if (obj instanceof TextBuilder tb) return tb.build();
        if (obj instanceof Component c) return c;
        if (obj instanceof ColorableArg ca) return Component.literal(String.valueOf(ca.value())).withStyle(ca.color());
        if (obj instanceof ColorableRgbArg cra) return Component.literal(String.valueOf(cra.value())).withStyle(s -> s.withColor(cra.rgbColor()));
        if (obj == null) return Component.empty();
        return Component.literal(String.valueOf(obj));
    }

    // ═══════════════════════════════════════════════════════════════
    // 颜色参数包装
    // ═══════════════════════════════════════════════════════════════

    public static ColorableArg arg(Object value, ChatFormatting color) {
        Objects.requireNonNull(color, "color must not be null");
        return new ColorableArg(value, color);
    }

    public static ColorableRgbArg arg(Object value, int rgbColor) {
        return new ColorableRgbArg(value, rgbColor);
    }

    public record ColorableArg(Object value, ChatFormatting color) {
        public ColorableArg { Objects.requireNonNull(color, "color must not be null"); }
    }

    public record ColorableRgbArg(Object value, int rgbColor) {}

    // ═══════════════════════════════════════════════════════════════
    // 交互事件
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder withClickEvent(ClickEvent.Action action, String value) {
        Objects.requireNonNull(action, "click action must not be null");
        Objects.requireNonNull(value, "action value must not be null");
        component.withStyle(s -> s.withClickEvent(new ClickEvent(action, value)));
        return this;
    }

    public TextBuilder withHoverEvent(HoverEvent event) {
        Objects.requireNonNull(event, "hover event must not be null");
        component.withStyle(s -> s.withHoverEvent(event));
        return this;
    }

    public TextBuilder suggestCommand(String command) {
        Objects.requireNonNull(command, "command must not be null");
        return withClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command);
    }

    public TextBuilder runCommand(String command) {
        Objects.requireNonNull(command, "command must not be null");
        return withClickEvent(ClickEvent.Action.RUN_COMMAND, command);
    }

    public TextBuilder showText(Component text) {
        Objects.requireNonNull(text, "hover text must not be null");
        return withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, text));
    }

    // ═══════════════════════════════════════════════════════════════
    // 颜色快捷方法 - ChatFormatting
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder gray() { return withStyle(ChatFormatting.GRAY); }
    public TextBuilder gold() { return withStyle(ChatFormatting.GOLD); }
    public TextBuilder darkAqua() { return withStyle(ChatFormatting.DARK_AQUA); }
    public TextBuilder red() { return withStyle(ChatFormatting.RED); }
    public TextBuilder darkRed() { return withStyle(ChatFormatting.DARK_RED); }
    public TextBuilder aqua() { return withStyle(ChatFormatting.AQUA); }
    public TextBuilder lightPurple() { return withStyle(ChatFormatting.LIGHT_PURPLE); }
    public TextBuilder green() { return withStyle(ChatFormatting.GREEN); }
    public TextBuilder white() { return withStyle(ChatFormatting.WHITE); }
    public TextBuilder yellow() { return withStyle(ChatFormatting.YELLOW); }
    public TextBuilder darkGray() { return withStyle(ChatFormatting.DARK_GRAY); }
    public TextBuilder cornflowerBlue() { return withStyle(0xFF6495ED); }
    public TextBuilder darkPurple() { return withStyle(ChatFormatting.DARK_PURPLE); }

    public TextBuilder withStyle(ChatFormatting formatting) {
        Objects.requireNonNull(formatting, "formatting type must not be null");
        component.withStyle(formatting);
        return this;
    }

    public TextBuilder withStyle(int rgbColor) {
        component.withStyle(s -> s.withColor(rgbColor));
        return this;
    }

    public TextBuilder withFont(ResourceLocation font) {
        Objects.requireNonNull(font, "font resource location must not be null");
        component.withStyle(s -> s.withFont(font));
        return this;
    }

    // ═══════════════════════════════════════════════════════════════
    // 渐变色预设
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder holidayFlow() { return colorFlow(0xFF0000, 0x00FF00, 0xFFCC00); }
    public TextBuilder oceanFlow() { return colorFlow(0x1E90FF, 0x00CED1, 0xC0FF, 0x87CEEB); }
    public TextBuilder fireFlow() { return colorFlow(0xFF4500, 0xFF6347, 0xFF7F50, 0xFFA500); }
    public TextBuilder forestFlow() { return colorFlow(0x228B22, 0x32CD32, 0x90EE50, 0x98FB98); }
    public TextBuilder rainbowFlow() { return colorFlow(0xFF0000, 0xFF7700, 0xFFCC00, 0x00FF00, 0x0000FF, 0x4B0082, 0x9400D3); }
    public TextBuilder pastelFlow() { return colorFlow(0xFFB6C1, 0xFFD700, 0x98FB98, 0x87CEEB, 0xDDA0DD); }

    // ═══════════════════════════════════════════════════════════════
    // spectrumGradient - 色相环渐变
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder spectrumGradient() {
        return spectrumGradient(1000L);
    }

    public TextBuilder spectrumGradient(long speed) {
        if (speed <= 0) throw new IllegalArgumentException("flow speed must be greater than 0");
        return applyGradient(GradientConfig.spectrum(speed));
    }

    public TextBuilder spectrumGradient(float phaseShift) {
        return applyGradient(GradientConfig.spectrum(1000L, phaseShift));
    }

    public TextBuilder spectrumGradient(long speed, float phaseShift) {
        return applyGradient(GradientConfig.spectrum(speed, phaseShift));
    }

    // ═══════════════════════════════════════════════════════════════
    // colorFlow - 自定义颜色流动
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder colorFlow(int... colors) {
        return colorFlow(1000L, 0.0F, colors);
    }

    public TextBuilder colorFlow(float phaseShift, int... colors) {
        return colorFlow(1000L, phaseShift, colors);
    }

    public TextBuilder colorFlow(long speed, float phaseShift, int... colors) {
        if (speed <= 0) throw new IllegalArgumentException("flow speed must be greater than 0");
        if (colors == null) throw new NullPointerException("color array must not be null");
        if (colors.length < 2) throw new IllegalArgumentException("at least 2 color values required");
        return applyGradient(GradientConfig.customFlow(speed, phaseShift, colors));
    }

    // ═══════════════════════════════════════════════════════════════
    // {flow} 标记解析
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder processFlowMarkedText() {
        String fullText = buildString();
        if (!fullText.contains("{flow}")) return this;

        MutableComponent result = Component.empty().withStyle(component.getStyle());
        int startLen = "{flow}".length();
        int endLen = "{/flow}".length();
        int lastIndex = 0;
        boolean inFlow = false;

        while (lastIndex < fullText.length()) {
            if (!inFlow) {
                int start = fullText.indexOf("{flow}", lastIndex);
                if (start == -1) {
                    appendPlainText(result, fullText.substring(lastIndex));
                    break;
                }
                if (start > lastIndex) {
                    appendPlainText(result, fullText.substring(lastIndex, start));
                }
                lastIndex = start + startLen;
                inFlow = true;
            } else {
                int end = fullText.indexOf("{/flow}", lastIndex);
                if (end == -1) {
                    appendPlainText(result, fullText.substring(lastIndex));
                    break;
                }
                String flowText = fullText.substring(lastIndex, end);
                if (!flowText.isEmpty()) {
                    result.append(of(flowText).spectrumGradient().build());
                }
                lastIndex = end + endLen;
                inFlow = false;
            }
        }

        return of(result);
    }

    private static void appendPlainText(MutableComponent target, String text) {
        if (text != null && !text.isEmpty()) {
            target.append(Component.literal(text));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 渐变核心
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder applyGradient(GradientConfig config) {
        Objects.requireNonNull(config, "gradient config must not be null");
        String text = component.getString();
        if (text.isEmpty() && component.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents tc) {
            text = net.minecraft.locale.Language.getInstance().getOrDefault(tc.getKey());
        }
        if (text.isEmpty()) return this;

        MutableComponent gradientComponent = Component.empty();
        long time = System.currentTimeMillis();

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                gradientComponent.append(" ");
            } else {
                int color = calculateGradientColor(i, text.length(), time, config);
                gradientComponent.append(Component.literal(String.valueOf(c))
                        .withStyle(s -> s.withColor(color)));
            }
        }

        component = gradientComponent;
        return this;
    }

    private int calculateGradientColor(int charIndex, int textLength, long time, GradientConfig config) {
        float progress = (float) charIndex / textLength;
        float timeProgress = (float) (time % config.speed()) / (float) config.speed();
        float totalProgress = (progress + timeProgress + config.phaseShift()) % 1.0F;

        if (config.gradientType() == GradientType.SPECTRUM) {
            return hueToRgb(totalProgress, 0.8F, 0.9F);
        }

        int colorCount = Math.max(1, config.colors().length);
        float segmentSize = (colorCount > 1) ? (1.0F / (colorCount - 1)) : 1.0F;
        int startIdx = Math.min((int) (totalProgress / segmentSize), colorCount - 1);
        int endIdx = Math.min(startIdx + 1, colorCount - 1);
        float localProgress = (totalProgress % segmentSize) / segmentSize;

        if (config.useHSLInterpolation()) {
            return interpolateColorHSL(config.colors()[startIdx], config.colors()[endIdx],
                    applyEasing(localProgress, config.easing()));
        }
        return interpolateColorWithEasing(config.colors()[startIdx], config.colors()[endIdx],
                localProgress, config.easing());
    }

    // ═══════════════════════════════════════════════════════════════
    // HSL 颜色空间插值
    // ═══════════════════════════════════════════════════════════════

    private int interpolateColorHSL(int startColor, int endColor, float progress) {
        if (progress <= 0.0F) return startColor;
        if (progress >= 1.0F) return endColor;

        float[] startHSL = rgbToHsl(startColor);
        float[] endHSL = rgbToHsl(endColor);

        float hue = interpolateHue(startHSL[0], endHSL[0], progress);
        float saturation = startHSL[1] + (endHSL[1] - startHSL[1]) * progress;
        float lightness = startHSL[2] + (endHSL[2] - startHSL[2]) * progress;

        return hslToRgb(hue, saturation, lightness);
    }

    private float interpolateHue(float startHue, float endHue, float progress) {
        float diff = endHue - startHue;
        if (Math.abs(diff) > 0.5F) {
            if (diff > 0.0F) endHue -= 1.0F;
            else endHue += 1.0F;
        }
        float hue = startHue + (endHue - startHue) * progress;
        return (hue % 1.0F + 1.0F) % 1.0F;
    }

    private float[] rgbToHsl(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2.0F;

        float h, s;
        if (max == min) {
            h = s = 0.0F;
        } else {
            float diff = max - min;
            s = (l > 0.5F) ? (diff / (2.0F - max - min)) : (diff / (max + min));
            if (max == r) {
                h = (g - b) / diff + ((g < b) ? 6.0F : 0.0F);
            } else if (max == g) {
                h = (b - r) / diff + 2.0F;
            } else {
                h = (r - g) / diff + 4.0F;
            }
            h /= 6.0F;
        }
        return new float[]{h, s, l};
    }

    private int hslToRgb(float h, float s, float l) {
        float r, g, b;
        if (s == 0.0F) {
            r = g = b = l;
        } else {
            float q = (l < 0.5F) ? (l * (1.0F + s)) : (l + s - l * s);
            float p = 2.0F * l - q;
            r = hueToRgbComponent(p, q, h + 0.33333334F);
            g = hueToRgbComponent(p, q, h);
            b = hueToRgbComponent(p, q, h - 0.33333334F);
        }
        return (int) (r * 255.0F) << 16 | (int) (g * 255.0F) << 8 | (int) (b * 255.0F);
    }

    private float hueToRgbComponent(float p, float q, float t) {
        if (t < 0.0F) t += 1.0F;
        if (t > 1.0F) t -= 1.0F;
        if (t < 0.16666667F) return p + (q - p) * 6.0F * t;
        if (t < 0.5F) return q;
        if (t < 0.6666667F) return p + (q - p) * (0.6666667F - t) * 6.0F;
        return p;
    }

    // ═══════════════════════════════════════════════════════════════
    // 缓动 / ease interpolation
    // ═══════════════════════════════════════════════════════════════

    private int interpolateColorWithEasing(int startColor, int endColor, float progress, EasingType easing) {
        return interpolateColorHSL(startColor, endColor, applyEasing(progress, easing));
    }

    private float applyEasing(float progress, EasingType easing) {
        return switch (easing) {
            case LINEAR -> progress;
            case EASE_IN -> progress * progress;
            case EASE_OUT -> 1.0F - (1.0F - progress) * (1.0F - progress);
            case EASE_IN_OUT -> (progress < 0.5F)
                    ? 2.0F * progress * progress
                    : 1.0F - 2.0F * (1.0F - progress) * (1.0F - progress);
            case SMOOTH_STEP -> progress * progress * (3.0F - 2.0F * progress);
        };
    }

    // ═══════════════════════════════════════════════════════════════
    // Hue → RGB (色相环渐变用)
    // ═══════════════════════════════════════════════════════════════

    private static int hueToRgb(float hue, float saturation, float brightness) {
        int h = (int) (hue * 6.0F);
        float f = hue * 6.0F - h;
        float p = brightness * (1.0F - saturation);
        float q = brightness * (1.0F - f * saturation);
        float t = brightness * (1.0F - (1.0F - f) * saturation);

        float r, g, b;
        switch (h % 6) {
            case 0 -> { r = brightness; g = t; b = p; }
            case 1 -> { r = q; g = brightness; b = p; }
            case 2 -> { r = p; g = brightness; b = t; }
            case 3 -> { r = p; g = q; b = brightness; }
            case 4 -> { r = t; g = p; b = brightness; }
            case 5 -> { r = brightness; g = p; b = q; }
            default -> { r = 0; g = 0; b = 0; }
        }
        return (int) (r * 255.0F) << 16 | (int) (g * 255.0F) << 8 | (int) (b * 255.0F);
    }

    // ═══════════════════════════════════════════════════════════════
    // 格式
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder bold() { component.withStyle(ChatFormatting.BOLD); return this; }
    public TextBuilder italic() { component.withStyle(ChatFormatting.ITALIC); return this; }
    public TextBuilder underlined() { component.withStyle(ChatFormatting.UNDERLINE); return this; }
    public TextBuilder strikethrough() { component.withStyle(ChatFormatting.STRIKETHROUGH); return this; }
    public TextBuilder obfuscated() { component.withStyle(ChatFormatting.OBFUSCATED); return this; }

    // ═══════════════════════════════════════════════════════════════
    // 内容追加
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder append(Component other) {
        if (other != null) component.append(other);
        return this;
    }

    public TextBuilder append(TextBuilder other) {
        if (other != null) component.append(other.build());
        return this;
    }

    public TextBuilder append(String text) {
        if (text != null) component.append(Component.literal(text));
        return this;
    }

    public TextBuilder appendTranslate(String key, Object... args) {
        Objects.requireNonNull(key, "translation key must not be null");
        return append(translate(key, args).build());
    }

    public TextBuilder appendTranslateStyled(String key, ChatFormatting color, Object... args) {
        Objects.requireNonNull(key, "translation key must not be null");
        Objects.requireNonNull(color, "color must not be null");
        return append(translateStyled(key, color, args).build());
    }

    public TextBuilder appendKeyValue(String key, Object value, ChatFormatting keyColor, ChatFormatting valueColor) {
        Objects.requireNonNull(key, "key name must not be null");
        Objects.requireNonNull(keyColor, "key name color must not be null");
        Objects.requireNonNull(valueColor, "value color must not be null");
        return append(Component.literal(key + ": ").withStyle(keyColor))
                .append(convertToComponent(value).copy().withStyle(valueColor));
    }

    public TextBuilder appendKeyValue(String key, Object value) {
        Objects.requireNonNull(key, "key name must not be null");
        return append(Component.literal(key + ": ")).append(convertToComponent(value).copy());
    }

    // ═══════════════════════════════════════════════════════════════
    // 实用方法
    // ═══════════════════════════════════════════════════════════════

    public TextBuilder asListItem() {
        component = Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY).append(component);
        return this;
    }

    public TextBuilder newLine() { return append("\n"); }

    public boolean isEmpty() { return component.getString().isEmpty(); }

    public TextBuilder copy() { return new TextBuilder(component.copy()); }

    public static TextBuilder keyValue(String key, Object value) {
        Objects.requireNonNull(key, "key name must not be null");
        return of(key).gray().append(": ").append(of(value).gold());
    }

    public static TextBuilder progressBar(int current, int max, int length) {
        if (current < 0) throw new IllegalArgumentException("current must not be negative");
        if (max <= 0) throw new IllegalArgumentException("max must be greater than 0");
        if (current > max) throw new IllegalArgumentException("current must not exceed max");
        if (length <= 0) throw new IllegalArgumentException("progress bar length must be greater than 0");

        double progress = (double) current / max;
        int filled = (int) (progress * length);

        TextBuilder bar = of("[");
        for (int i = 0; i < length; i++) {
            if (i < filled) bar.append("|").green();
            else bar.append("|").darkGray();
        }
        return bar.append("]");
    }

    // ═══════════════════════════════════════════════════════════════
    // 构建
    // ═══════════════════════════════════════════════════════════════

    public MutableComponent build() {
        return component != null ? component : Component.empty();
    }

    public String buildString() {
        return component.getString();
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部类型
    // ═══════════════════════════════════════════════════════════════

    public enum GradientType { SPECTRUM, CUSTOM_FLOW }

    public enum EasingType { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, SMOOTH_STEP }

    public record GradientConfig(int[] colors, long speed, GradientType gradientType, float phaseShift,
                                  EasingType easing, boolean useHSLInterpolation) {

        public GradientConfig {
            if (colors == null || colors.length == 0) throw new IllegalArgumentException("color array must not be empty");
            if (speed <= 0) throw new IllegalArgumentException("flow speed must be greater than 0");
            if (phaseShift < 0.0F || phaseShift > 1.0F) throw new IllegalArgumentException("phase shift must be in [0, 1]");
            Objects.requireNonNull(gradientType, "gradient type must not be null");
            Objects.requireNonNull(easing, "easing type must not be null");
        }

        public static GradientConfig spectrum(long speed) { return spectrum(speed, 0.0F); }

        public static GradientConfig spectrum(long speed, float phaseShift) {
            return new GradientConfig(
                    new int[]{0xFF0000, 0xFFCC00, 0x00FF00, 0x00FFFF, 0x0000FF, 0xFF00FF, 0xFF0000},
                    speed, GradientType.SPECTRUM, phaseShift, EasingType.SMOOTH_STEP, true);
        }

        public static GradientConfig customFlow(long speed, float phaseShift, int... colors) {
            return new GradientConfig(colors, speed, GradientType.CUSTOM_FLOW, phaseShift,
                    EasingType.SMOOTH_STEP, true);
        }

        public GradientConfig withPhaseShift(float newPhaseShift) {
            return new GradientConfig(colors, speed, gradientType, newPhaseShift, easing, useHSLInterpolation);
        }
    }
}
