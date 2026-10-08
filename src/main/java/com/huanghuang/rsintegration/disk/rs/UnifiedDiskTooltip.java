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
    public record View(UnifiedDiskSummary summary, boolean unavailable, boolean expanded, UnifiedDiskFailure failure) {
        public View(UnifiedDiskSummary summary, boolean unavailable, boolean expanded) {
            this(summary, unavailable, expanded, null);
        }
    }
    private UnifiedDiskTooltip() {}

    public static void append(List<Component> tooltip, UUID id, View view) {
        if (id == null) {
            tooltip.add(DiskTooltipEffects.tint(PREFIX + "uninitialized", DiskTooltipEffects.Theme.GUIXU,
                    DiskTooltipEffects.Tone.WARNING));
        } else {
            UnifiedDiskSummary summary = view == null ? null : view.summary();
            if (summary == null) {
                if (view != null && view.unavailable()) {
                    tooltip.add(Component.translatable(PREFIX + "unavailable")
                            .withStyle(ChatFormatting.RED, ChatFormatting.BOLD));
                    if (view.failure() != null) {
                        tooltip.add(view.failure().reason().copy().withStyle(ChatFormatting.RED));
                        tooltip.add(view.failure().action().copy().withStyle(ChatFormatting.YELLOW));
                    } else {
                        tooltip.add(Component.translatable(PREFIX + "failure.contact_admin").withStyle(ChatFormatting.YELLOW));
                    }
                    tooltip.add(Component.translatable(PREFIX + "failure.preserved").withStyle(ChatFormatting.GRAY));
                } else {
                    tooltip.add(DiskTooltipEffects.tint(PREFIX + "loading", DiskTooltipEffects.Theme.GUIXU,
                            DiskTooltipEffects.Tone.MUTED));
                }
            } else {
                tooltip.add(DiskTooltipEffects.tint(PREFIX + "stored_items", DiskTooltipEffects.Theme.GUIXU,
                        DiskTooltipEffects.Tone.MAIN, number(summary.items())));
                tooltip.add(DiskTooltipEffects.tint(PREFIX + "stored_fluids", DiskTooltipEffects.Theme.GUIXU,
                        DiskTooltipEffects.Tone.FLUID, number(summary.fluids())));
                tooltip.add(DiskTooltipEffects.tint(PREFIX + "item_types", DiskTooltipEffects.Theme.GUIXU,
                        DiskTooltipEffects.Tone.MAIN, number(summary.itemTypes()),
                        number(summary.itemCapacity())));
                tooltip.add(DiskTooltipEffects.tint(PREFIX + "fluid_types", DiskTooltipEffects.Theme.GUIXU,
                        DiskTooltipEffects.Tone.FLUID, number(summary.fluidTypes()),
                        number(summary.fluidCapacity())));
            }
            tooltip.add(DiskTooltipEffects.tint(PREFIX + "id", DiskTooltipEffects.Theme.GUIXU,
                    DiskTooltipEffects.Tone.MUTED, id.toString()));
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
