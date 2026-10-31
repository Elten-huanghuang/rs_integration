package com.huanghuang.rsintegration.util;

import com.huanghuang.rsintegration.feature.experience.ExperienceWandSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

/** 两种终端式物品的主题文字；展开状态由客户端传入，便于独立验证提示内容。 */
public final class SpecialItemTooltip {
    private SpecialItemTooltip() {}

    public static void appendExperience(List<Component> tooltip, ExperienceWandSettings settings,
                                        boolean expanded) {
        var theme = DiskTooltipEffects.Theme.EXPERIENCE;
        Component mode = Component.translatable("experience.rs_integration.mode."
                + settings.mode().name().toLowerCase(Locale.ROOT));
        tooltip.add(DiskTooltipEffects.tint("experience.rs_integration.mode_label",
                theme, DiskTooltipEffects.Tone.MAIN, mode));
        tooltip.add(DiskTooltipEffects.tint("experience.rs_integration.target",
                theme, DiskTooltipEffects.Tone.MUTED, settings.targetLevel()));
        if (settings.mending()) {
            tooltip.add(DiskTooltipEffects.tint("experience.rs_integration.mending",
                    theme, DiskTooltipEffects.Tone.FLUID));
        }
        tooltip.add(DiskTooltipEffects.flow("experience.rs_integration.open",
                theme, DiskTooltipEffects.Tone.HINT, 0));
        appendDetails(tooltip, "experience.rs_integration.tooltip.", theme, expanded, 4);
    }

    public static void appendFood(List<Component> tooltip, boolean expanded) {
        var theme = DiskTooltipEffects.Theme.FOOD;
        tooltip.add(DiskTooltipEffects.flow("item.rs_integration.order_terminal.tip",
                theme, DiskTooltipEffects.Tone.MAIN, 0));
        tooltip.add(DiskTooltipEffects.tint("item.rs_integration.order_terminal.setup",
                theme, DiskTooltipEffects.Tone.FLUID));
        appendDetails(tooltip, "item.rs_integration.order_terminal.tooltip.", theme, expanded, 3);
    }

    private static void appendDetails(List<Component> tooltip, String prefix,
                                      DiskTooltipEffects.Theme theme, boolean expanded, int lines) {
        if (!expanded) {
            tooltip.add(DiskTooltipEffects.flow(prefix + "expand",
                    theme, DiskTooltipEffects.Tone.HINT, 0.3F));
            return;
        }
        tooltip.add(Component.empty());
        tooltip.add(DiskTooltipEffects.flow(prefix + "title",
                theme, DiskTooltipEffects.Tone.HINT, 0).copy().withStyle(ChatFormatting.BOLD));
        for (int i = 1; i <= lines; i++) {
            tooltip.add(DiskTooltipEffects.tint(Component.literal("• ")
                    .append(Component.translatable(prefix + "detail_" + i)),
                    theme, DiskTooltipEffects.Tone.MUTED));
        }
    }
}
