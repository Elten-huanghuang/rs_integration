package com.huanghuang.rsintegration.disk.rs;

import com.huanghuang.rsintegration.disk.core.UnifiedDiskSummary;
import com.huanghuang.rsintegration.util.TextBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 展示与查询分离，便于验证摘要、展开说明和未初始化状态。 */
public final class UnifiedDiskTooltip {
    private static final String PREFIX = "item.rs_integration.unified_storage_disk.";
    private static final int[] COLORS = {0x55FFFF, 0x5599FF, 0xAA55FF, 0xFF55CC};
    public record View(UnifiedDiskSummary summary, boolean unavailable, boolean expanded) {}
    private UnifiedDiskTooltip() {}

    public static void append(List<Component> tooltip, UUID id, View view) {
        if (id == null) {
            tooltip.add(Component.translatable(PREFIX + "uninitialized").withStyle(ChatFormatting.GRAY));
        } else {
            UnifiedDiskSummary summary = view == null ? null : view.summary();
            if (summary == null) {
                tooltip.add(Component.translatable(PREFIX + (view != null && view.unavailable()
                        ? "unavailable" : "loading")).withStyle(ChatFormatting.GRAY));
            } else {
                tooltip.add(Component.translatable(PREFIX + "stored_items", number(summary.items())).withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable(PREFIX + "stored_fluids", number(summary.fluids())).withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable(PREFIX + "item_types", number(summary.itemTypes()),
                        number(summary.itemCapacity())).withStyle(ChatFormatting.GRAY));
                tooltip.add(Component.translatable(PREFIX + "fluid_types", number(summary.fluidTypes()),
                        number(summary.fluidCapacity())).withStyle(ChatFormatting.GRAY));
            }
            tooltip.add(Component.translatable(PREFIX + "id", id.toString()).withStyle(ChatFormatting.DARK_GRAY));
        }
        tooltip.add(Component.empty());
        tooltip.add(TextBuilder.translate(PREFIX + "tooltip.title").colorFlow(1800L, 0.0F, COLORS).bold().build());
        if (view == null || !view.expanded()) {
            tooltip.add(TextBuilder.translate(PREFIX + "tooltip.subtitle").colorFlow(1800L, 0.35F, COLORS).build());
            tooltip.add(TextBuilder.translate(PREFIX + "tooltip.subtitle_2").colorFlow(1800L, 0.7F, COLORS).build());
            tooltip.add(Component.translatable(PREFIX + "tooltip.expand").withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable(PREFIX + "tooltip.rules").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            for (int line = 1; line <= 6; line++) {
                tooltip.add(TextBuilder.of("• ").darkAqua().append(TextBuilder.translate(
                        PREFIX + "tooltip.detail_" + line).gray()).build());
            }
        }
    }

    private static String number(long value) { return String.format(Locale.ROOT, "%,d", value); }
}
