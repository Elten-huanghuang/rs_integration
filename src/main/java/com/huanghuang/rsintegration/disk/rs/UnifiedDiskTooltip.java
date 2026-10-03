package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.UnifiedDiskSummary;
import com.huanghuang.rsintegration.util.DiskTooltipEffects;
import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 展示与查询分离，便于验证摘要、展开说明和未初始化状态。 */
public final class UnifiedDiskTooltip {
    private static final String PREFIX = "item.rs_integration.unified_storage_disk.";
    public record View(UnifiedDiskSummary summary, boolean unavailable, boolean expanded) {}
    private UnifiedDiskTooltip() {}

    public static void append(List<Component> tooltip, UUID id, View view) {
        if (id == null) {
            tooltip.add(DiskTooltipEffects.flow(PREFIX + "uninitialized", DiskTooltipEffects.Theme.GUIXU,
                    DiskTooltipEffects.Tone.WARNING, 0.0F));
        } else {
            UnifiedDiskSummary summary = view == null ? null : view.summary();
            if (summary == null) {
                tooltip.add(DiskTooltipEffects.flow(PREFIX + (view != null && view.unavailable()
                        ? "unavailable" : "loading"), DiskTooltipEffects.Theme.GUIXU,
                        view != null && view.unavailable()
                                ? DiskTooltipEffects.Tone.WARNING : DiskTooltipEffects.Tone.MUTED, 0.0F));
            } else {
                tooltip.add(DiskTooltipEffects.flow(PREFIX + "stored_items", DiskTooltipEffects.Theme.GUIXU,
                        DiskTooltipEffects.Tone.MAIN, 0.0F, number(summary.items())));
                tooltip.add(DiskTooltipEffects.flow(PREFIX + "stored_fluids", DiskTooltipEffects.Theme.GUIXU,
                        DiskTooltipEffects.Tone.FLUID, 0.18F, number(summary.fluids())));
                tooltip.add(DiskTooltipEffects.flow(PREFIX + "item_types", DiskTooltipEffects.Theme.GUIXU,
                        DiskTooltipEffects.Tone.MAIN, 0.36F, number(summary.itemTypes()),
                        number(summary.itemCapacity())));
                tooltip.add(DiskTooltipEffects.flow(PREFIX + "fluid_types", DiskTooltipEffects.Theme.GUIXU,
                        DiskTooltipEffects.Tone.FLUID, 0.54F, number(summary.fluidTypes()),
                        number(summary.fluidCapacity())));
            }
            tooltip.add(DiskTooltipEffects.flow(PREFIX + "id", DiskTooltipEffects.Theme.GUIXU,
                    DiskTooltipEffects.Tone.MUTED, 0.7F, id.toString()));
        }
        tooltip.add(Component.empty());
        tooltip.add(DiskTooltipEffects.flow(PREFIX + "tooltip.title", DiskTooltipEffects.Theme.GUIXU,
                DiskTooltipEffects.Tone.MAIN, 0.0F).copy().withStyle(ChatFormatting.BOLD));
        if (view == null || !view.expanded()) {
            tooltip.add(DiskTooltipEffects.flow(PREFIX + "tooltip.subtitle", DiskTooltipEffects.Theme.GUIXU,
                    DiskTooltipEffects.Tone.MAIN, 0.35F));
            tooltip.add(DiskTooltipEffects.flow(PREFIX + "tooltip.subtitle_2", DiskTooltipEffects.Theme.GUIXU,
                    DiskTooltipEffects.Tone.MAIN, 0.7F));
            tooltip.add(DiskTooltipEffects.flow(PREFIX + "tooltip.expand", DiskTooltipEffects.Theme.GUIXU,
                    DiskTooltipEffects.Tone.HINT, 0.25F));
        } else {
            tooltip.add(DiskTooltipEffects.flow(PREFIX + "tooltip.rules", DiskTooltipEffects.Theme.GUIXU,
                    DiskTooltipEffects.Tone.HINT, 0.0F).copy().withStyle(ChatFormatting.BOLD));
            for (int line = 1; line <= 8; line++) {
                tooltip.add(DiskTooltipEffects.flow(TextBuilder.of("• ").append(Component.translatable(
                        PREFIX + "tooltip.detail_" + line)).build(), DiskTooltipEffects.Theme.GUIXU,
                        DiskTooltipEffects.Tone.MUTED, line * 0.09F));
            }
        }
    }

    private static String number(long value) { return String.format(Locale.ROOT, "%,d", value); }
}
